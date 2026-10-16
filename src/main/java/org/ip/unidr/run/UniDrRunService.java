package org.ip.unidr.run;

import org.ip.unidr.query.UniDrQueryService;
import org.ip.unidr.render.UniDrReportCompiler;
import org.ip.unidr.render.UniDrRenderedReport;
import org.ipro.reportstudio.render.ReportCompiler;
import org.ipro.reportstudio.query.ReportRunQuota;
import org.ipro.reportstudio.run.ReportTaskExecutor;
import org.ipro.reportstudio.run.ReportTaskCompletion;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/** Reuses the report worker/auth snapshot and the global report quota. */
public final class UniDrRunService {
    private final UniDrAccess access;
    private final UniDrQueryService queries;
    private final ReportTaskExecutor worker;
    private final ReportRunQuota quota;
    private final Clock clock;
    private final UniDrReportCompiler compiler;
    private final ReportCompiler exporter;
    public UniDrRunService(UniDrAccess access, UniDrQueryService queries, ReportTaskExecutor worker, ReportRunQuota quota, Clock clock,
                          UniDrReportCompiler compiler, ReportCompiler exporter) {
        this.access = access; this.queries = queries; this.worker = worker; this.quota = quota; this.clock = clock;
        this.compiler = compiler; this.exporter = exporter;
    }

    public static final class Ticket {
        private final ReportTaskCompletion<UniDrRunResult> future = new ReportTaskCompletion<>();
        public CompletionStage<UniDrRunResult> completion() { return future.stage(); }
        /** Stops publication; SQL already running still ends under its timeout and retains its quota slot. */
        public void cancel() { future.cancel(); }
    }

    public Ticket submit(UniDrRunRequest request) {
        String owner = access.requireUser();
        var now=clock.instant();queries.validateRequest(request,now,owner);
        var slot = quota.tryAcquire(0);
        if (slot == null) throw new UniDrRunException(UniDrRunException.Code.BUSY, quota.busyMessage());
        var ticket = new Ticket();
        try {
            worker.execute(SecurityContextHolder.getContext().getAuthentication(), RequestContextHolder.getRequestAttributes(), () -> {
                try {
                    if (ticket.future.isCancelled()) return;
                    access.requireOwner(owner);
                    var data = queries.read(request, now, owner);
                    if (ticket.future.isCancelled()) return;
                    var print = data.source()==null?compiler.compile(request.definition(), data.dataset()):compiler.compile(request.definition(),data.dataset(),data.source());
                    access.requireOwner(owner);
                    var rendered = new UniDrRenderedReport(print, request.definition().title(), owner, access, exporter);
                    ticket.future.complete(new UniDrRunResult(UUID.randomUUID(), owner, request.definition(), now, clock.instant(), data, rendered));
                } catch (Throwable failure) { ticket.future.fail(failure); }
                finally { slot.release(); }
            });
        } catch (RejectedExecutionException rejected) {
            slot.release(); throw new UniDrRunException(UniDrRunException.Code.QUEUE_FULL, "Очередь отчётов заполнена. Повторите позже", rejected);
        } catch (RuntimeException failed) { slot.release(); throw failed; }
        return ticket;
    }

    public Session newSession() { return new Session(access.requireUser()); }

    /** UI owns this small object and closes it on detach; revisions suppress late completions. */
    public final class Session implements AutoCloseable {
        private final String owner;
        private long revision;
        private boolean closed;
        private Ticket pending;
        private UniDrRunResult latest;
        private Session(String owner) { this.owner = owner; }
        public synchronized Ticket start(UniDrRunRequest request) {
            access.requireOwner(owner); requireOpen();
            invalidate();
            long submittedRevision = revision;
            Ticket ticket = submit(request); pending = ticket;
            ticket.completion().whenComplete((result, failure) -> {
                synchronized (Session.this) {
                    if (!closed && revision == submittedRevision && failure == null) latest = result;
                }
            });
            return ticket;
        }
        public synchronized void invalidate() {
            access.requireOwner(owner); requireOpen();
            revision++; latest = null;
            if (pending != null) pending.cancel();
        }
        public synchronized Optional<UniDrRunResult> latest() { access.requireOwner(owner); requireOpen(); return Optional.ofNullable(latest); }
        @Override public synchronized void close() {
            closed = true; revision++; latest = null;
            if (pending != null) pending.cancel();
        }
        private void requireOpen() { if (closed) throw new UniDrRunException(UniDrRunException.Code.CLOSED, "Редактор закрыт"); }
    }
}

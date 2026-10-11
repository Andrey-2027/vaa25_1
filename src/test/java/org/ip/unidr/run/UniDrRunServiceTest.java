package org.ip.unidr.run;

import org.ip.unidr.model.UniDrDefinitions;
import org.ip.unidr.query.UniDrQueryService;
import org.ip.unidr.render.UniDrReportCompiler;
import org.ipro.reportstudio.render.ReportCompiler;
import org.ipro.reportstudio.data.*;
import org.ipro.reportstudio.query.ReportRunQuota;
import org.ipro.reportstudio.run.ReportTaskExecutor;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrRunServiceTest {
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final UniDrAccess access = new UniDrAccess(enabled::get, () -> SecurityContextHolder.getContext().getAuthentication().getName());
    private final UniDrQueryService queries = mock(UniDrQueryService.class);
    private final UniDrReportCompiler compiler = mock(UniDrReportCompiler.class);
    private final ReportCompiler exporter = mock(ReportCompiler.class);
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final ReportRunQuota quota = new ReportRunQuota(2);
    private final UniDrRunRequest request = new UniDrRunRequest(UniDrDefinitions.receivingItemsSummary(), Map.of(), ZoneOffset.UTC);
    private final UniDrRunService service = service(new ReportTaskExecutor(queue::add));
    private UniDrRunService service(ReportTaskExecutor worker) {
        return new UniDrRunService(access, queries, worker, quota, Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC), compiler, exporter);
    }
    private void login(String name) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, null, List.of())); }
    private void finish() { queue.remove().run(); }
    private UniDrQueryService.Data data() { return new UniDrQueryService.Data(new ReportDataset(new QueryField[0], new ReportRow[0]), Map.of()); }
    @BeforeEach void setup() {
        login("alice"); when(queries.read(any(), any(), any())).thenReturn(data());
        when(compiler.compile(any(), any())).thenReturn(new net.sf.jasperreports.engine.JasperPrint());
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes(); }

    @Test void capturesUserAndWorkerClearsThreadContext() {
        when(queries.read(any(), any(), eq("alice"))).thenAnswer(call -> { assertThat(access.requireUser()).isEqualTo("alice"); return data(); });
        var ticket = service.submit(request); login("bob"); finish();
        assertThat(ticket.completion().toCompletableFuture().join().owner()).isEqualTo("alice");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(RequestContextHolder.getRequestAttributes()).isNull(); assertThat(quota.activeRuns()).isZero();
    }
    @Test void globalQuotaRejectsBeforeQueueingAndReleasesOnSuccess() {
        var oldUdrSlot = quota.tryAcquire(0);
        service.submit(request);
        assertThatThrownBy(() -> service.submit(request)).isInstanceOfSatisfying(UniDrRunException.class, e -> assertThat(e.code()).isEqualTo(UniDrRunException.Code.BUSY));
        assertThat(queue).hasSize(1); finish(); oldUdrSlot.release(); assertThat(quota.activeRuns()).isZero();
    }
    @Test void queryFailureReleasesQuotaAndPublishesNoResult() {
        when(queries.read(any(), any(), any())).thenThrow(new UniDrRunException(UniDrRunException.Code.TIMEOUT, "timeout"));
        var ticket = service.submit(request); finish();
        assertThatThrownBy(() -> ticket.completion().toCompletableFuture().join()).hasCauseInstanceOf(UniDrRunException.class);
        assertThat(quota.activeRuns()).isZero();
    }
    @Test void rejectedQueueReleasesQuota() {
        var rejected = service(new ReportTaskExecutor(task -> { throw new RejectedExecutionException(); }));
        assertThatThrownBy(() -> rejected.submit(request)).isInstanceOfSatisfying(UniDrRunException.class, e -> assertThat(e.code()).isEqualTo(UniDrRunException.Code.QUEUE_FULL));
        assertThat(quota.activeRuns()).isZero(); verifyNoInteractions(queries);
    }
    @Test void cancelledQueuedTaskHoldsSlotUntilWorkerSkipsIt() {
        var ticket = service.submit(request); ticket.cancel(); assertThat(quota.activeRuns()).isEqualTo(1);
        finish(); assertThat(quota.activeRuns()).isZero(); verifyNoInteractions(queries);
    }
    @Test void latestRevisionWinsEvenWhenOldTaskFinishesLast() {
        var session = service.newSession(); var old = session.start(request); var newest = session.start(request);
        queue.removeLast().run(); login("alice");
        assertThat(session.latest()).contains(newest.completion().toCompletableFuture().join());
        finish(); login("alice");
        assertThat(session.latest()).contains(newest.completion().toCompletableFuture().join());
        assertThat(old.completion().toCompletableFuture()).isCompletedExceptionally(); assertThat(quota.activeRuns()).isZero();
    }
    @Test void settingChangesDuringRunningQueryDiscardCompletion() {
        var session = service.newSession();
        when(queries.read(any(), any(), any())).thenAnswer(call -> { session.invalidate(); return data(); });
        session.start(request); finish(); login("alice"); assertThat(session.latest()).isEmpty(); assertThat(quota.activeRuns()).isZero();
    }
    @Test void closeDuringRunningQueryDiscardsResultAndReleasesSlot() {
        var session = service.newSession();
        when(queries.read(any(), any(), any())).thenAnswer(call -> { session.close(); return data(); });
        session.start(request); finish(); login("alice");
        assertThatThrownBy(session::latest).isInstanceOfSatisfying(UniDrRunException.class, e -> assertThat(e.code()).isEqualTo(UniDrRunException.Code.CLOSED));
        assertThat(quota.activeRuns()).isZero();
    }
    @Test void sessionsDoNotShareResultsAndRejectAnotherOwner() {
        var first = service.newSession(); var second = service.newSession(); first.start(request); finish(); login("alice");
        assertThat(first.latest()).isPresent(); assertThat(second.latest()).isEmpty(); login("bob");
        assertThatThrownBy(first::latest).isInstanceOf(UniDrRunException.class); assertThatThrownBy(first::invalidate).isInstanceOf(UniDrRunException.class);
    }
    @Test void turningPilotOffAfterQueueingFailsClosed() {
        var ticket = service.submit(request); enabled.set(false); finish();
        assertThatThrownBy(() -> ticket.completion().toCompletableFuture().join()).hasCauseInstanceOf(UniDrRunException.class);
        assertThat(quota.activeRuns()).isZero(); verifyNoInteractions(queries);
    }
    @Test void anonymousAndUnauthenticatedAndSystemCannotSubmit() {
        SecurityContextHolder.clearContext(); assertThatThrownBy(() -> service.submit(request)).isInstanceOf(UniDrRunException.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice", "password"));
        assertThatThrownBy(() -> service.submit(request)).isInstanceOf(UniDrRunException.class);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThatThrownBy(() -> service.submit(request)).isInstanceOf(UniDrRunException.class);
        login("system"); assertThatThrownBy(() -> service.submit(request)).isInstanceOf(UniDrRunException.class);
        assertThat(queue).isEmpty(); assertThat(quota.activeRuns()).isZero();
    }
    @Test void privilegedRlsContextCannotBeUsedForUiReport() {
        try (var context = mockStatic(org.ipro.rls.RlsContext.class)) {
            context.when(org.ipro.rls.RlsContext::isBypassed).thenReturn(true);
            assertThatThrownBy(() -> service.submit(request)).isInstanceOfSatisfying(UniDrRunException.class,
                error -> assertThat(error.code()).isEqualTo(UniDrRunException.Code.ACCESS_DENIED));
        }
        assertThat(queue).isEmpty(); assertThat(quota.activeRuns()).isZero();
    }
    @Test void renderingUsesWorkerSubjectAndStillHoldsQuotaAndFailuresReleaseIt() {
        when(compiler.compile(any(), any())).thenAnswer(call -> {
            assertThat(access.requireUser()).isEqualTo("alice"); assertThat(quota.activeRuns()).isEqualTo(1);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new org.ipro.reportstudio.render.ReportRenderException("Макет");
        });
        var ticket = service.submit(request); finish();
        assertThatThrownBy(() -> ticket.completion().toCompletableFuture().join()).hasCauseInstanceOf(org.ipro.reportstudio.render.ReportRenderException.class);
        assertThat(quota.activeRuns()).isZero();
    }
}

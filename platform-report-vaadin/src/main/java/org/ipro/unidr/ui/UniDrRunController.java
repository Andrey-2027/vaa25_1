package org.ipro.unidr.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedHttpSession;
import org.ipro.unidr.run.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.ipro.report.task.ReportTaskCompletion;
import java.util.function.Consumer;

/** One owner for the parameter/result window pair. UI callbacks always run under the UI lock. */
public final class UniDrRunController implements AutoCloseable {
    private final UniDrRunService.Session session;
    private ReportTaskCompletion<UniDrRunResult> pending;
    private Consumer<UniDrRunResult> success;
    private Consumer<Throwable> failure;
    private Registration polling;
    private UI ui;
    private PollLease pollLease;
    private boolean closed;
    private UniDrDownloads downloads;

    public UniDrRunController(UniDrRunService runs) { session=runs.newSession(); }

    public void start(UniDrRunRequest request, UI target, Consumer<UniDrRunResult> succeeded, Consumer<Throwable> failed) {
        if(closed)throw new IllegalStateException("Окно запуска закрыто");
        invalidate();success=succeeded;failure=failed;
        var previous=RequestContextHolder.getRequestAttributes();
        try {
            var vaadin=VaadinSession.getCurrent();
            if(vaadin!=null && vaadin.getSession() instanceof WrappedHttpSession wrapped)
                RequestContextHolder.setRequestAttributes(new UniDrUiSessionAttributes(wrapped.getHttpSession()));
            pending=ReportTaskCompletion.observe(session.start(request).completion());
        } finally {
            if(previous==null)RequestContextHolder.resetRequestAttributes();else RequestContextHolder.setRequestAttributes(previous);
        }
        if(target!=null) {
            ui=target;
            pollLease=ComponentUtil.getData(ui,PollLease.class);
            if(pollLease==null){pollLease=new PollLease(ui.getPollInterval());ComponentUtil.setData(ui,PollLease.class,pollLease);}
            pollLease.users++;ui.setPollInterval(250);
            polling=ui.addPollListener(event->poll());
        }
    }

    void poll() {
        if(closed || pending==null || !pending.isDone())return;
        var completed=pending;pending=null;stopPolling();
        UniDrRunResult result;
        try {completed.join();result=session.latest().orElse(null);}
        catch(RuntimeException error) {failure.accept(error);return;}
        if(result!=null && !closed)success.accept(result);
    }

    UniDrDownloads downloads(UniDrRunResult result) {
        if(closed)throw new IllegalStateException("Окно результата закрыто");
        if(downloads!=null)downloads.invalidate();
        downloads=new UniDrDownloads(result.report());return downloads;
    }
    public void invalidate() {
        if(closed)return;
        pending=null;session.invalidate();stopPolling();
        if(downloads!=null){downloads.invalidate();downloads=null;}
    }
    private void stopPolling() {
        if(polling!=null){polling.remove();polling=null;}
        if(ui!=null){
            if(--pollLease.users==0){if(ui.getPollInterval()==250)ui.setPollInterval(pollLease.previousInterval);ComponentUtil.setData(ui,PollLease.class,null);}
            ui=null;pollLease=null;
        }
    }
    private static final class PollLease { final int previousInterval;int users;PollLease(int interval){previousInterval=interval;} }
    @Override public void close() {
        if(closed)return;
        closed=true;pending=null;session.close();stopPolling();
        if(downloads!=null){downloads.invalidate();downloads=null;}
    }
}

package org.ipro.reportstudio.run;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Completion and cancellation of a report worker result; never schedules work or uses a common pool. */
public final class ReportTaskCompletion<T> {
    private final CompletableFuture<T> result=new CompletableFuture<>();
    public CompletionStage<T> stage(){return result.minimalCompletionStage();}
    public boolean isDone(){return result.isDone();}
    public boolean isCancelled(){return result.isCancelled();}
    public T join(){return result.join();}
    public void cancel(){result.cancel(false);}
    public boolean complete(T value){return result.complete(value);}
    public boolean fail(Throwable error){return result.completeExceptionally(error);}
    /** Observe an existing report stage synchronously, without creating another worker. */
    public static <T> ReportTaskCompletion<T> observe(CompletionStage<T> stage) {
        var completion=new ReportTaskCompletion<T>();
        stage.whenComplete((value,error)->{if(error==null)completion.complete(value);else completion.fail(error);});
        return completion;
    }
}

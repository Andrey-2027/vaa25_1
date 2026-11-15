package org.ipro.query.ui.grid;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Порт фонового выполнения (UX-4). Модуль не знает о безопасности приложения, поэтому адаптер
 * приложения оборачивает задачу так, чтобы в рабочем потоке действовал тот же пользователь, что
 * в сессии UI: от него зависят права, RLS и квота.
 */
public interface QueryTaskRunner {
    <T> CompletableFuture<T> submit(Callable<T> task);

    /** Выполняет в вызвавшем потоке: без фона. Для тестов и режима «Постранично». */
    static QueryTaskRunner direct() {
        return new QueryTaskRunner() {
            @Override
            public <T> CompletableFuture<T> submit(Callable<T> task) {
                try {
                    return CompletableFuture.completedFuture(task.call());
                } catch (Throwable error) {
                    return CompletableFuture.failedFuture(error);
                }
            }
        };
    }

    /** Выполняет в пуле потоков без переноса пользователя; адаптер приложения оборачивает задачи сам. */
    static QueryTaskRunner of(Executor executor) {
        return new QueryTaskRunner() {
            @Override
            public <T> CompletableFuture<T> submit(Callable<T> task) {
                CompletableFuture<T> result = new CompletableFuture<>();
                try {
                    executor.execute(() -> {
                        try {
                            result.complete(task.call());
                        } catch (Throwable error) {
                            result.completeExceptionally(error);
                        }
                    });
                } catch (RuntimeException rejected) {
                    result.completeExceptionally(rejected);
                }
                return result;
            }
        };
    }
}

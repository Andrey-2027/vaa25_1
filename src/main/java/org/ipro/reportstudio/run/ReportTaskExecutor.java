package org.ipro.reportstudio.run;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, application-managed worker for report generation. */
public final class ReportTaskExecutor implements AutoCloseable {

    private final Executor executor;
    private final ThreadPoolExecutor ownedPool;

    public ReportTaskExecutor(int threads, int queueCapacity) {
        int poolSize = Math.max(1, threads);
        int queueSize = Math.max(1, queueCapacity);
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "report-worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.ownedPool = new ThreadPoolExecutor(poolSize, poolSize, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueSize), factory, new ThreadPoolExecutor.AbortPolicy());
        this.ownedPool.allowCoreThreadTimeOut(true);
        this.executor = ownedPool;
    }

    /** Visible for deterministic tests; lifecycle belongs to the supplied executor. */
    public ReportTaskExecutor(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedPool = null;
    }

    public void execute(Authentication source, RequestAttributes requestAttributes, Runnable task) {
        Authentication authentication = immutableSnapshot(source);
        Objects.requireNonNull(task, "task");
        executor.execute(() -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            if (requestAttributes != null) {
                RequestContextHolder.setRequestAttributes(requestAttributes);
            }
            try {
                task.run();
            } finally {
                RequestContextHolder.resetRequestAttributes();
                SecurityContextHolder.clearContext();
            }
        });
    }

    @Override
    public void close() {
        if (ownedPool != null) {
            ownedPool.shutdown();
        }
    }

    private static Authentication immutableSnapshot(Authentication source) {
        if (source == null || !source.isAuthenticated()
                || source.getName() == null || source.getName().isBlank()
                || "system".equals(source.getName())) {
            throw new org.ipro.rls.RlsAccessDeniedException(
                "Report worker requires an authenticated user");
        }
        return new UsernamePasswordAuthenticationToken(source.getName(), null,
            List.copyOf(source.getAuthorities()));
    }
}

package org.ipro.reportstudio.query;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Квота параллельных запусков отчётов (Фаза 2). Отчёт — тяжелейший запрос
 * системы (широкий SELECT + рендер), поэтому одновременно выполняется не
 * больше {@code maxParallelRuns} формирований; остальные получают жёсткий
 * отказ с человекочитаемым сообщением (не очередь — в V1 пользователь
 * повторяет запуск сам).
 * <p>
 * Потокобезопасность: {@link Semaphore}; активные счётчики (для сообщений
 * «занято X из N») — atomic.
 */
@Component
public class ReportRunQuota {

    /** Значение по умолчанию при отсутствии настройки. */
    public static final int DEFAULT_MAX_PARALLEL_RUNS = 2;

    private final int maxParallelRuns;
    private final Semaphore slots;
    private final AtomicInteger active = new AtomicInteger();

    public ReportRunQuota(@Value("${ipro.report.max-parallel-runs:2}") int maxParallelRuns) {
        if (maxParallelRuns <= 0) {
            throw new IllegalArgumentException("ipro.report.max-parallel-runs должен быть > 0");
        }
        this.maxParallelRuns = maxParallelRuns;
        this.slots = new Semaphore(maxParallelRuns);
    }

    /** Квота по умолчанию (для тестов и мест без Spring-конфигурации). */
    public static ReportRunQuota defaults() {
        return new ReportRunQuota(DEFAULT_MAX_PARALLEL_RUNS);
    }

    /**
     * Захватывает слот запуска отчёта.
     *
     * @param waitMs сколько ждать освобождения слота (0 = не ждать)
     * @return слот для последующего {@link #release}, или null — превышена квота
     */
    public Slot tryAcquire(long waitMs) {
        try {
            boolean acquired = waitMs > 0
                ? slots.tryAcquire(waitMs, TimeUnit.MILLISECONDS)
                : slots.tryAcquire();
            if (!acquired) {
                return null;
            }
            active.incrementAndGet();
            return () -> {
                active.decrementAndGet();
                slots.release();
            };
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Сколько запусков выполняется прямо сейчас. */
    public int activeRuns() {
        return active.get();
    }

    public int maxParallelRuns() {
        return maxParallelRuns;
    }

    /** Человекочитаемое описание переполнения. */
    public String busyMessage() {
        return "Сейчас уже выполняется " + active.get() + " из " + maxParallelRuns
            + " отчётов — повторите формирование позже";
    }

    /** Освобождаемый слот: release() снимает активность и место в квоте. */
    @FunctionalInterface
    public interface Slot {
        void release();
    }
}
package org.ipro.telemetry.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * L0-агрегаты: in-memory счётчики по ключам (метод / нормализованный SQL):
 * count, total, min, max и приближённый p95 по петле записей последних N
 * длительностей. Rolling-окно {@link #rollWindow()} сбрасывает накопленное
 * и возвращает снапшот окна (начало окна + счётчики).
 */
public final class PerfCounterStore {

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final long windowNanos;
    private volatile long windowStartNanos = System.nanoTime();
    private volatile long windowStartEpochMs = System.currentTimeMillis();

    public PerfCounterStore(long windowSeconds) {
        this.windowNanos = windowSeconds * 1_000_000_000L;
    }

    public void record(String key, long durationNanos) {
        counters.computeIfAbsent(key, ignored -> new Counter())
                .record(durationNanos);
    }

    /**
     * Если окно истекло — снять снапшоты всех счётчиков, сбросить их
     * и вернуть снапшот окна; иначе null.
     */
    public WindowSnapshot rollWindow() {
        long now = System.nanoTime();
        if (now - windowStartNanos < windowNanos) {
            return null;
        }
        synchronized (this) {
            if (now - windowStartNanos < windowNanos) {
                return null;
            }
            Instant windowStart = Instant.ofEpochMilli(windowStartEpochMs);
            windowStartNanos = now;
            windowStartEpochMs = System.currentTimeMillis();
            List<CounterSnapshot> snapshots = new ArrayList<>(counters.size());
            counters.forEach((key, counter) -> {
                CounterSnapshot snapshot = counter.snapshot();
                if (snapshot.count() > 0 && snapshot.totalMs() > 0) {
                    snapshots.add(snapshot.withKey(key));
                }
            });
            counters.clear();
            return new WindowSnapshot(windowStart, snapshots);
        }
    }

    public Map<String, Counter> counters() {
        return counters;
    }

    /** Снапшот окна: момент начала окна + счётчики, накопленные за окно. */
    public record WindowSnapshot(Instant windowStart, List<CounterSnapshot> snapshots) {
    }

    public record CounterSnapshot(String key, long count, double totalMs, double avgMs,
                                  double minMs, double maxMs, double p95Ms) {
        CounterSnapshot withKey(String key) {
            return new CounterSnapshot(key, count, totalMs, avgMs, minMs, maxMs, p95Ms);
        }
    }

    private static final class Counter {
        private static final int RING_SIZE = 100;

        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxNanos = new AtomicLong(0);
        private final long[] ring = new long[RING_SIZE];
        private final AtomicLong ringIndex = new AtomicLong(0);

        void record(long durationNanos) {
            count.increment();
            totalNanos.add(durationNanos);
            long min = minNanos.get();
            while (durationNanos < min && !minNanos.compareAndSet(min, durationNanos)) {
                min = minNanos.get();
            }
            long max = maxNanos.get();
            while (durationNanos > max && !maxNanos.compareAndSet(max, durationNanos)) {
                max = maxNanos.get();
            }
            ring[(int) (ringIndex.getAndIncrement() % RING_SIZE)] = durationNanos;
        }

        CounterSnapshot snapshot() {
            long cnt = count.sum();
            long total = totalNanos.sum();
            if (cnt == 0) {
                return new CounterSnapshot(null, 0, 0, 0, 0, 0, 0);
            }
            double p95 = p95Nanos();
            return new CounterSnapshot(
                    null,
                    cnt,
                    ms(total),
                    ms(total / cnt),
                    ms(minNanos.get()),
                    ms(maxNanos.get()),
                    ms((long) p95));
        }

        private double p95Nanos() {
            int size = Math.min(RING_SIZE, (int) ringIndex.get());
            if (size == 0) {
                return 0;
            }
            long[] values = new long[size];
            System.arraycopy(ring, 0, values, 0, size);
            java.util.Arrays.sort(values);
            int idx = Math.min(size - 1, (int) Math.floor(size * 0.95));
            return values[idx];
        }
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
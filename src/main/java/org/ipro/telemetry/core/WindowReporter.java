package org.ipro.telemetry.core;

import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Периодически (раз в окно L0) снимает агрегаты из PerfCounterStore и
 * логирует сводку по суммарному времени — «где тормозит» без записи в журнал.
 * Веха 2 добавит персист агрегатов в perf_stats тем же снапшотом.
 */
public final class WindowReporter implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger("ipro.telemetry.stats");

    private final PerfCounterStore store;
    private final long windowSeconds;
    private final Thread thread;
    private volatile boolean running = true;

    public WindowReporter(PerfCounterStore store, long windowSeconds) {
        this.store = store;
        this.windowSeconds = Math.max(5, windowSeconds);
        this.thread = new Thread(this, "telemetry-window-reporter");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public void run() {
        while (running) {
            try {
                Thread.sleep(windowSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                List<PerfCounterStore.CounterSnapshot> snapshots = store.rollWindow();
                if (snapshots != null && !snapshots.isEmpty()) {
                    report(snapshots);
                }
            } catch (Exception e) {
                log.warn("failed to roll telemetry window: {}", e.toString());
            }
        }
    }

    private void report(List<PerfCounterStore.CounterSnapshot> snapshots) {
        List<PerfCounterStore.CounterSnapshot> top = snapshots.stream()
                .sorted(Comparator.comparingDouble(PerfCounterStore.CounterSnapshot::totalMs).reversed())
                .limit(15)
                .toList();
        StringBuilder sb = new StringBuilder("L0 window aggregate (calls, avg ms, max ms, p95 ms):\n");
        for (PerfCounterStore.CounterSnapshot s : top) {
            sb.append(String.format("  %-60s %6d %9.2f %9.2f %9.2f%n",
                    s.key(), s.count(), s.avgMs(), s.maxMs(), s.p95Ms()));
        }
        log.info(sb.toString());
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
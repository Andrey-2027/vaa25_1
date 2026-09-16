package org.ipro.telemetry.core;

import java.util.Comparator;
import java.util.List;

import org.ipro.telemetry.api.AggregateStats;
import org.ipro.telemetry.api.EventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Периодически (раз в окно L0) снимает агрегаты из PerfCounterStore,
 * логирует сводку по суммарному времени и персистит агрегаты в perf_stats
 * через {@link EventSink#acceptStats}.
 */
public final class WindowReporter implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger("ipro.telemetry.stats");

    private final PerfCounterStore store;
    private final long windowSeconds;
    private final EventSink sink;
    private final Thread thread;
    private volatile boolean running = true;

    public WindowReporter(PerfCounterStore store, long windowSeconds, EventSink sink) {
        this.store = store;
        this.windowSeconds = Math.max(5, windowSeconds);
        this.sink = sink;
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
                PerfCounterStore.WindowSnapshot window = store.rollWindow();
                if (window != null && !window.snapshots().isEmpty()) {
                    report(window.snapshots());
                    if (sink != null && !sink.isNoop()) {
                        for (PerfCounterStore.CounterSnapshot s : window.snapshots()) {
                            sink.acceptStats(new AggregateStats(
                                    s.key(), s.count(), s.totalMs(), s.avgMs(),
                                    s.minMs(), s.maxMs(), s.p95Ms(), window.windowStart()));
                        }
                    }
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

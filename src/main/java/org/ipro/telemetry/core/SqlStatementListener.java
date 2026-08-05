package org.ipro.telemetry.core;

import java.util.ArrayDeque;
import java.util.Deque;

import org.hibernate.BaseSessionEventListener;

/**
 * Per-сессионный слушатель Hibernate (регистрируется свойством
 * {@code hibernate.session.events.auto} — инстанцируется самим Hibernate,
 * поэтому public no-arg конструктор и статический мост). Замеряет
 * реальное время выполнения JDBC-стейтментов и привязывает к текущему
 * фрейму операции: {@link Frame#addSql(long)}.
 * <p>
 * Покрывает ВСЕ стейтменты (не только SELECT) — в отличие от встроенного
 * {@code LOG_QUERIES_SLOWER_THAN_MS}, который меряет выборку результатов.
 */
public final class SqlStatementListener extends BaseSessionEventListener {

    /**
     * Hibernate инстанцирует этот слушатель per-сессии ({@code hibernate.session
     * .events.auto}), а одна Session не используется из разных потоков
     * одновременно — обычного Deque-поля достаточно, ThreadLocal не нужен.
     */
    private final Deque<Long> executeStarts = new ArrayDeque<>();

    @Override
    public void jdbcExecuteStatementStart() {
        if (guardPasses()) {
            executeStarts.push(System.nanoTime());
        }
    }

    @Override
    public void jdbcExecuteStatementEnd() {
        accountExecution(executeStarts);
    }

    @Override
    public void jdbcExecuteBatchStart() {
        if (guardPasses()) {
            executeStarts.push(System.nanoTime());
        }
    }

    @Override
    public void jdbcExecuteBatchEnd() {
        accountExecution(executeStarts);
    }

    private void accountExecution(Deque<Long> starts) {
        if (starts.isEmpty()) {
            return;
        }
        long duration = System.nanoTime() - starts.pop();
        Frame frame = SqlTimingBridge.currentFrame();
        if (frame != null) {
            frame.addSql(duration);
        }
    }

    private boolean guardPasses() {
        return TelemetryGuard.isEnabled() && !TelemetryGuard.isInsideLogging();
    }
}

package org.ipro.telemetry.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.api.UserContext;
import org.slf4j.MDC;

/**
 * Контекст выполняемой операции. ThreadLocal-стек фреймов:
 * «первый вход побеждает» — операция стартует только при пустом стеке,
 * все последующие сервисные вызовы становятся фреймами внутри неё.
 * <p>
 * Внутри записи событий (см. {@link TelemetryGuard}) аспект фреймы не создаёт —
 * иначе журнал логировал бы сам себя.
 */
public final class OperationContext {

    private final ThreadLocal<Deque<Frame>> stack = new ThreadLocal<>();
    private final ThreadLocal<Operation> currentOperation = new ThreadLocal<>();

    private final PerfCounterStore counterStore;
    private final UserContext userContext;
    private final OperationCompletionHandler completionHandler;
    private final int frameLimit;

    public OperationContext(PerfCounterStore counterStore,
                            UserContext userContext,
                            OperationCompletionHandler completionHandler,
                            int frameLimit) {
        this.counterStore = counterStore;
        this.userContext = userContext;
        this.completionHandler = completionHandler;
        this.frameLimit = frameLimit;
    }

    /**
     * Начать фрейм (или операцию, если стек пуст). null — телеметрия
     * выключена/активен guard/превышен лимит узлов дерева.
     */
    public Frame beginFrame(String name) {
        if (!TelemetryGuard.isEnabled() || TelemetryGuard.isInsideLogging()) {
            return null;
        }
        Deque<Frame> frames = stack.get();
        if (frames == null) {
            frames = new ArrayDeque<>();
            stack.set(frames);
        }

        if (frames.isEmpty()) {
            String incomingTraceId = MDC.get(MdcKeys.TRACE_ID);
            String traceId = incomingTraceId != null ? incomingTraceId : newTraceId();
            Operation operation = new Operation(
                    name,
                    traceId,
                    userContext.currentUsername(),
                    null);
            MDC.put(MdcKeys.TRACE_ID, operation.getTraceId());
            MDC.put(MdcKeys.USER, operation.getUser());
            MDC.put(MdcKeys.OPERATION, name);
            operation.addMdcKey(MdcKeys.TRACE_ID);
            operation.addMdcKey(MdcKeys.USER);
            operation.addMdcKey(MdcKeys.OPERATION);
            currentOperation.set(operation);
            frames.push(operation);
            return operation;
        }

        Operation operation = currentOperation.get();
        if (operation.nodeCount() >= frameLimit) {
            operation.incrementDroppedFrames();
            return null;
        }

        Frame frame = new Frame(name);
        frames.push(frame);
        operation.addChild(frame);
        return frame;
    }

    /**
     * Завершить фрейм. Для корневого фрейма — финализация операции:
     * агрегаты L0, обработчик завершения, очистка MDC.
     */
    public void endFrame(Frame frame, long durationNanos, Throwable failure) {
        if (frame == null) {
            return;
        }
        frame.setDurationNanos(durationNanos);
        if (failure != null) {
            frame.setFailed(true);
        }

        Deque<Frame> frames = stack.get();
        if (frames != null && !frames.isEmpty() && frames.peek() == frame) {
            frames.pop();
        }

        counterStore.record(methodKey(frame.getName()), durationNanos);

        if (frame instanceof Operation operation) {
            if (failure != null) {
                operation.setErrorMessage(failure.toString());
            }
            if (frames == null || frames.isEmpty()) {
                stack.remove();
            }
            currentOperation.remove();
            clearMdc(operation);
            completionHandler.onOperationComplete(operation);
        }
    }

    private void clearMdc(Operation operation) {
        for (String key : operation.getMdcKeysAdded()) {
            MDC.remove(key);
        }
    }

    /** Положить ключ в MDC и запомнить его в операции для очистки. */
    public void putContext(String key, String value) {
        Operation operation = currentOperation.get();
        if (operation != null && value != null) {
            MDC.put(key, value);
            operation.addMdcKey(key);
        }
    }

    /** Текущая операция потока (null вне операции). */
    public Operation currentOperation() {
        return currentOperation.get();
    }

    /** Текущий фрейм (для привязки SQL-статистики из перехватчиков). */
    public Frame currentFrame() {
        Deque<Frame> frames = stack.get();
        return frames != null && !frames.isEmpty() ? frames.peek() : null;
    }

    public OperationScope beginOperation(String name) {
        Frame frame = beginFrame(name);
        return new OperationScopeImpl(this, frame);
    }

    static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    static String methodKey(String name) {
        return "method:" + name;
    }

    private static final class OperationScopeImpl implements OperationScope {
        private final OperationContext ctx;
        private final Frame frame;

        private OperationScopeImpl(OperationContext ctx, Frame frame) {
            this.ctx = ctx;
            this.frame = frame;
        }

        @Override
        public void close() {
            if (frame != null) {
                ctx.endFrame(frame, frame.elapsedNanos(), null);
            }
        }

        @Override
        public boolean isActive() {
            return frame instanceof Operation;
        }
    }
}
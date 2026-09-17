package org.ipro.data;

import java.util.Objects;

/**
 * C4.8: canonical write отказан <b>policy</b>, а не прерван ошибкой исполнения.
 *
 * <p>До C4.8 telemetry-обвязка считала отказом любой {@code IllegalStateException}, поэтому
 * deny размывался: сюда попадали и ошибки состояния объекта, а настоящий
 * {@code RlsAccessDeniedException} (наследник {@code AccessDeniedException}) уезжал в
 * ошибки. Отказ теперь выражен отдельным типом, который несёт {@link WriteTelemetry.DenialKind}.</p>
 *
 * <p>Тип наследует {@link IllegalStateException} намеренно: существующие вызывающие,
 * ловившие {@code IllegalStateException} на границе записи, продолжают работать, а
 * различение «отказ policy / ошибка» становится явным там, где оно нужно — в telemetry и
 * в тестах порядка.</p>
 */
public class CanonicalWriteDeniedException extends IllegalStateException {

    private final WriteTelemetry.DenialKind kind;

    public CanonicalWriteDeniedException(WriteTelemetry.DenialKind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
    }

    /** Вид отказа — техническая категория для telemetry, без предметных данных. */
    public WriteTelemetry.DenialKind kind() {
        return kind;
    }
}

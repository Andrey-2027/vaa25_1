package org.ipro.telemetry.core;

import java.util.Map;

import org.ipro.telemetry.api.EventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Персист field-level аудита при завершении операции (корневого фрейма).
 * <p>
 * Вызывается до фиксации бизнес-транзакции (аспект @Order(1) выполняется
 * внутри tx-advice): durable-запись с REQUIRED присоединяется к текущей
 * транзакции — строка аудита коммитится/откатывается атомарно с изменением,
 * журнал всегда соответствует реальному состоянию БД.
 * <p>
 * При неудачной операции (isFailed) накопленные изменения отбрасываются:
 * незакоммиченные изменения в журнал не пишутся.
 */
public final class FieldAuditOperationHandler implements OperationCompletionHandler {

    private static final Logger log = LoggerFactory.getLogger("ipro.telemetry.field-audit");

    private final EventSink sink;

    public FieldAuditOperationHandler(EventSink sink) {
        this.sink = sink;
    }

    @Override
    public void onOperationComplete(Operation operation) {
        if (operation == null || operation.isFailed()) {
            return;
        }
        Map<String, FieldAuditAccumulator> changes = operation.takeFieldAudit();
        if (changes.isEmpty()) {
            return;
        }
        for (FieldAuditAccumulator acc : changes.values()) {
            if (acc.isEmpty()) {
                continue;
            }
            try {
                sink.acceptFieldChangeDurable(acc.toRecord());
            } catch (RuntimeException e) {
                // падение записи аудита не роняет бизнес-операцию
                log.warn("field audit write failed: {}", e.toString());
            }
        }
    }
}

package org.ipro.telemetry.core;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.ipro.telemetry.api.FieldAudit;

/**
 * Статический мост конфигурации field-level аудита: листенеры Hibernate
 * инстанцируются самим Hibernate (без Spring), поэтому whitelist сущностей,
 * redaction-список и сервис чтения выставляются сюда авто-конфигурацией.
 * <p>
 * Механизм включения сущности: {@code @FieldAudit} на классе ИЛИ имя
 * в whitelist {@code ipro.telemetry.field-audit.entities} (основной механизм —
 * whitelist, по умолчанию — сущности, ранее покрытые Envers).
 */
public final class FieldAuditBridge {

    private static volatile Set<String> entities = Set.of();
    private static volatile Set<String> redactFields = Set.of();
    private static volatile FieldAuditQueryService queryService;

    private FieldAuditBridge() {
    }

    /** Выставить whitelist и redaction-список (имена приводятся к нижнему регистру). */
    public static void configure(Set<String> auditedEntities, Set<String> redacted) {
        entities = Set.copyOf(normalize(auditedEntities));
        redactFields = Set.copyOf(normalize(redacted));
    }

    public static void setQueryService(FieldAuditQueryService service) {
        queryService = service;
    }

    /** Сервис чтения журнала изменений для UI (null, пока не настроен). */
    public static FieldAuditQueryService queryService() {
        return queryService;
    }

    static boolean isAudited(Class<?> entityClass, String simpleName) {
        if (entityClass != null && entityClass.isAnnotationPresent(FieldAudit.class)) {
            return true;
        }
        return simpleName != null && entities.contains(simpleName.toLowerCase(Locale.ROOT));
    }

    /** true — значение поля при персисте заменяется на "***". */
    static boolean isRedacted(String fieldName) {
        return fieldName != null && redactFields.contains(fieldName.toLowerCase(Locale.ROOT));
    }

    private static Set<String> normalize(Set<String> values) {
        Set<String> result = new HashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }
}

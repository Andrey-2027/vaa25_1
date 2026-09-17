package org.ipro.metadata;

import java.util.Objects;

/**
 * Диагностика метаданных с классификацией Seriousness (C4.2, ADR-0007 §6).
 *
 * <p>Сообщение адресное: сущность, поле и источник факта названы прямо, чтобы нерабочую
 * конфигурацию можно было исправить без поиска по коду. {@code ERROR} останавливает старт,
 * {@code WARNING} — принятое ограничение или избыточность, {@code INFO} — нейтральное
 * наблюдение (например, объявление, дублирующее вывод).</p>
 *
 * @param severity  классификация
 * @param code      машинный код ({@link MetadataDiagnosticCodes})
 * @param entity    полное имя сущности/строки секции
 * @param field     имя Java-поля
 * @param source    источник факта: аннотация, маппинг или правило вывода
 * @param message   человекочитаемое объяснение
 */
public record MetadataDiagnostic(Severity severity,
                                 String code,
                                 String entity,
                                 String field,
                                 String source,
                                 String message) {

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public MetadataDiagnostic {
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    /** Однострочная форма: пригодна для логов и диагностических тестов. */
    public String render() {
        return severity + " [" + code + "] " + entity + "#" + field
            + " (" + source + "): " + message;
    }
}

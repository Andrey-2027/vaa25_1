package org.ipro.data;

import org.ipro.fetch.plan.FetchScenario;

import java.util.Objects;
import java.util.Set;

/**
 * Явная policy типа поверх выведенной из экспозиции (C4.1, ADR-0007 §2).
 *
 * <p>Экспозиция отвечает на вопрос «что это за тип», эта запись — «что типу разрешено в
 * canonical data path». Нужна там, где правило нельзя вывести из аннотаций:</p>
 * <ul>
 * <li>владелец подсистемы отдаёт тип в canonical path (например, каталог отчётов читается
 * сервисом, который пока наследует {@code AbstractBaseService});</li>
 * <li>typed use case намеренно запрещает generic CRUD ({@code AttributeValue} — только
 * создание, {@code SklNomOpa} — состав ведёт канонизация, а не generic write);</li>
 * <li>тип ограничен runtime-проверкой владельца (ownership у {@code GridFormView}).</li>
 * </ul>
 *
 * <p>Переданные наборы <b>заменяют</b> выведенные, а не дополняют их: политика типа должна
 * читаться одним взглядом, а не вычисляться как дельта. Пустой набор — явный запрет.</p>
 *
 * <p>Значения описывают <b>canonical</b> handle. Тип с `INTERNAL_STORE` и пустыми writes
 * не становится недоступным владельцу: владелец подсистемы по-прежнему обслуживает его
 * своим путём (это и есть правило {@code INTERNAL_STORE}), но generic canonical-запись ему
 * не выдана.</p>
 *
 * @param type   классифицируемый persistence type
 * @param reads  допустимые read-сценарии canonical path
 * @param writes допустимые write intents canonical path
 * @param reason почему тип отклоняется от политики своей экспозиции
 */
public record EntityCapabilityOverride(Class<?> type,
                                       Set<FetchScenario> reads,
                                       Set<DataOperation> writes,
                                       String reason) {

    public EntityCapabilityOverride {
        Objects.requireNonNull(type, "type must not be null");
        reads = reads == null ? Set.of() : Set.copyOf(reads);
        writes = writes == null ? Set.of() : Set.copyOf(writes);
        reason = Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("capability override reason must not be blank");
        }
    }
}

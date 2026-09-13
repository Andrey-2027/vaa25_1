package org.ipro.data;

import org.ipro.fetch.plan.FetchScenario;

import java.util.Objects;
import java.util.Set;

/**
 * Разрешённые операции типа: read-сценарии и write-намерения (C4, ADR-0007 §2).
 *
 * <p>Capability — часть policy типа, а не случайный runtime trap. Owner-сервис может
 * запрещать операцию намеренно (immutable/controlled сущность) — тогда запрет выражен
 * здесь и покрывается отдельным тестом, а не {@code UnsupportedOperationException}
 * глубоко в generic-пути.</p>
 *
 * <p>Наборы описывают <b>canonical</b> handle. Их отсутствие не делает тип недоступным
 * владельцу: подсистема (report/settings/telemetry) по-прежнему обслуживает свой storage
 * собственным путём, но generic canonical-read/write ей не выдан.</p>
 *
 * @param readScenarios допустимые FetchScenario; пусто — автономное чтение запрещено
 * @param writes        допустимые write-намерения; пусто — автономная запись запрещена
 * @param reason        человекочитаемая причина ограничения (для диагностики и тестов)
 */
public record EntityCapabilities(Set<FetchScenario> readScenarios,
                                 Set<DataOperation> writes,
                                 String reason) {

    public EntityCapabilities {
        readScenarios = readScenarios == null ? Set.of() : Set.copyOf(readScenarios);
        writes = writes == null ? Set.of() : Set.copyOf(writes);
        reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public boolean allows(FetchScenario scenario) {
        return scenario != null && readScenarios.contains(scenario);
    }

    public boolean allows(DataOperation operation) {
        return operation != null && writes.contains(operation);
    }
}

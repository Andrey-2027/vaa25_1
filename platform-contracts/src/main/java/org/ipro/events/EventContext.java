package org.ipro.events;

import org.slf4j.MDC;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Контекст прикладной операции, общий для всех событий её жизненного цикла.
 *
 * <p>Контекст не содержит Vaadin-состояния и не привязан к конкретной JPA-сущности.
 * {@code attachedSections} — это именно фактически подключённые секции агрегата;
 * отсутствующий класс строки в этом множестве не должен трактоваться как пустая
 * секция, которую нужно очистить.</p>
 *
 * @param correlationId идентификатор операции; по умолчанию берётся из MDC traceId
 *                     или генерируется локально
 * @param source канал-источник операции
 * @param aggregateType класс корневого агрегата
 * @param aggregateId идентификатор агрегата, может быть null для новой записи
 * @param formVariant вариант формы, если операция пришла из вариантной формы
 * @param attachedSections фактически подключённые секции агрегата
 * @param operationName стабильное имя операции для журналов и диагностики
 */
public record EventContext(
    String correlationId,
    EventSource source,
    Class<?> aggregateType,
    Object aggregateId,
    String formVariant,
    Set<Class<?>> attachedSections,
    String operationName
) {

    private static final String TRACE_ID_MDC_KEY = "traceId";

    public EventContext {
        correlationId = requireText(correlationId, "correlationId");
        source = source == null ? EventSource.SYSTEM : source;
        aggregateType = Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        attachedSections = immutableClasses(attachedSections);
        operationName = requireText(operationName, "operationName");
    }

    /** Создать builder с correlation id текущей telemetry-операции или новым UUID. */
    public static Builder builder(Class<?> aggregateType) {
        return new Builder(aggregateType);
    }

    /** Удобный контекст для обычной entity-операции без табличных частей. */
    public static EventContext forEntity(Class<?> aggregateType,
                                         Object aggregateId,
                                         EventSource source,
                                         String operationName) {
        return builder(aggregateType)
            .aggregateId(aggregateId)
            .source(source)
            .operationName(operationName)
            .build();
    }

    /** Проверить, считается ли секция фактически подключённой к операции. */
    public boolean isSectionAttached(Class<?> rowClass) {
        return rowClass != null && attachedSections.contains(rowClass);
    }

    /**
     * Вернуть тот же контекст с идентификатором, присвоенным persistence-слоем.
     * Для новой сущности before-событие закономерно имеет {@code aggregateId == null},
     * а Saved/Changed могут уже содержать сгенерированный id.
     */
    public EventContext withAggregateId(Object persistedId) {
        return new EventContext(correlationId, source, aggregateType, persistedId,
            formVariant, attachedSections, operationName);
    }

    /** Тот же контекст с другим именем фазы операции (например, save:/delete:). */
    public EventContext withOperationName(String name) {
        return new EventContext(correlationId, source, aggregateType, aggregateId,
            formVariant, attachedSections, requireText(name, "operationName"));
    }

    /**
     * Получить correlation id, установленный существующим telemetry-контуром.
     * Если активной операции нет, вернуть новый id — события вне telemetry
     * всё равно остаются трассируемыми.
     */
    public static String currentOrNewCorrelationId() {
        String current = MDC.get(TRACE_ID_MDC_KEY);
        return current == null || current.isBlank() ? UUID.randomUUID().toString() : current;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Set<Class<?>> immutableClasses(Collection<Class<?>> classes) {
        LinkedHashSet<Class<?>> copy = new LinkedHashSet<>();
        if (classes != null) {
            for (Class<?> type : classes) {
                copy.add(Objects.requireNonNull(type, "attached section class must not be null"));
            }
        }
        return Collections.unmodifiableSet(copy);
    }

    /** Fluent builder для контекстов формы/use case/service. */
    public static final class Builder {
        private final Class<?> aggregateType;
        private String correlationId;
        private EventSource source = EventSource.SYSTEM;
        private Object aggregateId;
        private String formVariant;
        private final Set<Class<?>> attachedSections = new LinkedHashSet<>();
        private String operationName;

        private Builder(Class<?> aggregateType) {
            this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType must not be null");
            this.operationName = "event:" + aggregateType.getSimpleName();
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder source(EventSource source) {
            this.source = source;
            return this;
        }

        public Builder aggregateId(Object aggregateId) {
            this.aggregateId = aggregateId;
            return this;
        }

        public Builder formVariant(String formVariant) {
            this.formVariant = formVariant;
            return this;
        }

        public Builder attachedSections(Collection<Class<?>> sections) {
            this.attachedSections.clear();
            if (sections != null) {
                for (Class<?> section : sections) {
                    attachSection(section);
                }
            }
            return this;
        }

        public Builder attachSection(Class<?> rowClass) {
            this.attachedSections.add(
                Objects.requireNonNull(rowClass, "attached section class must not be null"));
            return this;
        }

        public Builder operationName(String operationName) {
            this.operationName = operationName;
            return this;
        }

        public EventContext build() {
            String id = correlationId == null ? currentOrNewCorrelationId() : correlationId;
            return new EventContext(id, source, aggregateType, aggregateId, formVariant,
                attachedSections, operationName);
        }
    }
}

package org.ipro.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

/**
 * Единая точка публикации lifecycle-событий сущностей и агрегатов.
 *
 * <p>Before-события публикуются синхронно: исключение listener'а сразу
 * возвращается вызывающему коду и может отменить сохранение/удаление.</p>
 *
 * <p>Saved-событие означает успешный вызов persistence-операции внутри текущей
 * транзакции. Changed/Deleted доставляются только после успешного commit. Вызов
 * этих методов вне активной транзакции является ошибкой: publisher не подменяет
 * отсутствующий commit немедленной доставкой события.</p>
 *
 * <p>Aggregate scope нужен metadata-driven service или custom use case: сервис шапки получает тот же
 * {@link EventContext}, но не планирует второе {@link EntityChangedEvent}; committed
 * событие агрегата планирует aggregate coordinator после сохранения всех attached-секций.</p>
 */
public final class EntityEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EntityEventPublisher.class);

    private final ApplicationEventPublisher applicationEventPublisher;
    private final ThreadLocal<OperationFrame> operationContext = new ThreadLocal<>();

    public EntityEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = Objects.requireNonNull(
            applicationEventPublisher, "applicationEventPublisher must not be null");
    }

    /** Открыть обычный standalone entity scope. */
    public EventScope openOperation(EventContext context) {
        return open(context, false);
    }

    /** Открыть scope aggregate coordinator. */
    public EventScope openAggregateOperation(EventContext context) {
        return open(context, true);
    }

    private EventScope open(EventContext context, boolean aggregate) {
        Objects.requireNonNull(context, "context must not be null");
        OperationFrame previous = operationContext.get();
        operationContext.set(new OperationFrame(context, aggregate));
        return new EventScope(this, previous);
    }

    /**
     * Вернуть контекст активной операции либо построить обычный entity-контекст.
     * Если тип совпадает с корнем активного агрегата, все lifecycle-события получают
     * один correlation id/source/variant, но с operationName вызывающей фазы
     * (save:/delete:), а не имени фрейма.
     */
    public EventContext contextFor(Class<?> entityType,
                                   Object entityId,
                                   EventSource defaultSource,
                                   String operationName) {
        OperationFrame active = operationContext.get();
        if (active != null && active.context().aggregateType().equals(entityType)) {
            EventContext base = active.context();
            if (entityId != null) {
                base = base.withAggregateId(entityId);
            }
            return base.withOperationName(operationName);
        }
        return EventContext.forEntity(entityType, entityId, defaultSource, operationName);
    }

    /** Проверить, что завершение корневой сущности поручено aggregate coordinator. */
    public boolean isAggregateOperation(Class<?> aggregateType) {
        OperationFrame active = operationContext.get();
        return active != null
            && active.aggregate()
            && active.context().aggregateType().equals(aggregateType);
    }

    /** Публикация veto-capable before-save события сущности. */
    public <T> void publishSaving(T entity, EventContext context) {
        publish(new EntitySavingEvent<>(entity, context));
    }

    /** Публикация veto-capable before-save события агрегата с attached-секциями. */
    public <T> void publishAggregateSaving(T aggregate,
                                            List<AggregateSection> sections,
                                            EventContext context) {
        publish(new AggregateSavingEvent<>(aggregate, sections, context));
    }

    /** Факт успешного вызова persistence-операции, внутри текущей транзакции. */
    public <T> void publishSaved(T entity, EventContext context) {
        publish(new EntitySavedEvent<>(entity, context));
    }

    /** Факт изменения entity, доставляемый только после успешного commit. */
    public <T> void publishChanged(T entity, EventContext context) {
        publishAfterCommit(new EntityChangedEvent<>(entity, context));
    }

    /** Публикация veto-capable before-delete события сущности. */
    public <T> void publishDeleting(T entity, EventContext context) {
        publish(new EntityDeletingEvent<>(entity, context));
    }

    /** Факт удаления entity, доставляемый только после успешного commit. */
    public <T> void publishDeleted(T entity, EventContext context) {
        publishAfterCommit(new EntityDeletedEvent<>(entity, context));
    }

    private void publish(Object event) {
        // ApplicationEventPublisher по умолчанию вызывает listeners синхронно;
        // это обязательная семантика veto-событий.
        applicationEventPublisher.publishEvent(event);
    }

    private void publishAfterCommit(Object event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
            || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "After-commit event " + event.getClass().getSimpleName()
                    + " requires an active transaction");
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishAfterCommitSafely(event);
            }
        });
    }

    private void publishAfterCommitSafely(Object event) {
        try {
            publish(event);
        } catch (RuntimeException exception) {
            // Транзакция уже зафиксирована: ошибка реактивного обработчика
            // не может отменить сохранённые данные.
            log.warn("Entity after-commit event delivery failed for {}: {}",
                event.getClass().getSimpleName(), exception.toString());
        }
    }

    private void restore(OperationFrame previous) {
        if (previous == null) {
            operationContext.remove();
        } else {
            operationContext.set(previous);
        }
    }

    private record OperationFrame(EventContext context, boolean aggregate) {
    }

    /** Закрываемый scope контекста прикладной операции. */
    public static final class EventScope implements AutoCloseable {
        private final EntityEventPublisher owner;
        private final OperationFrame previous;
        private boolean closed;

        private EventScope(EntityEventPublisher owner, OperationFrame previous) {
            this.owner = owner;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                owner.restore(previous);
            }
        }
    }
}

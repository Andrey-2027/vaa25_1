package org.ipro.lifecycle;

import org.ipro.identity.IdentifiableEntity;
import org.ipro.events.AggregateSection;
import org.ipro.events.EntityChangedEvent;
import org.ipro.events.EventContext;
import org.springframework.context.event.EventListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Fail-fast registry прикладных lifecycle handlers.
 *
 * <p>В отличие от decoupled event subscribers здесь разрешается максимум один
 * authoritative handler на entity type. Это делает место и порядок предметных
 * callbacks (включая in-transaction {@code onSave}) обозримыми, не запрещая
 * множественные listeners для интеграций.</p>
 */
public final class EntityLifecycleRegistry {

    private final Map<Class<?>, EntityLifecycle<?>> handlers;

    public EntityLifecycleRegistry(List<EntityLifecycle<?>> discovered) {
        Map<Class<?>, EntityLifecycle<?>> index = new LinkedHashMap<>();
        for (EntityLifecycle<?> handler : Objects.requireNonNull(discovered, "discovered must not be null")) {
            Objects.requireNonNull(handler, "discovered lifecycle handler must not be null");
            Class<?> entityType = Objects.requireNonNull(
                handler.entityType(), "lifecycle entityType must not be null");
            EntityLifecycle<?> previous = index.putIfAbsent(entityType, handler);
            if (previous != null) {
                throw new IllegalStateException(
                    "More than one EntityLifecycle is registered for " + entityType.getName()
                        + ": " + previous.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
        this.handlers = Map.copyOf(index);
    }

    public <T extends IdentifiableEntity>
    Optional<EntityLifecycle<T>> find(Class<T> entityType) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        return Optional.ofNullable(lookup(entityType));
    }

    public <T extends IdentifiableEntity> void beforeSave(
            Class<T> entityType, T entity, EventContext context) {
        find(entityType).ifPresent(handler -> handler.beforeSave(
            new EntitySaveContext<>(entity, context)));
    }

    public <T extends IdentifiableEntity> void beforeUpdate(
            Class<T> entityType, T original, T updated, EventContext context) {
        find(entityType).ifPresent(handler -> handler.beforeUpdate(
            new EntityUpdateContext<>(original, updated, context)));
    }

    public <T extends IdentifiableEntity> void beforeAggregateSave(
            Class<T> entityType, T aggregate, List<AggregateSection> sections,
            EventContext context) {
        find(entityType).ifPresent(handler -> handler.beforeAggregateSave(
            new AggregateSaveContext<>(aggregate, sections, context)));
    }

    public <T extends IdentifiableEntity> void beforeDelete(
            Class<T> entityType, T entity, EventContext context) {
        find(entityType).ifPresent(handler -> handler.beforeDelete(
            new EntityDeleteContext<>(entity, context)));
    }

    public <T extends IdentifiableEntity> void onSave(
            Class<T> entityType, T entity, EventContext context) {
        find(entityType).ifPresent(handler -> handler.onSave(
            new EntitySaveContext<>(entity, context)));
    }

    /** EntityEventPublisher доставляет EntityChangedEvent только после commit. */
    @EventListener
    public void onEntityChanged(EntityChangedEvent<?> event) {
        if (!(event.entity() instanceof IdentifiableEntity)) {
            return;
        }
        Class<?> entityType = event.context().aggregateType();
        dispatchAfterCommit(entityType, event.entity(), event.context());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatchAfterCommit(
            Class<?> entityType, Object entity, EventContext context) {
        EntityLifecycle handler = handlers.get(entityType);
        if (handler != null) {
            handler.afterCommit(new EntityChangedContext((IdentifiableEntity) entity, context));
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends IdentifiableEntity> EntityLifecycle<T> lookup(Class<T> entityType) {
        return (EntityLifecycle<T>) handlers.get(entityType);
    }
}

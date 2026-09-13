package org.ipro.data;

import org.ipro.crud.BaseService;
import org.ipro.crud.IdentifiableEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Type-directed resolver для инфраструктуры форм (C4, ADR-0007 §1, план C4.3 п.5).
 *
 * <p>По явному entity type выбирается ровно один способ доступа:</p>
 * <ol>
 * <li>зарегистрированная {@link EntityDataPolicy} — explicit typed custom path;</li>
 * <li>иначе canonical generic path, если descriptor типа его допускает
 * ({@code STANDARD_ROOT} либо {@code INTERNAL_STORE} с явным read-мостом владельца);</li>
 * <li>иначе — отказ с реальной причиной: owned row, internal store без моста, тип вне
 * каталога. Отказ приходит здесь, до RLS, SQL и пользовательского кода.</li>
 * </ol>
 *
 * <p>Duplicate custom registration — startup error, а не «кто позже зарегистрировался».
 * Custom policy не может молча затенить standard type: причина обязательна.</p>
 */
public class EntityDataAccessResolver {

    private final EntityDescriptorCatalog catalog;
    private final EntityDataAccess canonical;
    private final CanonicalReadExecutor readExecutor;
    private final Map<Class<?>, EntityDataPolicy> policies;

    public EntityDataAccessResolver(EntityDescriptorCatalog catalog,
                                    EntityDataAccess canonical,
                                    CanonicalReadExecutor readExecutor,
                                    List<EntityDataPolicy> policies) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.canonical = Objects.requireNonNull(canonical, "canonical must not be null");
        this.readExecutor = Objects.requireNonNull(readExecutor, "readExecutor must not be null");

        Map<Class<?>, EntityDataPolicy> registered = new LinkedHashMap<>();
        for (EntityDataPolicy policy : policies == null ? List.<EntityDataPolicy>of() : policies) {
            policy.validate();
            Class<?> type = policy.entityType();
            EntityDataPolicy previous = registered.putIfAbsent(type, policy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate EntityDataPolicy for " + type.getName()
                    + ": registered twice, выбор должен быть детерминированным по типу");
            }
            if (!type.isAnnotationPresent(jakarta.persistence.Entity.class)) {
                throw new IllegalStateException("EntityDataPolicy for " + type.getName()
                    + " is not a JPA entity: policy describes a persistence type");
            }
        }
        this.policies = Map.copyOf(registered);
    }

    /** Descriptor типа — read-only таксономия и capabilities. */
    public EntityDescriptor descriptor(Class<?> type) {
        return catalog.descriptorOf(type);
    }

    /** Почему тип обслуживается данным путём (для диагностики). */
    public String resolutionReason(Class<?> type) {
        EntityDataPolicy policy = policies.get(type);
        if (policy != null) {
            return "custom EntityDataPolicy: " + policy.reason();
        }
        return "canonical generic path: " + catalog.descriptorOf(type).reason();
    }

    /**
     * Resolve с отказом для недопустимой экспозиции. Допустимость <b>конкретного</b>
     * write intent дополнительно проверяет executor: write без capability отклоняется
     * на вызове, а не на resolve (read-only тип остаётся читаемым).
     */
    public EntityDataAccess resolve(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        return find(type).orElseThrow(() -> rejected(type));
    }

    /** Non-throwing вариант: canonical handle доступен только для разрешённой экспозиции. */
    public Optional<EntityDataAccess> find(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        EntityDataPolicy policy = policies.get(type);
        if (policy != null) {
            return Optional.of(policy.dataAccess());
        }
        EntityDescriptor descriptor = catalog.descriptorOf(type);
        if (descriptor.exposure() == EntityExposure.STANDARD_ROOT) {
            return Optional.of(canonical);
        }
        if (descriptor.exposure() == EntityExposure.INTERNAL_STORE
                && !descriptor.capabilities().readScenarios().isEmpty()) {
            // Владелец подсистемы явно отдал типу canonical чтение с названной причиной.
            return Optional.of(canonical);
        }
        return Optional.empty();
    }

    /**
     * Default data handle/service для form infrastructure. Возвращает canonical service
     * только там, где canonical handle разрешён: у типа со своим API остаётся его
     * собственный сервис (это решает {@code ServiceLocator}, а не resolver).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> Optional<BaseService<T, ID>> findService(Class<T> type) {
        return find(type).map(access -> (BaseService<T, ID>) new CanonicalEntityService(type,
            readExecutor, access));
    }

    private IllegalStateException rejected(Class<?> type) {
        EntityDescriptor descriptor = catalog.descriptorOf(type);
        if (descriptor.exposure() == EntityExposure.OWNED_ROW) {
            return new IllegalStateException(type.getSimpleName()
                + " — строка owned-секции и не имеет автономного canonical handle."
                + " Секция доступна через aggregate boundary владельца ("
                + descriptor.reason() + ").");
        }
        return new IllegalStateException(type.getSimpleName()
            + " не имеет canonical data handle: " + descriptor.exposure() + " («"
            + descriptor.reason() + "», policy: " + descriptor.capabilities().reason() + ").");
    }
}

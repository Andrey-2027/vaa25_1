package org.ipro.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.metamodel.EntityType;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.CatalogOwnerDescriptor;
import org.ipro.metadata.CatalogOwnerResolution;
import org.ipro.metadata.CatalogOwnerResolver;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;

/**
 * Обязательность и неизменяемость владельца справочника.
 *
 * <p>Guard — обязательная часть платформы, а не необязательный Spring-бин: его создают
 * canonical write и aggregate save из собственных зависимостей, поэтому отсутствие
 * пользовательского бина не может отключить проверку. {@link CanonicalWritePropertyPolicy}
 * сохраняет своё назначение (право на именованную операцию) и здесь не используется:
 * нарушение owner-контракта не разрешается никаким правом.</p>
 *
 * <p><b>Доверенное предсостояние.</b> Для update исходный owner-id читается запросом с
 * {@link FlushModeType#COMMIT}: JPA не выполняет auto-flush перед таким запросом, поэтому
 * значение приходит из сохранённой строки, а не из уже изменённого managed-экземпляра в том
 * же persistence context. Сравниваются только id; другой экземпляр или proxy с тем же id —
 * та же запись.</p>
 *
 * <p><b>Две фазы.</b> {@link #preflight(Class, Object, boolean)} выполняется до нумерации,
 * валидации, хуков, событий и SQL; {@link #recheck(CatalogOwnerToken, Object)} — после
 * изменяющих состояние callbacks/hooks, непосредственно перед записью. Вторая фаза сравнивает
 * значение с уже проверенным id, поэтому позднее изменение владельца не проходит.</p>
 */
public final class CatalogOwnerGuard {

    private final EntityManager entityManager;
    private final CatalogOwnerResolver resolver = new CatalogOwnerResolver();

    /**
     * @param entityManager persistence context для чтения сохранённого владельца; {@code null}
     *                      допустим только для create-only контуров и даёт явный отказ на
     *                      update owner-enabled типа
     */
    public CatalogOwnerGuard(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Preflight записи: владелец обязателен, совпадает с явным контекстом команды и не
     * меняется относительно сохранённого состояния.
     */
    public CatalogOwnerToken preflight(Class<?> type, Object target, boolean creating) {
        CatalogOwnerDescriptor owner = descriptor(type);
        if (owner == null) {
            return CatalogOwnerToken.none();
        }
        Long ownerId = checkedOwnerId(type, owner, target);
        requireContextMatches(type, owner, ownerId);

        if (!creating) {
            Object id = identityOf(target);
            if (id == null) {
                throw new CatalogOwnerViolationException(
                    CatalogOwnerViolationException.Reason.STATE_UNREADABLE,
                    type.getSimpleName() + ": update без id не имеет сохранённого владельца");
            }
            PersistedOwner persisted = persistedOwner(type, owner, id);
            if (persisted.rowPresent()) {
                if (persisted.ownerId() == null) {
                    throw new CatalogOwnerViolationException(
                        CatalogOwnerViolationException.Reason.OWNER_IMMUTABLE,
                        type.getSimpleName() + "." + owner.property()
                            + ": сохранённая запись не имеет владельца, назначение владельца"
                            + " после сохранения запрещено");
                }
                if (!persisted.ownerId().equals(ownerId)) {
                    throw new CatalogOwnerViolationException(
                        CatalogOwnerViolationException.Reason.OWNER_IMMUTABLE,
                        type.getSimpleName() + "." + owner.property()
                            + ": перенос сохранённой записи к другому владельцу запрещён"
                            + " (сохранён owner id=" + persisted.ownerId()
                            + ", записывается id=" + ownerId + ")");
                }
            }
        }
        return new CatalogOwnerToken(owner, ownerId);
    }

    /**
     * Финальная проверка перед записью: значение обязано остаться тем, которое проверил
     * preflight. Ловит изменение owner через named mutation, lifecycle hook или callback.
     */
    public void recheck(CatalogOwnerToken token, Object target) {
        Objects.requireNonNull(token, "token must not be null");
        if (!token.declared()) {
            return;
        }
        CatalogOwnerDescriptor owner = token.owner();
        Class<?> type = entityType(target);
        Long ownerId = checkedOwnerId(type, owner, target);
        requireContextMatches(type, owner, ownerId);
        if (!ownerId.equals(token.ownerId())) {
            throw new CatalogOwnerViolationException(
                CatalogOwnerViolationException.Reason.OWNER_IMMUTABLE,
                type.getSimpleName() + "." + owner.property()
                    + ": владелец изменён после preflight (проверен id=" + token.ownerId()
                    + ", записывается id=" + ownerId + ")");
        }
    }

    /** Resolved capability или {@code null}; невалидное объявление — явный отказ, не тишина. */
    private CatalogOwnerDescriptor descriptor(Class<?> type) {
        CatalogOwnerResolution resolution = resolver.resolve(type);
        if (resolution.declared() && !resolution.valid()) {
            String details = resolution.diagnostics().stream()
                .map(diagnostic -> diagnostic.code() + " " + diagnostic.message())
                .reduce((left, right) -> left + "; " + right)
                .orElse("owner capability is invalid");
            throw new IllegalStateException("Owner-capability " + type.getName()
                + " не разрешена до старта: " + details);
        }
        return resolution.owner();
    }

    private static Long checkedOwnerId(Class<?> type, CatalogOwnerDescriptor owner, Object target) {
        Object value = fieldValue(target, owner.property());
        if (value == null) {
            throw new CatalogOwnerViolationException(
                CatalogOwnerViolationException.Reason.OWNER_REQUIRED,
                type.getSimpleName() + "." + owner.property()
                    + ": владелец обязателен для записи");
        }
        if (!(value instanceof IdentifiableEntity<?> identifiable)) {
            throw new CatalogOwnerViolationException(
                CatalogOwnerViolationException.Reason.STATE_UNREADABLE,
                type.getSimpleName() + "." + owner.property()
                    + ": владелец не является IdentifiableEntity");
        }
        Object id = identifiable.getId();
        if (!(id instanceof Long ownerId)) {
            throw new CatalogOwnerViolationException(
                CatalogOwnerViolationException.Reason.OWNER_REQUIRED,
                type.getSimpleName() + "." + owner.property()
                    + ": владелец обязан быть сохранённой записью с Long-id");
        }
        return ownerId;
    }

    private static void requireContextMatches(Class<?> type, CatalogOwnerDescriptor owner,
                                              Long ownerId) {
        CatalogOwnerContext.current().ifPresent(context -> {
            if (!context.ownerTypeId().equals(owner.targetTypeId())) {
                throw new CatalogOwnerViolationException(
                    CatalogOwnerViolationException.Reason.CONTEXT_MISMATCH,
                    type.getSimpleName() + "." + owner.property()
                        + ": контекст команды объявляет владельца типа " + context.ownerTypeId()
                        + ", association ведёт на " + owner.targetTypeId());
            }
            if (!context.ownerId().equals(ownerId)) {
                throw new CatalogOwnerViolationException(
                    CatalogOwnerViolationException.Reason.CONTEXT_MISMATCH,
                    type.getSimpleName() + "." + owner.property()
                        + ": context-owner id=" + context.ownerId()
                        + " не совпадает с association id=" + ownerId);
            }
        });
    }

    private PersistedOwner persistedOwner(Class<?> type, CatalogOwnerDescriptor owner, Object id) {
        if (entityManager == null) {
            throw new CatalogOwnerViolationException(
                CatalogOwnerViolationException.Reason.STATE_UNREADABLE,
                type.getSimpleName() + "." + owner.property()
                    + ": owner-guard требует persistence context для проверки сохранённого"
                    + " владельца");
        }
        String entityName;
        try {
            EntityType<?> entity = entityManager.getMetamodel().entity(type);
            entityName = entity.getName();
        } catch (IllegalArgumentException notManaged) {
            throw new CatalogOwnerViolationException(
                CatalogOwnerViolationException.Reason.STATE_UNREADABLE,
                type.getSimpleName() + ": тип не управляется persistence unit",
                notManaged);
        }
        String jpql = "select e." + owner.property() + ".id from " + entityName
            + " e where e.id = :id";
        List<?> rows;
        try {
            // COMMIT: запрос не вызывает auto-flush изменённого managed-состояния, поэтому
            // читается сохранённый owner-id, а не мутация из этого же persistence context.
            rows = entityManager.createQuery(jpql, Object.class)
                .setFlushMode(FlushModeType.COMMIT)
                .setParameter("id", id)
                .getResultList();
        } catch (RuntimeException failure) {
            throw new CatalogOwnerViolationException(
                CatalogOwnerViolationException.Reason.STATE_UNREADABLE,
                type.getSimpleName() + "." + owner.property()
                    + ": сохранённый владелец недоступен",
                failure);
        }
        if (rows.isEmpty()) {
            return new PersistedOwner(false, null);
        }
        Object value = rows.getFirst();
        if (value == null) {
            return new PersistedOwner(true, null);
        }
        if (!(value instanceof Long ownerId)) {
            throw new CatalogOwnerViolationException(
                CatalogOwnerViolationException.Reason.STATE_UNREADABLE,
                type.getSimpleName() + "." + owner.property()
                    + ": сохранённый owner-id не является Long");
        }
        return new PersistedOwner(true, ownerId);
    }

    /** Реальный persistence class без инициализации Hibernate proxy. */
    private static Class<?> entityType(Object target) {
        if (target instanceof org.hibernate.proxy.HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getPersistentClass();
        }
        return target.getClass();
    }

    private static Object identityOf(Object target) {
        return target instanceof IdentifiableEntity<?> identifiable ? identifiable.getId() : null;
    }

    private static Object fieldValue(Object target, String property) {
        Class<?> type = target.getClass();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(property);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Ищем выше по иерархии.
            } catch (IllegalAccessException denied) {
                throw new CatalogOwnerViolationException(
                    CatalogOwnerViolationException.Reason.STATE_UNREADABLE,
                    type.getSimpleName() + "." + property + ": поле владельца недоступно",
                    denied);
            }
        }
        throw new CatalogOwnerViolationException(
            CatalogOwnerViolationException.Reason.STATE_UNREADABLE,
            type.getSimpleName() + "." + property + ": поле владельца не найдено");
    }

    private record PersistedOwner(boolean rowPresent, Long ownerId) {
    }
}

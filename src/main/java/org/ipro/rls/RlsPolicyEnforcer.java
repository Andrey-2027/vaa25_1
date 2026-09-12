package org.ipro.rls;

import jakarta.persistence.EntityManager;
import org.hibernate.proxy.HibernateProxy;
import org.ipro.telemetry.core.SecurityEventLogger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Обязательная server-side граница RLS для чтения и изменения сущностей.
 *
 * <p>Для protected entity неизвестный субъект, неполный descriptor значений либо
 * невозможность вычислить policy всегда означают отказ. Обычный пользователь с
 * корректным контекстом, но без row grants, получает mandatory predicate с sentinel.</p>
 */
public class RlsPolicyEnforcer {

    private final RlsDimensionRegistry registry;
    private final RlsReadGate readGate;
    private final RlsFilterActivator filterActivator;
    private final AccessService accessService;
    private final RlsCurrentUser currentUser;
    private final Optional<SecurityEventLogger> securityEventLogger;

    public RlsPolicyEnforcer(RlsDimensionRegistry registry,
                             RlsReadGate readGate,
                             RlsFilterActivator filterActivator,
                             AccessService accessService,
                             RlsCurrentUser currentUser) {
        this(registry, readGate, filterActivator, accessService, currentUser, Optional.empty());
    }

    public RlsPolicyEnforcer(RlsDimensionRegistry registry,
                             RlsReadGate readGate,
                             RlsFilterActivator filterActivator,
                             AccessService accessService,
                             RlsCurrentUser currentUser,
                             Optional<SecurityEventLogger> securityEventLogger) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.readGate = Objects.requireNonNull(readGate, "readGate");
        this.filterActivator = Objects.requireNonNull(filterActivator, "filterActivator");
        this.accessService = Objects.requireNonNull(accessService, "accessService");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        this.securityEventLogger = Objects.requireNonNull(securityEventLogger,
            "securityEventLogger");
    }

    public boolean isProtected(Class<?> entityClass) {
        return registry.policyOf(entityClass).protectedEntity();
    }

    /** Проверяет CHECK_ONLY и устанавливает mandatory FILTERABLE predicates до query. */
    public boolean prepareRead(Class<?> entityClass, EntityManager entityManager) {
        RlsPolicyDescriptor policy = registry.policyOf(entityClass);
        if (!policy.protectedEntity() || RlsContext.isBypassed()) {
            return true;
        }
        String username = currentUser.requireAuthenticatedUsername();
        if (!readGate.canRead(policy.entityClass(), username)) {
            return false;
        }
        filterActivator.ensureRlsEnabled(entityManager);
        return true;
    }

    public void requireReadable(Class<?> entityClass, EntityManager entityManager) {
        if (!prepareRead(entityClass, entityManager)) {
            throw new RlsAccessDeniedException("Нет права чтения " + entityType(entityClass).getSimpleName());
        }
    }

    public void requireUpdate(Object entity) {
        Map<String, List<RlsCheckValue>> checks = requirePermission(entity, false);
        if (isProtected(entityType(entity)) && !RlsContext.isBypassed()) {
            RlsWriteAuthorization.grant(entity, RlsWriteAuthorization.Operation.WRITE, checks);
        }
    }

    public void requireDelete(Object entity) {
        Map<String, List<RlsCheckValue>> checks = requirePermission(entity, true);
        if (isProtected(entityType(entity)) && !RlsContext.isBypassed()) {
            RlsWriteAuthorization.grant(entity, RlsWriteAuthorization.Operation.DELETE, checks);
        }
    }

    private Map<String, List<RlsCheckValue>> requirePermission(Object entity, boolean delete) {
        Objects.requireNonNull(entity, "entity");
        Class<?> entityClass = entityType(entity);
        RlsPolicyDescriptor policy = registry.policyOf(entityClass);
        if (!policy.protectedEntity() || RlsContext.isBypassed()) {
            return Map.of();
        }
        String username = currentUser.requireAuthenticatedUsername();
        Map<String, List<RlsCheckValue>> checks = policy.checksOf(entity);
        for (String dimension : policy.dimensions().keySet()) {
            List<RlsCheckValue> values = checks.get(dimension);
            if (values == null || values.isEmpty()) {
                throw new IllegalStateException("RLS dimension " + dimension + " of "
                    + entityClass.getName() + " has no check values");
            }
            for (RlsCheckValue value : values) {
                if (value == null) {
                    throw new IllegalStateException("Null RLS check in " + entityClass.getName()
                        + " for dimension " + dimension);
                }
                if (value instanceof RlsCheckValue.NotApplicable) {
                    continue;
                }
                Long id = ((RlsCheckValue.Check) value).id();
                boolean allowed = delete
                    ? accessService.canDelete(dimension, id, username)
                    : accessService.canUpdate(dimension, id, username);
                if (!allowed) {
                    emitRlsDenied(username, entityClass, delete ? "удаление" : "изменение",
                        dimension, id);
                    throw new RlsAccessDeniedException("Нет прав на "
                        + (delete ? "удаление" : "изменение") + " (измерение "
                        + dimension + (id == null ? ", создание новой записи" : ", id=" + id) + ")");
                }
            }
        }
        return checks;
    }

    private void emitRlsDenied(String username, Class<?> entityClass, String action,
                               String dimension, Long dimensionValueId) {
        securityEventLogger.ifPresent(logger -> logger.emitSecurityEvent(
            "WARN",
            "rls:denied",
            username,
            "Нет прав на " + action + " (измерение " + dimension
                + (dimensionValueId == null
                    ? ", создание новой записи" : ", id=" + dimensionValueId) + ")",
            Map.of(
                "action", action,
                "dimension", dimension,
                "dimensionValueId", dimensionValueId == null ? "" : String.valueOf(dimensionValueId),
                "entity", entityClass.getName())));
    }

    private static Class<?> entityType(Object entity) {
        if (entity instanceof HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getPersistentClass();
        }
        return entity.getClass();
    }

    private static Class<?> entityType(Class<?> entityClass) {
        return entityClass;
    }
}

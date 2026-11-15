package org.ipro.query;

import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.CacheStoreMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.jpa.QueryHints;
import org.ipro.filtergrid.projection.ProjectionDefinition;
import org.ipro.filtergrid.projection.ProjectionQueryPolicy;
import org.ipro.query.model.QueryLimits;
import org.ipro.rls.AccessGrantVersion;
import org.ipro.rls.RlsContext;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsFilterActivator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

final class ReportProjectionQueryPolicy implements ProjectionQueryPolicy {
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final ReportQueryGuard guard;
    private final RlsCurrentUser currentUser;
    private final RlsFilterActivator rls;
    private final QueryLimits limits;
    private final int rowLimit;
    private final ReportRunQuota quota;
    private final int inMemoryLimit;

    ReportProjectionQueryPolicy(EntityManager entityManager, PlatformTransactionManager transactions,
                                ReportQueryGuard guard, RlsCurrentUser currentUser,
                                RlsFilterActivator rls, QueryLimits limits, int rowLimit,
                                ReportRunQuota quota, int inMemoryLimit) {
        this.entityManager = entityManager;
        this.guard = guard;
        this.currentUser = currentUser;
        this.rls = rls;
        this.limits = limits;
        this.rowLimit = rowLimit;
        this.quota = quota;
        this.inMemoryLimit = inMemoryLimit;
        transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <Q extends Query, T> T execute(EntityManager em, ProjectionDefinition definition, String hql,
                                         Supplier<Q> prepare, Function<Q, T> read) {
        if (em != entityManager || RlsContext.isBypassed()) {
            throw new IllegalStateException("Для чтения проекции требуется защищённая сессия");
        }
        return transaction.execute(status -> {
            var revision = accessRevision();
            if (!em.isJoinedToTransaction()) {
                throw new IllegalStateException("Сессия проекции не присоединена к транзакции чтения");
            }
            Q query = prepare.get();
            Map<String, Class<?>> entityParameters = new HashMap<>();
            var metamodel = em.getMetamodel();
            var names = new java.util.HashSet<String>();
            for (var parameter : query.getParameters()) {
                names.add(parameter.getName());
                var value = query.getParameterValue(parameter);
                for (var entity : metamodel.getEntities()) {
                    if (entity.getJavaType().isInstance(value) || entity.getJavaType() == parameter.getParameterType()) {
                        entityParameters.put(parameter.getName(), entity.getJavaType());
                        break;
                    }
                }
            }
            var result = guard.guardProjection(hql, names, entityParameters);
            if (!result.allowed()) {
                throw new QueryReadRefusedException(QueryReadRefusedException.Reason.ACCESS_DENIED,
                        "Отказ: " + String.join("; ", result.errors()));
            }
            rls.ensureRlsEnabled(em);
            if (em.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class)
                    .getJdbcServices().getDialect() instanceof PostgreSQLDialect) {
                em.createNativeQuery("set local statement_timeout = " + limits.timeoutMs()).executeUpdate();
            }
            query.setHint(QueryHints.HINT_READONLY, true);
            query.setHint(QueryHints.HINT_CACHEABLE, false);
            query.setHint("jakarta.persistence.cache.retrieveMode", CacheRetrieveMode.BYPASS);
            query.setHint("jakarta.persistence.cache.storeMode", CacheStoreMode.BYPASS);
            query.setHint(QueryHints.HINT_FETCH_SIZE, Math.min(rowLimit, 1000));
            query.setHint("jakarta.persistence.query.timeout", limits.timeoutMs());
            query.setMaxResults(Math.min(query.getMaxResults(), rowLimit));
            T rows = read.apply(query);
            if (inMemoryLimit > 0 && rows instanceof java.util.List<?> list && list.size() > inMemoryLimit) {
                throw new QueryReadRefusedException(QueryReadRefusedException.Reason.ROW_LIMIT,
                        "Результат больше " + inMemoryLimit + " строк. Сузьте запрос или выберите LAZY либо HYBRID.");
            }
            if (!revision.equals(accessRevision())) {
                throw new QueryReadRefusedException(QueryReadRefusedException.Reason.ACCESS_CHANGED,
                        "Права изменились во время чтения. Повторите запрос.");
            }
            return rows;
        });
    }

    @Override
    public <T> T materialize(Supplier<T> load) {
        var slot = quota.tryAcquire(0);
        if (slot == null) {
            throw new QueryReadRefusedException(QueryReadRefusedException.Reason.QUOTA_BUSY, quota.busyMessage());
        }
        try {
            return load.get();
        } finally {
            slot.release();
        }
    }

    @Override
    public Object accessRevision() {
        return new AccessRevision(currentUser.requireAuthenticatedUsername(), AccessGrantVersion.current());
    }

    private record AccessRevision(String username, long version) { }
}


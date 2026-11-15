package org.ipro.query;

import jakarta.persistence.EntityManager;
import org.ipro.filtergrid.projection.ExecutionStrategy;
import org.ipro.filtergrid.projection.MaterializedStrategy;
import org.ipro.filtergrid.projection.ProjectionDefinition;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.filtergrid.projection.ProjectionQueryPolicy;
import org.ipro.filtergrid.projection.TupleStrategy;
import org.ipro.query.model.QueryLimits;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsFilterActivator;
import org.springframework.transaction.PlatformTransactionManager;

/** Creates the strategy and policy for a checked report projection. Use both for a grouped grid. */
public final class ReportProjectionReads {
    private final EntityManager entityManager;
    private final PlatformTransactionManager transactions;
    private final ReportQueryGuard guard;
    private final RlsCurrentUser currentUser;
    private final RlsFilterActivator rls;
    private final ReportRunQuota quota;
    private final int inMemoryLimit;

    public ReportProjectionReads(EntityManager entityManager, PlatformTransactionManager transactions,
                                 ReportQueryGuard guard, RlsCurrentUser currentUser,
                                 RlsFilterActivator rls, ReportRunQuota quota, int inMemoryLimit) {
        if (inMemoryLimit < 1 || inMemoryLimit == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Некорректный предел строк IN_MEMORY");
        }
        this.entityManager = entityManager;
        this.transactions = transactions;
        this.guard = guard;
        this.currentUser = currentUser;
        this.rls = rls;
        this.quota = quota;
        this.inMemoryLimit = inMemoryLimit;
    }

    /** Сессия, в которой выполняется политика чтения: её же получает сервис группировки. */
    public EntityManager entityManager() {
        return entityManager;
    }

    /** Предел строк режима IN_MEMORY; сверх него чтение отклоняется. */
    public int inMemoryLimit() {
        return inMemoryLimit;
    }

    public ReadPath create(ProjectionDefinition definition, QueryLimits limits) {
        int rowLimit = definition.executionMode() == ProjectionExecutionMode.IN_MEMORY
                ? inMemoryLimit + 1 : limits.maxRows();
        if (definition.executionMode() == ProjectionExecutionMode.HYBRID
                && (definition.maxRows() < 1 || definition.maxRows() > limits.maxRows())) {
            throw new IllegalArgumentException("HYBRID требует maxRows в пределах лимита запроса");
        }
        var policy = new ReportProjectionQueryPolicy(entityManager, transactions, guard, currentUser, rls,
                limits, definition.executionMode() == ProjectionExecutionMode.HYBRID
                        ? (int) Math.min(Integer.MAX_VALUE, (long) rowLimit + 1) : rowLimit,
                quota, definition.executionMode() == ProjectionExecutionMode.IN_MEMORY ? inMemoryLimit : 0);
        ExecutionStrategy strategy = definition.executionMode() == ProjectionExecutionMode.LAZY
                ? new TupleStrategy(definition, entityManager, policy)
                : new MaterializedStrategy(definition, entityManager, policy);
        return new ReadPath(strategy, policy);
    }

    public record ReadPath(ExecutionStrategy strategy, ProjectionQueryPolicy policy) { }
}


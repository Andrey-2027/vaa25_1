package org.ipro.register;

import org.ipro.register.persistence.RecorderOrderClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Objects;

/** Seeds one transactional order row for every managed recorder-owned register at startup. */
public final class RecorderOrderClockBootstrap {
    private static final int MAX_SEED_ATTEMPTS = 5;

    private final EntityManagerFactory entityManagerFactory;
    private final TransactionTemplate transactions;

    public RecorderOrderClockBootstrap(EntityManagerFactory entityManagerFactory,
                                       PlatformTransactionManager transactionManager) {
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory, "entityManagerFactory");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public void initialize(Collection<RegisterDescriptor> descriptors) {
        for (RegisterDescriptor descriptor : descriptors) {
            if (descriptor.recorded()) ensureSeeded(RecorderOrderClock.keyFor(descriptor.entityType()));
        }
    }

    private void ensureSeeded(String registerKey) {
        for (int attempt = 1; attempt <= MAX_SEED_ATTEMPTS; attempt++) {
            try {
                transactions.executeWithoutResult(status -> {
                    EntityManager entityManager = transactionalEntityManager();
                    if (entityManager.find(RecorderOrderClock.class, registerKey) == null) {
                        entityManager.persist(new RecorderOrderClock(registerKey));
                        entityManager.flush();
                    }
                });
                return;
            } catch (RuntimeException failure) {
                if (!isUniqueViolation(failure)) throw failure;
                if (existsInFreshTransaction(registerKey)) return;
                if (attempt == MAX_SEED_ATTEMPTS) {
                    throw new IllegalStateException("RECORDER_ORDER_CLOCK_INIT_FAILED: " + registerKey, failure);
                }
            }
        }
    }

    private boolean existsInFreshTransaction(String registerKey) {
        Boolean exists = transactions.execute(status -> transactionalEntityManager()
            .find(RecorderOrderClock.class, registerKey) != null);
        return Boolean.TRUE.equals(exists);
    }

    private EntityManager transactionalEntityManager() {
        EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        if (entityManager == null) {
            throw new IllegalStateException("Recorder order clock initialization requires a transaction");
        }
        return entityManager;
    }

    private static boolean isUniqueViolation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
        }
        return false;
    }
}

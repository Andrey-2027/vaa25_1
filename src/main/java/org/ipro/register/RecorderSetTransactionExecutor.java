package org.ipro.register;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;

/** Starts each retry in a fresh transaction; never retries inside an already-open caller transaction. */
final class RecorderSetTransactionExecutor {
    private final TransactionTemplate transactions;
    private final int maxAttempts;
    private final long firstBackoffMillis;

    RecorderSetTransactionExecutor(PlatformTransactionManager transactionManager,
                                   int maxAttempts, long firstBackoffMillis) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (firstBackoffMillis < 0) throw new IllegalArgumentException("firstBackoffMillis must be non-negative");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.maxAttempts = maxAttempts;
        this.firstBackoffMillis = firstBackoffMillis;
    }

    <T> T execute(Supplier<T> transactionWork) {
        Objects.requireNonNull(transactionWork, "transactionWork");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return transactions.execute(status -> transactionWork.get());
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> transactionWork.get());
            } catch (RuntimeException failure) {
                if (attempt >= maxAttempts || !isRetryableLockFailure(failure)) throw failure;
                backoff(attempt);
            }
        }
    }

    private void backoff(int attempt) {
        if (firstBackoffMillis == 0) return;
        long multiplier = 1L << Math.min(attempt - 1, 20);
        try {
            Thread.sleep(Math.multiplyExact(firstBackoffMillis, multiplier));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RECORDER_RETRY_INTERRUPTED", interrupted);
        }
    }

    private static boolean isRetryableLockFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof LockTimeoutException || current instanceof PessimisticLockException) return true;
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.equals("40P01") || state.equals("40001")
                        || state.equals("55P03") || state.equals("57014") || state.equals("50200"))) {
                    return true;
                }
            }
        }
        return false;
    }
}

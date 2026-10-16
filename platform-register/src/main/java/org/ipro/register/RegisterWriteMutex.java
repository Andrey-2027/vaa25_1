package org.ipro.register;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.ipro.register.persistence.RecorderOrderClock;

import java.util.Map;

/** Shared serialization point. Taking this lock does not advance order or touch a document. */
final class RegisterWriteMutex {
    private RegisterWriteMutex() { }

    static RecorderOrderClock lock(EntityManager entityManager, Class<?> type, int timeoutMillis) {
        // Do not lose increments already made by an earlier append in this transaction.
        entityManager.flush();
        var hints = Map.<String, Object>of("jakarta.persistence.lock.timeout", timeoutMillis);
        RecorderOrderClock clock = entityManager.find(RecorderOrderClock.class,
            RecorderOrderClock.keyFor(type), LockModeType.PESSIMISTIC_WRITE, hints);
        if (clock == null) {
            throw new IllegalStateException("RECORDER_ORDER_CLOCK_MISSING: " + RecorderOrderClock.keyFor(type));
        }
        entityManager.refresh(clock, LockModeType.PESSIMISTIC_WRITE, hints);
        return clock;
    }
}

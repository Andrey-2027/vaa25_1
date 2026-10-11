package org.ipro.register;

import java.time.LocalDateTime;
import java.util.Objects;

/** Calendar granularity of a register. EXACT keeps time to milliseconds, without rounding. */
public enum RegisterPeriodicity {
    EXACT, DAY, MONTH, YEAR;

    public LocalDateTime normalize(LocalDateTime value) {
        Objects.requireNonNull(value, "period");
        return switch (this) {
            case EXACT -> value.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            case DAY -> value.toLocalDate().atStartOfDay();
            case MONTH -> value.toLocalDate().withDayOfMonth(1).atStartOfDay();
            case YEAR -> value.toLocalDate().withDayOfYear(1).atStartOfDay();
        };
    }
}

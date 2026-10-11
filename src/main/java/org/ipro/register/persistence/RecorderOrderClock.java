package org.ipro.register.persistence;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Transactional, per-register order allocator used by recorder-set replacements. */
@Entity
@Table(name = "ipro_register_recording_clock")
@Access(AccessType.FIELD)
public class RecorderOrderClock {
    @Id
    @Column(name = "register_key", nullable = false, updatable = false, length = 255)
    private String registerKey;

    @Column(name = "last_order", nullable = false)
    private Long lastOrder;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected RecorderOrderClock() { }

    public RecorderOrderClock(String registerKey) {
        this.registerKey = requireKey(registerKey);
        this.lastOrder = 0L;
    }

    public Long getLastOrder() { return lastOrder; }

    /** Increment while the row is held under a pessimistic lock. */
    public long advance() {
        try {
            lastOrder = Math.addExact(lastOrder, 1L);
            return lastOrder;
        } catch (ArithmeticException exhausted) {
            throw new IllegalStateException("RECORDER_ORDER_EXHAUSTED: " + registerKey, exhausted);
        }
    }

    /** Stable physical register table identity, independent of Java class or entity renames. */
    public static String keyFor(Class<?> registerType) {
        var table = registerType.getAnnotation(Table.class);
        if (table == null || table.name().isBlank()) {
            throw new IllegalArgumentException("Recorder register needs an explicit @Table(name): "
                + registerType.getName());
        }
        var parts = new java.util.ArrayList<String>();
        if (!table.catalog().isBlank()) parts.add(table.catalog());
        if (!table.schema().isBlank()) parts.add(table.schema());
        parts.add(table.name());
        return requireKey(String.join(".", parts));
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new IllegalArgumentException("Register clock key must contain 1..255 characters");
        }
        return key;
    }
}

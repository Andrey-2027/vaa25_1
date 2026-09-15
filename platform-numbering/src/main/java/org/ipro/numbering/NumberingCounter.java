package org.ipro.numbering;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Счётчик последовательности. Ключ = {@code entity|scope:value,...|period} — одна строка на
 * каждую реальную последовательность (например "ReceivingDocument|JOURNAL:7|2026").
 *
 * <p>Естественный ключ, а не IDENTITY: счётчик — инфраструктура, без жизненного цикла
 * {@code BaseEntity}. {@code @Version} — запасной механизм, если позже откажемся от
 * пессимистичной блокировки в пользу optimistic-retry.</p>
 */
@Entity
@Table(name = "numbering_counter")
public class NumberingCounter {

    @Id
    @Column(name = "numbering_key", nullable = false, length = 255)
    private String key;

    @Column(name = "last_value", nullable = false)
    private long lastValue;

    @Version
    private long version;

    protected NumberingCounter() {
    }

    public NumberingCounter(String key, long lastValue) {
        this.key = key;
        this.lastValue = lastValue;
    }

    public String getKey() {
        return key;
    }

    public long getLastValue() {
        return lastValue;
    }

    public void setLastValue(long lastValue) {
        this.lastValue = lastValue;
    }

    public long getVersion() {
        return version;
    }
}

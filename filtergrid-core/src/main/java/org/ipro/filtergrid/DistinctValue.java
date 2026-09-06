package org.ipro.filtergrid;

/** A distinct column value suitable for a value-selection popup. */
public record DistinctValue(Object value, long matchingCount, boolean nullValue) {
    public String label() {
        return nullValue ? "Не задано" : String.valueOf(value);
    }
}

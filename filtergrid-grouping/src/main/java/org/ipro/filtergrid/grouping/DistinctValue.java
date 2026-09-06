package org.ipro.filtergrid.grouping;

/** @deprecated use {@link org.ipro.filtergrid.DistinctValue}. */
@Deprecated
public record DistinctValue(Object value, long matchingCount, boolean nullValue) {
    public String label() { return nullValue ? "Не задано" : String.valueOf(value); }
}

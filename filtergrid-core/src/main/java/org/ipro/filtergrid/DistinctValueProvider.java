package org.ipro.filtergrid;

import java.util.List;

/** Supplies distinct values without exposing persistence details to the UI. */
@FunctionalInterface
public interface DistinctValueProvider<T, F> {
    List<DistinctValue> fetch(F field, int offset, int limit);

    default long count(F field) {
        return -1L;
    }
}

package org.ipro.filtergrid.grouping;

/** @deprecated use {@link org.ipro.filtergrid.DistinctValueProvider}. */
@Deprecated
@FunctionalInterface
public interface DistinctValueProvider<T> extends org.ipro.filtergrid.DistinctValueProvider<T, GroupField<T, ?>> {
}

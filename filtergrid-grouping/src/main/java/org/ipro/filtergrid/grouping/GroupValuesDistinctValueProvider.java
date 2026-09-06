package org.ipro.filtergrid.grouping;

import org.ipro.filtergrid.DistinctValue;
import org.ipro.filtergrid.DistinctValueProvider;

import java.util.List;
import java.util.Objects;

/** Adapts GroupValuesService to value-selection UI without exposing EntityManager. */
public final class GroupValuesDistinctValueProvider<T> implements DistinctValueProvider<T, GroupField<T, ?>> {
    private final GroupValuesService<T> delegate;
    private final GroupField<T, ?> field;

    public GroupValuesDistinctValueProvider(GroupValuesService<T> delegate, GroupField<T, ?> field) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.field = Objects.requireNonNull(field, "field");
    }

    @Override
    public List<DistinctValue> fetch(GroupField<T, ?> requestedField, int offset, int limit) {
        if (!field.fieldPath().equals(requestedField.fieldPath())) {
            throw new IllegalArgumentException("Provider is bound to field " + field.fieldPath());
        }
        return delegate.fetchDistinct(field, List.of(), offset, limit, List.of()).stream()
            .map(row -> new DistinctValue(row.value(), row.matchingCount(), row.value() == null))
            .toList();
    }

    @Override
    public long count(GroupField<T, ?> requestedField) {
        if (!field.fieldPath().equals(requestedField.fieldPath())) {
            throw new IllegalArgumentException("Provider is bound to field " + field.fieldPath());
        }
        return delegate.countDistinct(field, List.of());
    }
}

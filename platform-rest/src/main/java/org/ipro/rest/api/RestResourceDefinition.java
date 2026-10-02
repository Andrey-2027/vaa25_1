package org.ipro.rest.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable, structurally checked description of one application-published REST resource. */
public final class RestResourceDefinition<T> {

    /** One externally named scalar and the declarative property path that supplies its value. */
    public record Field(String name, RestFieldType type, RestNullability nullability,
                        RestFieldFormat format, RestPropertyPath source) {
    }

    /** One explicitly enabled equality filter; enabling it is independent of publishing a field. */
    public record Filter(String name, RestFilterOperator operator, RestFieldType valueType,
                         RestPropertyPath source) {
    }

    /** One default sort key. */
    public record Sort(String field, RestSortDirection direction) {
    }

    private final String resourceKey;
    private final int majorVersion;
    private final Class<T> resourceType;
    private final Map<String, Field> fields;
    private final List<String> listFields;
    private final List<String> listDefaultFields;
    private final List<String> detailFields;
    private final List<String> detailDefaultFields;
    private final Map<String, Filter> filters;
    private final List<String> sortFields;
    private final Optional<Sort> defaultSort;
    private final int defaultPageSize;
    private final int maxPageSize;

    RestResourceDefinition(String resourceKey, int majorVersion, Class<T> resourceType,
                           Map<String, Field> fields, List<String> listFields,
                           List<String> listDefaultFields, List<String> detailFields,
                           List<String> detailDefaultFields, Map<String, Filter> filters,
                           List<String> sortFields, Sort defaultSort, int defaultPageSize,
                           int maxPageSize) {
        this.resourceKey = resourceKey;
        this.majorVersion = majorVersion;
        this.resourceType = resourceType;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.listFields = List.copyOf(listFields);
        this.listDefaultFields = List.copyOf(listDefaultFields);
        this.detailFields = List.copyOf(detailFields);
        this.detailDefaultFields = List.copyOf(detailDefaultFields);
        this.filters = Collections.unmodifiableMap(new LinkedHashMap<>(filters));
        this.sortFields = List.copyOf(sortFields);
        this.defaultSort = Optional.ofNullable(defaultSort);
        this.defaultPageSize = defaultPageSize;
        this.maxPageSize = maxPageSize;
    }

    public String resourceKey() {
        return resourceKey;
    }

    public int majorVersion() {
        return majorVersion;
    }

    public Class<T> resourceType() {
        return resourceType;
    }

    /** All declared fields, in declaration order. */
    public Map<String, Field> fields() {
        return fields;
    }

    /** Maximum field set available to list requests. */
    public List<String> listFields() {
        return listFields;
    }

    public List<String> listDefaultFields() {
        return listDefaultFields;
    }

    /** Maximum field set available to detail requests. */
    public List<String> detailFields() {
        return detailFields;
    }

    public List<String> detailDefaultFields() {
        return detailDefaultFields;
    }

    /** Filters are separate from fields, so publishing a field does not enable filtering by it. */
    public Map<String, Filter> filters() {
        return filters;
    }

    public List<String> sortFields() {
        return sortFields;
    }

    public Optional<Sort> defaultSort() {
        return defaultSort;
    }

    public int defaultPageSize() {
        return defaultPageSize;
    }

    public int maxPageSize() {
        return maxPageSize;
    }
}

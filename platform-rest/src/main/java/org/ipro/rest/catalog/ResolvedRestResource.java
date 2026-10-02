package org.ipro.rest.catalog;

import org.ipro.data.EntityDescriptor;
import org.ipro.rest.api.RestResourceDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, metamodel-validated result for one application declaration. */
public final class ResolvedRestResource {

    private final RestResourceKey key;
    private final String beanName;
    private final RestResourceDefinition<?> definition;
    private final EntityDescriptor entityDescriptor;
    private final Map<String, ResolvedRestField> fields;
    private final Map<String, ResolvedRestPath> filters;
    private final Map<String, ResolvedRestPath> sorts;

    ResolvedRestResource(RestResourceKey key, String beanName,
                         RestResourceDefinition<?> definition, EntityDescriptor entityDescriptor,
                         Map<String, ResolvedRestField> fields,
                         Map<String, ResolvedRestPath> filters,
                         Map<String, ResolvedRestPath> sorts) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.beanName = Objects.requireNonNull(beanName, "beanName must not be null");
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.entityDescriptor = Objects.requireNonNull(entityDescriptor, "entityDescriptor must not be null");
        this.fields = immutableMap(fields);
        this.filters = immutableMap(filters);
        this.sorts = immutableMap(sorts);
    }

    public RestResourceKey key() { return key; }
    public String beanName() { return beanName; }
    public RestResourceDefinition<?> definition() { return definition; }
    public EntityDescriptor entityDescriptor() { return entityDescriptor; }
    public Map<String, ResolvedRestField> fields() { return fields; }
    public Map<String, ResolvedRestPath> filters() { return filters; }
    public Map<String, ResolvedRestPath> sorts() { return sorts; }

    /** Operation-specific maximum/default field contract with aliases paired to resolved paths. */
    public RestResourceProjection projection(RestReadOperation operation) {
        List<String> maximum = maximumFields(operation);
        List<String> defaults = defaultFields(operation);
        Map<String, ResolvedRestField> projection = new LinkedHashMap<>();
        maximum.forEach(name -> projection.put(name, fields.get(name)));
        return new RestResourceProjection(operation, maximum, defaults, projection);
    }

    /** Fixed persistent paths derived only from this operation's maximum output fields. */
    public List<String> fixedFetchPaths(RestReadOperation operation) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        maximumFields(operation).stream()
            .map(fields::get)
            .filter(Objects::nonNull)
            .forEach(field -> paths.add(field.source().source()));
        return List.copyOf(paths);
    }

    /** Association requirements for metadata summaries (Stage 2). */
    public List<String> fetchRequirements(RestReadOperation operation) {
        return maximumFields(operation).stream()
            .map(fields::get)
            .filter(Objects::nonNull)
            .map(field -> field.source().segments().getFirst())
            .filter(ResolvedRestPath.Segment::association)
            .map(ResolvedRestPath.Segment::name)
            .distinct().sorted().toList();
    }

    /**
     * Complete metadata path requirements for later C5 evaluation. These are requirements,
     * not grants; callers select the aliases and uses relevant to the actual request.
     */
    public RestResourceAccessRequirements accessRequirements(RestReadOperation operation) {
        List<RestAttributeRequirement> requirements = new ArrayList<>();
        maximumFields(operation).forEach(name -> requirements.add(
            new RestAttributeRequirement(name, RestPathUsage.OUTPUT, fields.get(name).source())));
        if (operation == RestReadOperation.LIST) {
            filters.forEach((name, path) -> requirements.add(
                new RestAttributeRequirement(name, RestPathUsage.FILTER, path)));
            sorts.forEach((name, path) -> requirements.add(
                new RestAttributeRequirement(name, RestPathUsage.SORT, path)));
        }
        return new RestResourceAccessRequirements(key, definition.resourceType(), operation, requirements);
    }

    private List<String> maximumFields(RestReadOperation operation) {
        return switch (Objects.requireNonNull(operation, "operation must not be null")) {
            case LIST -> definition.listFields();
            case DETAIL -> definition.detailFields();
        };
    }

    private List<String> defaultFields(RestReadOperation operation) {
        return switch (Objects.requireNonNull(operation, "operation must not be null")) {
            case LIST -> definition.listDefaultFields();
            case DETAIL -> definition.detailDefaultFields();
        };
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}

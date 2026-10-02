package org.ipro.rest.catalog;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.rest.api.RestResourceDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.ipro.rest.catalog.RestResourceCatalogException.Code.AMBIGUOUS_PERSISTENCE_UNIT;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.DUPLICATE_RESOURCE;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.READ_CAPABILITY_REQUIRED;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.UNMANAGED_RESOURCE_TYPE;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.UNSUPPORTED_EXPOSURE;

/** Immutable catalog of explicit application declarations validated at startup. */
public final class RestResourceCatalog {

    private final Map<RestResourceKey, ResolvedRestResource> byKey;
    private final List<ResolvedRestResource> resources;

    /** Build an atomic catalog from named Spring definition beans and one persistence unit. */
    public RestResourceCatalog(Map<String, RestResourceDefinition<?>> declarations,
                                EntityManagerFactory entityManagerFactory,
                                EntityDescriptorCatalog descriptors) {
        List<NamedDefinition> definitions = (declarations == null ? Map
            .<String, RestResourceDefinition<?>>of() : declarations).entrySet().stream()
            .map(entry -> new NamedDefinition(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing((NamedDefinition named) -> key(named.definition()))
                .thenComparing(NamedDefinition::beanName))
            .toList();

        if (definitions.isEmpty()) {
            this.byKey = Map.of();
            this.resources = List.of();
            return;
        }
        if (entityManagerFactory == null || descriptors == null) {
            throw new RestResourceCatalogException(
                RestResourceCatalogException.Code.BACKEND_CONTEXT_REQUIRED, "", null,
                "resource", "", "definitions require EntityManagerFactory and EntityDescriptorCatalog");
        }
        validateDescriptorSet(definitions.getFirst(), entityManagerFactory, descriptors);

        Map<RestResourceKey, List<NamedDefinition>> groups = definitions.stream()
            .collect(Collectors.groupingBy(named -> key(named.definition()), TreeMap::new,
                Collectors.toCollection(ArrayList::new)));
        for (Map.Entry<RestResourceKey, List<NamedDefinition>> entry : groups.entrySet()) {
            if (entry.getValue().size() > 1) {
                String names = entry.getValue().stream().map(NamedDefinition::beanName)
                    .sorted().collect(Collectors.joining(", "));
                NamedDefinition first = entry.getValue().getFirst();
                throw new RestResourceCatalogException(DUPLICATE_RESOURCE, first.beanName(), entry.getKey(),
                    "resource", "", "the same resource/major pair is declared by beans: " + names);
            }
        }

        Map<RestResourceKey, ResolvedRestResource> resolved = new LinkedHashMap<>();
        for (NamedDefinition named : definitions) {
            resolved.put(key(named.definition()), resolve(named, entityManagerFactory, descriptors));
        }
        this.byKey = Collections.unmodifiableMap(resolved);
        this.resources = List.copyOf(resolved.values());
    }

    /** Empty catalog used when the optional module is present without declarations. */
    public static RestResourceCatalog empty() {
        return new RestResourceCatalog(Map.of(), null, null);
    }

    public Optional<ResolvedRestResource> find(String resource, int major) {
        return Optional.ofNullable(byKey.get(new RestResourceKey(resource, major)));
    }

    public List<ResolvedRestResource> resources() { return resources; }

    private ResolvedRestResource resolve(NamedDefinition named, EntityManagerFactory emf,
                                         EntityDescriptorCatalog descriptors) {
        RestResourceDefinition<?> definition = named.definition();
        RestResourceKey key = key(definition);
        Class<?> type = definition.resourceType();
        EntityType<?> entity;
        try {
            entity = emf.getMetamodel().entity(type);
        } catch (IllegalArgumentException failure) {
            throw new RestResourceCatalogException(UNMANAGED_RESOURCE_TYPE, named.beanName(), key,
                "resource", "", "type is not an entity in the current persistence unit: " + type.getName(), failure);
        }
        EntityDescriptor descriptor = descriptors.find(type).orElseThrow(() ->
            new RestResourceCatalogException(UNMANAGED_RESOURCE_TYPE, named.beanName(), key,
                "resource", "", "no EntityDescriptor exists for managed type " + type.getName()));
        if (!descriptor.jpaManaged()) {
            throw new RestResourceCatalogException(UNMANAGED_RESOURCE_TYPE, named.beanName(), key,
                "resource", "", "EntityDescriptor does not classify this type as JPA-managed");
        }
        if (descriptor.exposure() != EntityExposure.STANDARD_ROOT) {
            throw new RestResourceCatalogException(UNSUPPORTED_EXPOSURE, named.beanName(), key,
                "resource", "", "REST resources require STANDARD_ROOT; found " + descriptor.exposure());
        }
        if (!descriptor.capabilities().allows(FetchScenario.LIST)
            || !descriptor.capabilities().allows(FetchScenario.DETAIL)) {
            throw new RestResourceCatalogException(READ_CAPABILITY_REQUIRED, named.beanName(), key,
                "resource", "", "both LIST and DETAIL read capabilities are required");
        }

        RestResourcePathResolver resolver = new RestResourcePathResolver(emf, type, key, named.beanName());
        resolver.validateId(definition);

        Map<String, ResolvedRestField> fields = new LinkedHashMap<>();
        definition.fields().forEach((name, field) -> fields.put(name,
            new ResolvedRestField(field, resolver.resolveField(field))));
        Map<String, ResolvedRestPath> filters = new LinkedHashMap<>();
        definition.filters().forEach((name, filter) -> filters.put(name, resolver.resolveFilter(filter)));
        Map<String, ResolvedRestPath> sorts = new LinkedHashMap<>();
        definition.sortFields().forEach(name -> sorts.put(name,
            resolver.resolveSort(name, fields.get(name).source())));
        return new ResolvedRestResource(key, named.beanName(), definition, descriptor,
            fields, filters, sorts);
    }

    private void validateDescriptorSet(NamedDefinition first, EntityManagerFactory emf,
                                       EntityDescriptorCatalog descriptors) {
        Set<Class<?>> metamodelTypes = emf.getMetamodel().getEntities().stream()
            .map(EntityType::getJavaType).collect(Collectors.toSet());
        Set<Class<?>> descriptorTypes = descriptors.all().stream()
            .map(EntityDescriptor::type).collect(Collectors.toSet());
        if (!metamodelTypes.equals(descriptorTypes)) {
            Set<Class<?>> missingDescriptors = new java.util.TreeSet<>(Comparator.comparing(Class::getName));
            missingDescriptors.addAll(metamodelTypes);
            missingDescriptors.removeAll(descriptorTypes);
            Set<Class<?>> descriptorsOutsideUnit = new java.util.TreeSet<>(Comparator.comparing(Class::getName));
            descriptorsOutsideUnit.addAll(descriptorTypes);
            descriptorsOutsideUnit.removeAll(metamodelTypes);
            throw new RestResourceCatalogException(
                RestResourceCatalogException.Code.INCONSISTENT_BACKEND_METADATA,
                first.beanName(), key(first.definition()), "resource", "",
                "EntityDescriptorCatalog and this EntityManagerFactory describe different entity sets;"
                    + " missing descriptors=" + classNames(missingDescriptors)
                    + ", descriptors outside unit=" + classNames(descriptorsOutsideUnit));
        }
    }

    private List<String> classNames(Set<Class<?>> types) {
        return types.stream().map(Class::getName).sorted().toList();
    }

    private static RestResourceKey key(RestResourceDefinition<?> definition) {
        if (definition == null) {
            throw new RestResourceCatalogException(
                RestResourceCatalogException.Code.BACKEND_CONTEXT_REQUIRED, "", null,
                "resource", "", "null RestResourceDefinition bean");
        }
        return new RestResourceKey(definition.resourceKey(), definition.majorVersion());
    }

    private record NamedDefinition(String beanName, RestResourceDefinition<?> definition) { }
}

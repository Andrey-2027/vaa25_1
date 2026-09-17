package org.ipro.search;

import jakarta.persistence.Entity;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.data.SearchFieldResolver;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.MetadataResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable runtime catalog of explicitly opted-in, canonically readable entities.
 * Configuration errors are reported while the application context starts.
 */
public final class GlobalSearchCatalog {

    private final List<GlobalSearchSource> sources;
    private final Map<Class<?>, GlobalSearchSource> byEntity;

    public GlobalSearchCatalog(EntityDescriptorCatalog descriptorCatalog,
                               MetadataResolver metadataResolver,
                               InstanceNameResolver instanceNameResolver,
                               List<GlobalSearchProvider<?>> providers) {
        Objects.requireNonNull(descriptorCatalog, "descriptorCatalog cannot be null");
        Objects.requireNonNull(metadataResolver, "metadataResolver cannot be null");
        Objects.requireNonNull(instanceNameResolver, "instanceNameResolver cannot be null");

        Set<Class<?>> customProviderTypes = providerTypes(providers);
        SearchFieldResolver searchFieldResolver =
            new SearchFieldResolver(metadataResolver, instanceNameResolver);

        List<EntityDescriptor> optedIn = descriptorCatalog.all().stream()
            .filter(descriptor -> descriptor.type().isAnnotationPresent(GlobalSearchable.class))
            .sorted(Comparator
                .comparingInt((EntityDescriptor descriptor) ->
                    descriptor.type().getAnnotation(GlobalSearchable.class).order())
                .thenComparing(descriptor -> descriptor.type().getName()))
            .toList();

        List<GlobalSearchSource> resolved = new ArrayList<>(optedIn.size());
        Map<Class<?>, GlobalSearchSource> index = new HashMap<>();
        for (EntityDescriptor descriptor : optedIn) {
            GlobalSearchSource source = resolve(descriptor, metadataResolver,
                instanceNameResolver, searchFieldResolver, customProviderTypes);
            resolved.add(source);
            index.put(source.entityClass(), source);
        }

        for (Class<?> providerType : customProviderTypes) {
            if (!index.containsKey(providerType)) {
                throw configurationError(providerType,
                    "GlobalSearchProvider зарегистрирован, но тип не объявлен через @GlobalSearchable");
            }
        }

        this.sources = List.copyOf(resolved);
        this.byEntity = Map.copyOf(index);
    }

    /** Sources in stable result-group order. */
    public List<GlobalSearchSource> sources() {
        return sources;
    }

    public Optional<GlobalSearchSource> sourceOf(Class<?> entityClass) {
        return Optional.ofNullable(byEntity.get(entityClass));
    }

    public GlobalSearchSource requireSource(Class<?> entityClass) {
        return sourceOf(entityClass).orElseThrow(() ->
            new IllegalArgumentException("Сущность не объявлена через @GlobalSearchable: "
                + entityClass.getName()));
    }

    private static GlobalSearchSource resolve(EntityDescriptor descriptor,
                                              MetadataResolver metadataResolver,
                                              InstanceNameResolver instanceNameResolver,
                                              SearchFieldResolver searchFieldResolver,
                                              Set<Class<?>> customProviderTypes) {
        Class<?> entityClass = descriptor.type();
        if (!entityClass.isAnnotationPresent(Entity.class) || !descriptor.jpaManaged()) {
            throw configurationError(entityClass, "тип не является managed JPA-сущностью");
        }
        if (descriptor.exposure() != EntityExposure.STANDARD_ROOT) {
            throw configurationError(entityClass,
                "глобальный поиск разрешён только для STANDARD_ROOT, найдено "
                    + descriptor.exposure() + " (" + descriptor.reason() + ")");
        }
        if (!descriptor.capabilities().allows(org.ipro.fetch.plan.FetchScenario.LIST)) {
            throw configurationError(entityClass,
                "canonical LIST-read capability запрещена: "
                    + descriptor.capabilities().reason());
        }

        EntityMetadataInfo metadata;
        try {
            metadata = metadataResolver.resolve(entityClass);
        } catch (RuntimeException e) {
            throw configurationError(entityClass,
                "не удалось разрешить @EntityMetadata: " + e.getMessage(), e);
        }

        List<String> searchFields;
        try {
            searchFields = searchFieldResolver.resolve(entityClass, List.of(), true);
        } catch (RuntimeException e) {
            throw configurationError(entityClass,
                "не удалось разрешить canonical search fields: " + e.getMessage(), e);
        }
        if (searchFields.isEmpty()) {
            throw configurationError(entityClass,
                "не найдено ни одного строкового поля в @SearchFields, @InstanceName "
                    + "или effective metadata");
        }

        boolean hasDisplay = instanceNameResolver.hasDeclaration(entityClass)
            || HasDisplayName.class.isAssignableFrom(entityClass)
            || customProviderTypes.contains(entityClass);
        if (!hasDisplay) {
            throw configurationError(entityClass,
                "нет @InstanceName/HasDisplayName и не зарегистрирован custom "
                    + "GlobalSearchProvider для подписи результата");
        }

        String title = metadata.getListFormTitle();
        if (title == null || title.isBlank()) {
            title = entityClass.getSimpleName();
        }
        int order = entityClass.getAnnotation(GlobalSearchable.class).order();
        List<String> additionalPaths = new ArrayList<>();
        List<String> resultPaths = new ArrayList<>(searchFields);
        resultPaths.addAll(instanceNameResolver.instanceNamePaths(entityClass));
        for (String path : resultPaths) {
            ColumnPath.resolve(entityClass, path).getFetchPaths().forEach(fetchPath -> {
                if (!additionalPaths.contains(fetchPath)) {
                    additionalPaths.add(fetchPath);
                }
            });
        }
        return new GlobalSearchSource(order, entityClass, searchFields, additionalPaths,
            SearchFieldResolver.idFieldName(entityClass), title);
    }

    private static Set<Class<?>> providerTypes(List<GlobalSearchProvider<?>> providers) {
        Set<Class<?>> types = new HashSet<>();
        for (GlobalSearchProvider<?> provider : providers == null
                ? List.<GlobalSearchProvider<?>>of() : providers) {
            Objects.requireNonNull(provider, "provider cannot be null");
            Class<?> type = Objects.requireNonNull(provider.entityClass(),
                "provider.entityClass() cannot be null");
            if (provider.queryTimeoutMs() <= 0) {
                throw configurationError(type, "queryTimeoutMs должен быть больше нуля");
            }
            if (!types.add(type)) {
                throw configurationError(type,
                    "для сущности зарегистрировано больше одного GlobalSearchProvider");
            }
        }
        return Set.copyOf(types);
    }

    private static IllegalStateException configurationError(Class<?> entityClass, String message) {
        return new IllegalStateException("Ошибка конфигурации глобального поиска для "
            + entityClass.getName() + ": " + message);
    }

    private static IllegalStateException configurationError(Class<?> entityClass,
                                                            String message,
                                                            Throwable cause) {
        return new IllegalStateException("Ошибка конфигурации глобального поиска для "
            + entityClass.getName() + ": " + message, cause);
    }
}

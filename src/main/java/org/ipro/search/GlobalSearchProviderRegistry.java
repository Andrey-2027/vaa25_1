package org.ipro.search;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime-реестр провайдеров поиска.
 *
 * <p>Прикладной провайдер может заменить generic JPA-маппер для конкретной сущности, если для
 * неё нужна особая подпись или классификация. Провайдеры не выполняют SQL: источники читаются
 * canonical executor'ом, а незарегистрированным типам назначается generic JPA-маппер.</p>
 */
public final class GlobalSearchProviderRegistry {

    private final Map<Class<?>, GlobalSearchProvider<?>> providers;
    private final org.ipro.fetch.instance.InstanceNameResolver instanceNameResolver;

    public GlobalSearchProviderRegistry(List<GlobalSearchProvider<?>> providers) {
        this(providers, null);
    }

    /**
     * @param instanceNameResolver единый источник отображаемого имени для мигрированных
     *                              сущностей; {@code null} — generic JPA-провайдер остаётся
     *                              на объявленных display fields
     */
    public GlobalSearchProviderRegistry(List<GlobalSearchProvider<?>> providers,
                                        org.ipro.fetch.instance.InstanceNameResolver instanceNameResolver) {
        Map<Class<?>, GlobalSearchProvider<?>> index = new HashMap<>();
        for (GlobalSearchProvider<?> provider : providers) {
            Objects.requireNonNull(provider, "provider");
            Class<?> entityClass = Objects.requireNonNull(provider.entityClass(),
                "provider.entityClass() не может быть null");
            if (index.putIfAbsent(entityClass, provider) != null) {
                throw new IllegalStateException(
                    "Для сущности уже зарегистрирован GlobalSearchProvider: " + entityClass.getName());
            }
        }
        this.providers = Map.copyOf(index);
        this.instanceNameResolver = instanceNameResolver;
    }

    /**
     * Вернуть прикладной провайдер либо стандартный JPA-провайдер для декларации.
     */
    @SuppressWarnings("unchecked")
    public <T> GlobalSearchProvider<T> providerOf(GlobalSearchSource source) {
        Objects.requireNonNull(source, "source");
        GlobalSearchProvider<?> provider = providers.get(source.entityClass());
        if (provider != null) {
            return (GlobalSearchProvider<T>) provider;
        }
        return new JpaGlobalSearchProvider<>((Class<T>) source.entityClass(), instanceNameResolver);
    }

    /** Зарегистрированные прикладные провайдеры — только для диагностики и тестов. */
    public Map<Class<?>, GlobalSearchProvider<?>> providers() {
        return providers;
    }
}

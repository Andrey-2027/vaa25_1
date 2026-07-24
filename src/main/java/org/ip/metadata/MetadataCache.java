package org.ip.metadata;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Потокобезопасный кэш метаданных по классу сущности.
 * Используется MetadataResolver, чтобы не парсить аннотации при каждом обращении.
 *
 * Реализация намеренно простая — без TTL, без eviction. В энтерпрайз-приложении
 * количество @Entity-классов обычно не превышает нескольких сотен, поэтому
 * кэш живёт в памяти всё время работы приложения.
 */
public class MetadataCache {

    private final ConcurrentHashMap<Class<?>, EntityMetadataInfo> cache = new ConcurrentHashMap<>();

    /**
     * Получить кэшированную метадату. Возвращает null, если для класса ещё не резолвили.
     */
    public EntityMetadataInfo get(Class<?> entityClass) {
        return cache.get(entityClass);
    }

    /**
     * Положить в кэш. Перезаписывает существующее значение.
     */
    public void put(Class<?> entityClass, EntityMetadataInfo info) {
        cache.put(entityClass, info);
    }

    /**
     * Проверить, есть ли метадата в кэше.
     */
    public boolean contains(Class<?> entityClass) {
        return cache.containsKey(entityClass);
    }

    /**
     * Очистить кэш (для тестов или при горячей перезагрузке классов).
     */
    public void clear() {
        cache.clear();
    }

    /**
     * Количество закэшированных классов.
     */
    public int size() {
        return cache.size();
    }
}

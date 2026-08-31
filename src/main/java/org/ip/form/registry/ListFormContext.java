package org.ip.form.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Неизменяемый снимок контекста открытого ListForm.
 *
 * <p>Контекст разделён на три части:</p>
 * <ul>
 *     <li>{@code openingParameters} — произвольные параметры, переданные при открытии;</li>
 *     <li>{@code openingFilters} — фиксированные ограничения связи от источника;</li>
 *     <li>{@code contextFilters} — текущие значения интерактивной панели списка.</li>
 * </ul>
 *
 * <p>Снимок предназначен для команд и составных View. Он не раскрывает внутреннее
 * состояние ListForm и не позволяет вызывающему коду изменить его.</p>
 */
public record ListFormContext(
        Map<String, Object> openingParameters,
        Map<String, Object> openingFilters,
        Map<String, Object> contextFilters) {

    public ListFormContext {
        openingParameters = immutableCopy(openingParameters);
        openingFilters = immutableCopy(openingFilters);
        contextFilters = immutableCopy(contextFilters);
    }

    /** Получить произвольный параметр открытия или null. */
    public Object parameter(String name) {
        return openingParameters.get(name);
    }

    /** Получить параметр открытия с проверкой ожидаемого типа. */
    public <T> T parameter(String name, Class<T> type) {
        Object value = parameter(name);
        return value == null ? null : type.cast(value);
    }

    /** Получить фиксированный фильтр открытия или null. */
    public Object openingFilter(String path) {
        return openingFilters.get(path);
    }

    /** Получить фильтр открытия с проверкой ожидаемого типа. */
    public <T> T openingFilter(String path, Class<T> type) {
        Object value = openingFilter(path);
        return value == null ? null : type.cast(value);
    }

    /**
     * Получить эффективное значение контекста для path.
     * Фиксированное ограничение открытия имеет приоритет: интерактивная панель не может
     * расширить или заменить связь, пришедшую от исходной формы.
     */
    public Object filter(String path) {
        return openingFilters.containsKey(path)
                ? openingFilters.get(path)
                : contextFilters.get(path);
    }

    /** Получить эффективный фильтр с проверкой ожидаемого типа. */
    public <T> T filter(String path, Class<T> type) {
        Object value = filter(path);
        return value == null ? null : type.cast(value);
    }

    /** Все фильтры, объединённые по path; фиксированное ограничение имеет приоритет. */
    public Map<String, Object> effectiveFilters() {
        Map<String, Object> result = new LinkedHashMap<>(openingFilters);
        contextFilters.forEach(result::putIfAbsent);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}

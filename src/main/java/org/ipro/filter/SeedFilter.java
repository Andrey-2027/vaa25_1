package org.ipro.filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Одно предзаданное ограничение открытия формы: пара (path, value).
 *
 * <p>Старый формат {@code seedFilter: {path, value}} сохраняется. Для связи с несколькими
 * параметрами поддерживается также {@code seedFilters: [{path, value}, ...]}.</p>
 *
 * <p>{@code value} может быть скаляром ({@code = path value}) или коллекцией допустимых
 * значений ({@code path IN (...)}), что удобно для тип-ограничений.</p>
 */
public record SeedFilter(String path, Object value) {

    private static final String KEY = "seedFilter";
    private static final String MULTI_KEY = "seedFilters";

    /**
     * Достать одно ограничение в legacy-формате {@code seedFilter}.
     * Возвращает null при отсутствии или некорректном виде.
     */
    public static SeedFilter fromParameters(Map<String, Object> parameters) {
        if (parameters == null) {
            return null;
        }
        return fromRaw(parameters.get(KEY));
    }

    /**
     * Достать все ограничения открытия. Поддерживает старый одиночный ключ и новый
     * список {@code seedFilters}; оба источника объединяются в порядке объявления.
     */
    public static List<SeedFilter> allFromParameters(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }

        List<SeedFilter> result = new ArrayList<>();
        appendRaw(result, parameters.get(MULTI_KEY));
        SeedFilter legacy = fromRaw(parameters.get(KEY));
        if (legacy != null) {
            result.add(legacy);
        }
        return List.copyOf(result);
    }

    private static void appendRaw(List<SeedFilter> result, Object raw) {
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                SeedFilter filter = fromRaw(item);
                if (filter != null) {
                    result.add(filter);
                }
            }
            return;
        }

        if (raw instanceof Map<?, ?> map && !map.containsKey("path")) {
            // Дополнительная короткая форма: seedFilters: {"journal.id": 10, "type": "X"}.
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.add(new SeedFilter(String.valueOf(entry.getKey()), entry.getValue()));
                }
            }
            return;
        }

        SeedFilter filter = fromRaw(raw);
        if (filter != null) {
            result.add(filter);
        }
    }

    private static SeedFilter fromRaw(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object path = map.get("path");
        if (path == null || String.valueOf(path).isBlank()) {
            return null;
        }
        return new SeedFilter(String.valueOf(path), map.get("value"));
    }
}

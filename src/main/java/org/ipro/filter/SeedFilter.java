package org.ipro.filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Совместимый адаптер ограничения выбора; новый прикладной код может использовать Map напрямую. */
@Deprecated(forRemoval = false)
public record SeedFilter(String path, Object value) {
    public static SeedFilter fromParameters(Map<String, Object> parameters) {
        if (parameters == null || !(parameters.get("seedFilter") instanceof Map<?, ?> map)) return null;
        Object path = map.get("path");
        return path == null ? null : new SeedFilter(String.valueOf(path), map.get("value"));
    }

    public static List<SeedFilter> allFromParameters(Map<String, Object> parameters) {
        if (parameters == null) return List.of();
        List<SeedFilter> result = new ArrayList<>();
        Object raw = parameters.get("seedFilters");
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) if (item instanceof Map<?, ?> map && map.get("path") != null)
                result.add(new SeedFilter(String.valueOf(map.get("path")), map.get("value")));
        }
        SeedFilter single = fromParameters(parameters);
        if (single != null) result.add(single);
        return List.copyOf(result);
    }
}

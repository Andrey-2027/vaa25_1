package org.ipro.reportstudio.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runtime-проверка объявленных параметров visual query. */
public final class VisualQueryParameterRuntime {
    private VisualQueryParameterRuntime() { }

    public static Map<String, Object> resolve(List<VisualQueryDefinition.Parameter> definitions,
                                              Map<String, Object> supplied) {
        Map<String, Object> values = supplied == null ? Map.of() : supplied;
        Map<String, Object> result = new LinkedHashMap<>();
        for (VisualQueryDefinition.Parameter parameter : definitions == null ? List.<VisualQueryDefinition.Parameter>of() : definitions) {
            Object value = values.containsKey(parameter.name()) ? values.get(parameter.name()) : parameter.defaultValue();
            if (value == null && parameter.required()) {
                throw new IllegalArgumentException("Обязательный visual-параметр не заполнен: " + parameter.name());
            }
            if (value != null) result.put(parameter.name(), value);
        }
        values.keySet().stream().filter(name -> definitions == null || definitions.stream().noneMatch(p -> p.name().equals(name)))
                .forEach(name -> { throw new IllegalArgumentException("Параметр не объявлен в visual query: " + name); });
        return Map.copyOf(result);
    }
}

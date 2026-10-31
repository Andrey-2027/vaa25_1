package org.ipro.query;

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
            if (value != null) {
                if ("DATE".equals(parameter.dataType()) && !(value instanceof java.time.LocalDate))
                    throw new IllegalArgumentException("Visual-параметр " + parameter.name() + " требует LocalDate");
                if ("DATETIME".equals(parameter.dataType()) && !(value instanceof java.time.LocalDateTime))
                    throw new IllegalArgumentException("Visual-параметр " + parameter.name() + " требует LocalDateTime");
                String expected = parameter.dataType();
                if (!java.util.Set.of("TEXT", "DATE", "DATETIME", "ENTITY").contains(expected)) {
                    try {
                        Class<?> type = switch(expected) { case "INTEGER", "DECIMAL", "NUMBER", "long", "int", "short", "byte" -> Number.class; case "double", "float" -> Number.class; case "BOOLEAN", "boolean" -> Boolean.class; case "STRING", "TEXT" -> String.class; case "ENUM" -> Enum.class; default -> Class.forName(expected, false, Thread.currentThread().getContextClassLoader()); };
                        if (Number.class.isAssignableFrom(type) && value instanceof Number) { /* JDBC numeric binding. */ }
                        else if (!type.isInstance(value)) throw new IllegalArgumentException("Параметр " + parameter.name() + " требует " + type.getSimpleName());
                    } catch (ClassNotFoundException unknown) { throw new IllegalArgumentException("Неизвестный тип параметра: " + expected, unknown); }
                }
                result.put(parameter.name(), value);
            }
        }
        values.keySet().stream().filter(name -> definitions == null || definitions.stream().noneMatch(p -> p.name().equals(name)))
                .forEach(name -> { throw new IllegalArgumentException("Параметр не объявлен в visual query: " + name); });
        return Map.copyOf(result);
    }
}

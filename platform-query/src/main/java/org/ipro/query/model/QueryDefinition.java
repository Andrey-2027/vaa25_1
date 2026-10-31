package org.ipro.query.model;

import java.util.List;
import java.util.Objects;

/**
 * Описание запроса, не зависящее от отчётного шаблона, UDR DOM и UniDR.
 *
 * <p>Это именно описание, а не подготовленный к выполнению запрос: конкретные
 * bindings, схема результата и предупреждения появляются только после
 * подготовки. Поля {@code visualQueryJson} и {@code visualFilterJson}
 * сохраняют существующий формат JSON; первичность представления задаёт
 * {@link QuerySource}, а не непустая строка.</p>
 */
public record QueryDefinition(
        String jpql,
        QuerySource source,
        String visualQueryJson,
        String visualFilterJson,
        List<QueryParameter> parameters,
        QueryLimits limits) {

    public QueryDefinition {
        jpql = jpql == null ? "" : jpql;
        source = source == null ? QuerySource.MANUAL : source;
        visualQueryJson = visualQueryJson == null || visualQueryJson.isBlank() ? null : visualQueryJson;
        visualFilterJson = visualFilterJson == null || visualFilterJson.isBlank() ? null : visualFilterJson;
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
        limits = limits == null ? QueryLimits.DEFAULT : limits;
        if (source == QuerySource.VISUAL && visualQueryJson == null) {
            throw new IllegalArgumentException("Некорректный визуальный запрос: отсутствует visualQueryJson");
        }
    }

    public QueryDefinition(String jpql, QuerySource source, String visualQueryJson,
                           String visualFilterJson, List<QueryParameter> parameters) {
        this(jpql, source, visualQueryJson, visualFilterJson, parameters, QueryLimits.DEFAULT);
    }

    public QueryParameter parameter(String name) {
        return parameters.stream()
                .filter(parameter -> parameter.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Параметр не объявлен: " + name));
    }

    public QueryDefinition withParameters(List<QueryParameter> updated) {
        return new QueryDefinition(jpql, source, visualQueryJson, visualFilterJson,
                Objects.requireNonNull(updated, "updated"), limits);
    }
}

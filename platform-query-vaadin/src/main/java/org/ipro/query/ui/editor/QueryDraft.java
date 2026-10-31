package org.ipro.query.ui.editor;

import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QuerySource;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Черновик запроса, с которым работает редактор. Это не сохранённый документ
 * и не отчётный шаблон: только текст, представление, параметры и их источник.
 *
 * <p>Неизменяемый: каждое действие редактора создаёт новый черновик, поэтому
 * «применить» и «отменить» — это операции над ссылкой, а не откат мутаций
 * шаблона.</p>
 */
public record QueryDraft(
        String jpql,
        QuerySource source,
        String visualQueryJson,
        String visualFilterJson,
        List<QueryParameter> parameters) {

    public QueryDraft {
        jpql = jpql == null ? "" : jpql;
        source = source == null ? QuerySource.MANUAL : source;
        visualQueryJson = visualQueryJson == null || visualQueryJson.isBlank() ? null : visualQueryJson;
        visualFilterJson = visualFilterJson == null || visualFilterJson.isBlank() ? null : visualFilterJson;
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
        if (source == QuerySource.VISUAL && visualQueryJson == null) {
            throw new IllegalArgumentException("Некорректный визуальный запрос: отсутствует visualQueryJson");
        }
    }

    public QueryDefinition definition() {
        return new QueryDefinition(jpql, source, visualQueryJson, visualFilterJson, parameters);
    }

    public static QueryDraft of(QueryDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return new QueryDraft(definition.jpql(), definition.source(), definition.visualQueryJson(),
                definition.visualFilterJson(), definition.parameters());
    }

    public QueryDraft withParameters(List<QueryParameter> updated) {
        return new QueryDraft(jpql, source, visualQueryJson, visualFilterJson, updated);
    }

    public QueryDraft withJpql(String jpql) {
        return new QueryDraft(jpql, source, visualQueryJson, visualFilterJson, parameters);
    }

    public QueryDraft withSource(QuerySource source) {
        return new QueryDraft(jpql, source, visualQueryJson, visualFilterJson, parameters);
    }

    public QueryDraft withVisualQueryJson(String visualQueryJson) {
        return new QueryDraft(jpql, source, visualQueryJson, visualFilterJson, parameters);
    }

    public Optional<QueryParameter> parameter(String name) {
        return parameters.stream().filter(parameter -> parameter.name().equals(name)).findFirst();
    }

    /** Добавляет параметр или заменяет объявление с тем же именем. */
    public QueryDraft withParameter(QueryParameter parameter) {
        Objects.requireNonNull(parameter, "parameter");
        var updated = new java.util.ArrayList<QueryParameter>(parameters.size() + 1);
        boolean replaced = false;
        for (QueryParameter candidate : parameters) {
            if (candidate.name().equals(parameter.name())) {
                updated.add(parameter);
                replaced = true;
            } else {
                updated.add(candidate);
            }
        }
        if (!replaced) {
            updated.add(parameter);
        }
        return withParameters(updated);
    }

    public QueryDraft withoutParameter(String name) {
        return withParameters(parameters.stream()
                .filter(parameter -> !parameter.name().equals(name))
                .toList());
    }

    public QueryDraft withoutParameters(java.util.function.Predicate<QueryParameter> predicate) {
        return withParameters(parameters.stream().filter(predicate.negate()).toList());
    }

    public int nextParameterPosition() {
        return parameters.stream().mapToInt(QueryParameter::position).max().orElse(-1) + 1;
    }
}

package org.ipro.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Полное состояние сохранённого вида грида (GridFormView.columns): состав колонок +
 * условия отбора. Раньше в этом текстовом поле лежал только состав колонок (сначала
 * ";"-формат, потом голый JSON-массив ColumnPath.Spec) — теперь это единый объект,
 * чтобы вид можно было расширять дальше (следующая настройка колонки/новый вид отбора —
 * просто новое поле в одном из record'ов, без придумывания нового текстового формата).
 *
 * Обратная совместимость (fromJson понимает все три формата):
 *   - "path;path=Заголовок"        — самый старый, до перехода на JSON
 *   - "[{\"path\":...}, ...]"        — голый JSON-массив колонок, без отбора
 *   - "{\"columns\":[...], \"filters\":[...]}" — текущий формат
 */
public record GridViewState(List<ColumnPath.Spec> columns, List<FilterSpec> filters) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public GridViewState {
        columns = columns == null ? List.of() : List.copyOf(columns);
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    public static GridViewState of(List<ColumnPath> columns, Class<?> entityClass, List<FilterSpec> filters) {
        List<ColumnPath.Spec> specs = new ArrayList<>(columns.size());
        for (ColumnPath column : columns) {
            String defaultLabel = ColumnPath.resolve(entityClass, column.getKey()).getLabel();
            boolean customLabel = !column.getLabel().equals(defaultLabel);
            specs.add(new ColumnPath.Spec(column.getKey(), customLabel ? column.getLabel() : null));
        }
        return new GridViewState(specs, filters);
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize GridViewState to JSON", e);
        }
    }

    public static GridViewState fromJson(String value) {
        if (value == null || value.isBlank()) {
            return new GridViewState(List.of(), List.of());
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("{")) {
            try {
                return JSON.readValue(trimmed, GridViewState.class);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot parse GridViewState JSON: " + value, e);
            }
        }
        // Легаси: голый массив колонок (без отбора) или совсем старый ";"-формат
        List<ColumnPath.Spec> legacyColumns = trimmed.startsWith("[")
            ? parseJsonArray(trimmed)
            : parseLegacyTextFormat(trimmed);
        return new GridViewState(legacyColumns, List.of());
    }

    private static List<ColumnPath.Spec> parseJsonArray(String value) {
        try {
            return JSON.readValue(value, new TypeReference<List<ColumnPath.Spec>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse legacy column list JSON: " + value, e);
        }
    }

    private static List<ColumnPath.Spec> parseLegacyTextFormat(String value) {
        List<ColumnPath.Spec> specs = new ArrayList<>();
        for (String token : value.split(";")) {
            if (token.isBlank()) continue;
            int eq = token.indexOf('=');
            String path = eq >= 0 ? token.substring(0, eq) : token;
            String label = eq >= 0 ? token.substring(eq + 1) : null;
            specs.add(new ColumnPath.Spec(path, label));
        }
        return specs;
    }
}

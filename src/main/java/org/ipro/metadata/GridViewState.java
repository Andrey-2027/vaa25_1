package org.ipro.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.ipro.filter.FilterTreeJson;
import org.ipro.filter.FilterCondition;
import org.ipro.filter.FilterConditionNode;
import org.ipro.filter.FilterDataType;
import org.ipro.filter.FilterGroup;
import org.ipro.filter.FilterNode;
import org.ipro.filter.FilterOperator;
import org.ipro.filter.LogicalOperator;

import java.util.ArrayList;
import java.util.List;

/** Полное переносимое состояние сохранённого вида грида. */
public record GridViewState(List<ColumnPath.Spec> columns, List<FilterSpec> filters,
                            FilterNode fixedFilter, FilterNode userFilter) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public GridViewState {
        columns = columns == null ? List.of() : List.copyOf(columns);
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    /** Legacy-конструктор: старый массив FilterSpec остаётся пользовательским AND-фильтром. */
    public GridViewState(List<ColumnPath.Spec> columns, List<FilterSpec> filters) {
        this(columns, filters, null, null);
    }

    public static GridViewState of(List<ColumnPath> columns, Class<?> entityClass, List<FilterSpec> filters) {
        return new GridViewState(specs(columns, entityClass), filters);
    }

    public static GridViewState of(List<ColumnPath> columns, Class<?> entityClass,
                                   FilterNode fixedFilter, FilterNode userFilter) {
        return new GridViewState(specs(columns, entityClass), List.of(), fixedFilter, userFilter);
    }

    private static List<ColumnPath.Spec> specs(List<ColumnPath> columns, Class<?> entityClass) {
        List<ColumnPath.Spec> specs = new ArrayList<>(columns.size());
        for (ColumnPath column : columns) {
            String defaultLabel = ColumnPath.resolve(entityClass, column.getKey()).getLabel();
            specs.add(new ColumnPath.Spec(column.getKey(), column.getLabel().equals(defaultLabel) ? null : column.getLabel()));
        }
        return specs;
    }

    public String toJson() {
        try {
            var object = JSON.createObjectNode();
            object.set("columns", JSON.valueToTree(columns));
            object.set("filters", JSON.valueToTree(filters));
            if (fixedFilter != null) object.set("fixedFilter", FilterTreeJson.write(fixedFilter));
            if (userFilter != null) object.set("userFilter", FilterTreeJson.write(userFilter));
            return JSON.writeValueAsString(object);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize GridViewState to JSON", e);
        }
    }

    public static GridViewState fromJson(String value) {
        if (value == null || value.isBlank()) return new GridViewState(List.of(), List.of());
        String trimmed = value.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode object = JSON.readTree(trimmed);
                List<ColumnPath.Spec> columns = JSON.convertValue(object.path("columns"), new TypeReference<List<ColumnPath.Spec>>() {});
                List<FilterSpec> filters = object.has("filters")
                        ? JSON.convertValue(object.path("filters"), new TypeReference<List<FilterSpec>>() {}) : List.of();
                return new GridViewState(columns, filters,
                        FilterTreeJson.read(object.get("fixedFilter")),
                        FilterTreeJson.read(object.get("userFilter")));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot parse GridViewState JSON: " + value, e);
            }
        }
        List<ColumnPath.Spec> legacyColumns = trimmed.startsWith("[")
                ? parseJsonArray(trimmed) : parseLegacyTextFormat(trimmed);
        return new GridViewState(legacyColumns, List.of());
    }

    /** Преобразует legacy FilterSpec в плоскую пользовательскую AND-группу. */
    public FilterNode userFilterOrLegacy() {
        if (userFilter != null) return userFilter;
        if (filters.isEmpty()) return null;
        return new FilterGroup(LogicalOperator.AND, filters.stream()
                .map(GridViewState::legacyCondition)
                .map(FilterConditionNode::of)
                .map(node -> (FilterNode) node).toList());
    }

    private static FilterCondition legacyCondition(FilterSpec spec) {
        FilterOperator operator = switch (spec.mode() == null ? "" : spec.mode()) {
            case "EQUALS" -> FilterOperator.EQ;
            case "STARTS_WITH" -> FilterOperator.STARTS_WITH;
            case "ENDS_WITH", "CONTAINS" -> FilterOperator.CONTAINS;
            default -> spec.valueTo() == null ? FilterOperator.EQ : FilterOperator.BETWEEN;
        };
        return new FilterCondition(spec.path(), operator, spec.value(), spec.valueTo(), FilterDataType.TEXT);
    }

    private static List<ColumnPath.Spec> parseJsonArray(String value) {
        try { return JSON.readValue(value, new TypeReference<List<ColumnPath.Spec>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Cannot parse legacy column list JSON: " + value, e); }
    }

    private static List<ColumnPath.Spec> parseLegacyTextFormat(String value) {
        List<ColumnPath.Spec> specs = new ArrayList<>();
        for (String token : value.split(";")) {
            if (token.isBlank()) continue;
            int eq = token.indexOf('=');
            specs.add(new ColumnPath.Spec(eq >= 0 ? token.substring(0, eq) : token,
                    eq >= 0 ? token.substring(eq + 1) : null));
        }
        return specs;
    }
}

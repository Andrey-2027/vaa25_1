package org.ipro.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Внутренний JSON-адаптер полиморфного FilterNode без type metadata в публичном формате. */
public final class FilterTreeJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FilterTreeJson() {
    }

    public static JsonNode write(FilterNode node) {
        if (node == null) return null;
        if (node instanceof FilterConditionNode leaf) {
            return JSON.valueToTree(java.util.Map.of("condition", leaf.condition()));
        }
        FilterGroup group = (FilterGroup) node;
        return JSON.valueToTree(java.util.Map.of(
                "operator", group.operator(),
                "children", group.children().stream().map(FilterTreeJson::write).toList()));
    }

    public static FilterNode read(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            if (node.has("condition")) {
                return new FilterConditionNode(JSON.treeToValue(node.get("condition"), FilterCondition.class));
            }
            LogicalOperator operator = LogicalOperator.valueOf(node.path("operator").asText("AND"));
            List<FilterNode> children = new ArrayList<>();
            for (JsonNode child : node.path("children")) children.add(read(child));
            return new FilterGroup(operator, children);
        } catch (Exception e) {
            throw new IllegalArgumentException("Некорректное дерево фильтра", e);
        }
    }
}

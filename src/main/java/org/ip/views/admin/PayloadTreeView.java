package org.ip.views.admin;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.treegrid.TreeGrid;

/**
 * Рендер payload_json операции (журнал, этап 9) в дерево Vaadin (TreeGrid):
 * фреймы → длительности → выполненные SQL. Чистая функция, без состояния.
 */
public final class PayloadTreeView {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadTreeView() {
    }

    /** Узел дерева журнала. */
    public record PayloadNode(String label, List<PayloadNode> children) {
    }

    public static TreeGrid<PayloadNode> build(String payloadJson) {
        TreeGrid<PayloadNode> tree = new TreeGrid<>();
        tree.addHierarchyColumn(PayloadNode::label).setHeader("Дерево операции");
        tree.setWidthFull();
        tree.setHeightFull();
        if (payloadJson == null || payloadJson.isBlank()) {
            return tree;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(payloadJson);
        } catch (Exception e) {
            List<PayloadNode> raw = List.of(new PayloadNode(payloadJson, List.of()));
            tree.setItems(raw, PayloadNode::children);
            return tree;
        }
        PayloadNode rootNode = toNode(root, "operation");
        tree.setItems(List.of(rootNode), PayloadNode::children);
        tree.expand(rootNode);
        return tree;
    }

    private static PayloadNode toNode(JsonNode node, String fallbackName) {
        String name = node.hasNonNull("name") ? node.get("name").asText() : fallbackName;
        StringBuilder label = new StringBuilder(name);

        JsonNode duration = node.path("durationMs");
        if (duration.isNumber()) {
            label.append("  durationMs=").append(fmt(duration.asDouble()));
        }
        JsonNode sqlCount = node.path("sqlCount");
        if (sqlCount.isIntegralNumber()) {
            label.append("  sqlCount=").append(sqlCount.asInt());
        }
        JsonNode sqlTotal = node.path("sqlTotalMs");
        if (sqlTotal.isNumber()) {
            label.append("  sqlTotalMs=").append(fmt(sqlTotal.asDouble()));
        }
        if (node.path("failed").asBoolean(false)) {
            label.append("  FAILED");
        }

        List<PayloadNode> children = new ArrayList<>();
        JsonNode sqls = node.path("sql");
        if (sqls.isArray()) {
            for (JsonNode sqlNode : sqls) {
                String text = sqlNode.path("text").asText("?");
                String ms = sqlNode.path("ms").isNumber()
                        ? fmt(sqlNode.path("ms").asDouble())
                        : "?";
                children.add(new PayloadNode(text.length() > 400
                        ? "SQL (" + ms + " ms): " + text.substring(0, 400) + "…"
                        : "SQL (" + ms + " ms): " + text, List.of()));
            }
        }
        JsonNode entityData = node.path("entityData");
        if (entityData.isContainerNode()) {
            String data = entityData.toString();
            children.add(new PayloadNode(data.length() > 500
                    ? "entityData: " + data.substring(0, 500) + "…"
                    : "entityData: " + data, List.of()));
        }
        JsonNode childNodes = node.path("children");
        if (childNodes.isArray()) {
            for (JsonNode child : childNodes) {
                children.add(toNode(child, "frame"));
            }
        }
        return new PayloadNode(label.toString(), children);
    }

    private static String fmt(double value) {
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }
}
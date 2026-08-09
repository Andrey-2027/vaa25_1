package org.ip.views.admin;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.treegrid.TreeGrid;

/**
 * Рендер payload_json операции (журнал, этап 9) в дерево Vaadin (TreeGrid):
 * фреймы → длительности → выполненные SQL. Чистая функция, без состояния.
 * <p>
 * Под деревом — TextArea с полным текстом выбранного узла (SQL, снимок
 * сущности): в самом дереве текст обрезан (400/500 символов).
 */
public final class PayloadTreeView {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadTreeView() {
    }

    /** Узел дерева журнала: label (обрезанный) + detail (полный текст). */
    public record PayloadNode(String label, String detail, List<PayloadNode> children) {
    }

    public static VerticalLayout build(String payloadJson) {
        TreeGrid<PayloadNode> tree = new TreeGrid<>();
        tree.addHierarchyColumn(PayloadNode::label).setHeader("Дерево операции");
        tree.setWidthFull();
        tree.setHeightFull();
        tree.setSelectionMode(com.vaadin.flow.component.grid.Grid.SelectionMode.SINGLE);

        TextArea detail = new TextArea("Расшифровка выбранного узла");
        detail.setWidthFull();
        detail.setHeight("200px");
        detail.setReadOnly(true);
        detail.setHelperText("Клик по узлу — полный текст (SQL, снимок сущности, кадр)");
        detail.getStyle().set("font-family", "monospace");
        detail.getStyle().set("font-size", "12px");
        detail.addAttachListener(e -> detail.getElement().executeJs(
                "const t = this.shadowRoot && this.shadowRoot.querySelector('textarea');"
                        + "if (t) { t.style.overflowY = 'auto'; t.style.overflowX = 'hidden';"
                        + " t.style.whiteSpace = 'pre-wrap'; t.style.wordBreak = 'break-word'; }"));

        tree.asSingleSelect().addValueChangeListener(e -> {
            PayloadNode selected = e.getValue();
            detail.setValue(selected == null ? "" : selected.detail());
        });

        VerticalLayout layout = new VerticalLayout(tree, detail);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setSpacing(true);
        layout.setFlexGrow(1, tree);

        if (payloadJson != null && !payloadJson.isBlank()) {
            JsonNode root;
            try {
                root = MAPPER.readTree(payloadJson);
            } catch (Exception e) {
                tree.setItems(List.of(new PayloadNode(payloadJson, payloadJson, List.of())),
                        PayloadNode::children);
                return layout;
            }
            PayloadNode rootNode = toNode(root, "operation");
            tree.setItems(List.of(rootNode), PayloadNode::children);
            tree.expand(rootNode);
        }
        return layout;
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
                String full = "SQL (" + ms + " ms):\n" + text;
                children.add(new PayloadNode(truncate(full, 400), full, List.of()));
            }
        }
        JsonNode entityData = node.path("entityData");
        if (entityData.isContainerNode()) {
            String data = entityData.toString();
            children.add(new PayloadNode(
                    truncate("entityData: " + data, 500),
                    "entityData:\n" + data, List.of()));
        }
        JsonNode childNodes = node.path("children");
        if (childNodes.isArray()) {
            for (JsonNode child : childNodes) {
                children.add(toNode(child, "frame"));
            }
        }

        String detail = nodeWithoutChildren(node);
        return new PayloadNode(label.toString(), detail, children);
    }

    /** Полный JSON кадра без ветки children (она уже развёрнута в дереве). */
    private static String nodeWithoutChildren(JsonNode node) {
        try {
            ObjectNode copy = node.deepCopy();
            copy.remove("children");
            return copy.toString();
        } catch (Exception e) {
            return node.toString();
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String fmt(double value) {
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }
}
package org.ipro.reportstudio.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JSON-кодек пакета визуальных запросов. Старый одиночный JSON читается как пакет без CTE. */
public final class VisualQueryPackageJsonCodec {
    private final ObjectMapper mapper = new ObjectMapper();
    private final VisualQueryDefinitionJsonCodec definitionCodec = new VisualQueryDefinitionJsonCodec(mapper);

    public VisualQueryPackage read(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("JSON пакета обязателен");
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("Пакет должен быть JSON-объектом");
            if (!node.has("ctes") || !node.has("main")) {
                return new VisualQueryPackage(java.util.List.of(), definitionCodec.read(json));
            }
            int version = node.path("version").asInt(VisualQueryPackage.CURRENT_VERSION);
            var ctes = new java.util.ArrayList<VisualQueryPackage.Cte>();
            for (JsonNode cte : node.path("ctes")) {
                String name = cte.path("name").asText();
                VisualQueryDefinition definition = definitionCodec.read(cte.path("definition").toString());
                VisualQueryPackage.Source source = readSource(cte.get("source"), name, definition);
                ctes.add(new VisualQueryPackage.Cte(name, source, definition));
            }
            return new VisualQueryPackage(version, ctes,
                    definitionCodec.read(node.path("main").toString()));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Некорректный JSON пакета", e);
        }
    }

    private VisualQueryPackage.Source readSource(JsonNode source, String cteName,
                                                  VisualQueryDefinition definition) {
        if (source == null || source.isNull()) {
            return new VisualQueryPackage.EntitySource(definition.entityName(), definition.entityAlias());
        }
        String type = source.path("type").asText();
        return switch (type) {
            case "entity" -> new VisualQueryPackage.EntitySource(
                    source.path("entityName").asText(), source.path("alias").asText());
            case "cte" -> new VisualQueryPackage.CteSource(
                    source.path("alias").asText(), source.path("cteName").asText());
            default -> throw new IllegalArgumentException("Неизвестный тип источника CTE: " + type);
        };
    }

    public String write(VisualQueryPackage queryPackage) {
        if (queryPackage == null) throw new IllegalArgumentException("Пакет обязателен");
        try {
            if (queryPackage.ctes().isEmpty()) return definitionCodec.write(queryPackage.main());
            ObjectNode root = mapper.createObjectNode();
            root.put("version", queryPackage.version());
            var array = root.putArray("ctes");
            for (VisualQueryPackage.Cte cte : queryPackage.ctes()) {
                ObjectNode item = array.addObject();
                item.put("name", cte.name());
                item.set("source", writeSource(cte.source()));
                item.set("definition", mapper.readTree(definitionCodec.write(cte.definition())));
            }
            root.set("main", mapper.readTree(definitionCodec.write(queryPackage.main())));
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("Не удалось сериализовать пакет", e);
        }
    }

    private JsonNode writeSource(VisualQueryPackage.Source source) {
        ObjectNode result = mapper.createObjectNode();
        if (source instanceof VisualQueryPackage.EntitySource entity) {
            result.put("type", "entity");
            result.put("entityName", entity.name());
            result.put("alias", entity.alias());
        } else if (source instanceof VisualQueryPackage.CteSource cte) {
            result.put("type", "cte");
            result.put("cteName", cte.cteName());
            result.put("alias", cte.alias());
        }
        return result;
    }
}

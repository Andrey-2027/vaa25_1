package org.ipro.reportstudio.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ipro.filter.FilterNode;
import org.ipro.filter.FilterTreeJson;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/** Явный codec визуального запроса с миграцией legacy version 1. */
public final class VisualQueryDefinitionJsonCodec {
    private final ObjectMapper mapper;
    public VisualQueryDefinitionJsonCodec() { this(configuredMapper()); }
    public VisualQueryDefinitionJsonCodec(ObjectMapper mapper) { this.mapper = mapper; }

    private static ObjectMapper configuredMapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(VisualQueryExpression.class, new JsonSerializer<>() {
            @Override public void serialize(VisualQueryExpression value, JsonGenerator gen, SerializerProvider provider) throws java.io.IOException {
                if (value instanceof VisualQueryExpression.FieldRef f) { gen.writeStartObject(); gen.writeStringField("type", "field"); gen.writeStringField("path", f.path()); gen.writeEndObject(); }
                else if (value instanceof VisualQueryExpression.ParameterRef p) { gen.writeStartObject(); gen.writeStringField("type", "parameter"); gen.writeStringField("name", p.name()); gen.writeEndObject(); }
                else if (value instanceof VisualQueryExpression.Literal l) { gen.writeStartObject(); gen.writeStringField("type", "literal"); gen.writeObjectField("value", l.value()); gen.writeEndObject(); }
                else if (value instanceof VisualQueryExpression.Binary b) { gen.writeStartObject(); gen.writeStringField("type", "binary"); gen.writeStringField("operator", b.operator().name()); gen.writeObjectField("left", b.left()); gen.writeObjectField("right", b.right()); gen.writeEndObject(); }
                else if (value instanceof VisualQueryExpression.Case c) {
                    gen.writeStartObject(); gen.writeStringField("type", "case"); gen.writeArrayFieldStart("branches");
                    for (var br : c.branches()) {
                        gen.writeStartObject();
                        gen.writeStringField("whenType", br.condition().getClass().getSimpleName());
                        if (br.condition() instanceof CaseCondition.Cmp cmp) {
                            gen.writeStringField("left", cmp.leftPath());
                            gen.writeStringField("op", cmp.op().name());
                            gen.writeNumberField("value", cmp.value());
                        } else {
                            CaseCondition.Between bt = (CaseCondition.Between) br.condition();
                            gen.writeStringField("left", bt.leftPath());
                            gen.writeNumberField("min", bt.min());
                            gen.writeNumberField("max", bt.max());
                        }
                        gen.writeStringField("thenType", br.result().getClass().getSimpleName());
                        if (br.result() instanceof VisualQueryExpression.FieldRef fr) { gen.writeStringField("thenField", fr.path()); }
                        else if (br.result() instanceof VisualQueryExpression.ParameterRef pr) { gen.writeStringField("thenParam", pr.name()); }
                        else if (br.result() instanceof VisualQueryExpression.Literal lit) { gen.writeObjectField("thenLiteral", lit.value()); }
                        gen.writeEndObject();
                    }
                    gen.writeEndArray();
                    if (c.elseResult() != null) {
                        if (c.elseResult() instanceof VisualQueryExpression.FieldRef fr) gen.writeStringField("elseField", fr.path());
                        else if (c.elseResult() instanceof VisualQueryExpression.ParameterRef pr) gen.writeStringField("elseParam", pr.name());
                        else if (c.elseResult() instanceof VisualQueryExpression.Literal lit) gen.writeObjectField("elseLiteral", lit.value());
                    }
                    gen.writeEndObject();
                }
                else { var f = (VisualQueryExpression.FunctionCall) value; gen.writeStartObject(); gen.writeStringField("type", "function"); gen.writeStringField("name", f.name()); gen.writeObjectField("arguments", f.arguments()); gen.writeEndObject(); }
            }
        });
        module.addDeserializer(VisualQueryExpression.class, new JsonDeserializer<>() {
            @Override public VisualQueryExpression deserialize(JsonParser p, DeserializationContext c) throws java.io.IOException {
                JsonNode n = p.getCodec().readTree(p); String type = n.path("type").asText();
                switch (type) {
                    case "field": return new VisualQueryExpression.FieldRef(n.path("path").asText());
                    case "parameter": return new VisualQueryExpression.ParameterRef(n.path("name").asText());
                    case "literal": return new VisualQueryExpression.Literal(unwrapScalar(n.get("value")));
                    case "binary": return new VisualQueryExpression.Binary(VisualQueryExpression.Operator.valueOf(n.path("operator").asText()),
                            n.path("left").traverse(p.getCodec()).readValueAs(VisualQueryExpression.class),
                            n.path("right").traverse(p.getCodec()).readValueAs(VisualQueryExpression.class));
                    case "case": {
                        java.util.List<CaseBranch> branches = new java.util.ArrayList<>();
                        for (JsonNode b : n.path("branches")) {
                            CaseCondition cond;
                            if (b.has("min")) cond = new CaseCondition.Between(b.path("left").asText(), b.path("min").asDouble(), b.path("max").asDouble());
                            else cond = new CaseCondition.Cmp(b.path("left").asText(), CaseCondition.CmpOp.valueOf(b.path("op").asText()), b.path("value").asDouble());
                            VisualQueryExpression result;
                            if (b.has("thenField")) result = new VisualQueryExpression.FieldRef(b.path("thenField").asText());
                            else if (b.has("thenParam")) result = new VisualQueryExpression.ParameterRef(b.path("thenParam").asText());
                            else result = new VisualQueryExpression.Literal(unwrapScalar(b.get("thenLiteral")));
                            branches.add(new CaseBranch(cond, result));
                        }
                        VisualQueryExpression elseResult = null;
                        if (n.has("elseField")) elseResult = new VisualQueryExpression.FieldRef(n.path("elseField").asText());
                        else if (n.has("elseParam")) elseResult = new VisualQueryExpression.ParameterRef(n.path("elseParam").asText());
                        else if (n.has("elseLiteral")) elseResult = new VisualQueryExpression.Literal(unwrapScalar(n.get("elseLiteral")));
                        return new VisualQueryExpression.Case(branches, elseResult);
                    }
                    case "function": return new VisualQueryExpression.FunctionCall(n.path("name").asText(),
                            java.util.stream.StreamSupport.stream(n.path("arguments").spliterator(), false)
                                    .map(x -> { try { return x.traverse(p.getCodec()).readValueAs(VisualQueryExpression.class); } catch (java.io.IOException e) { throw new IllegalArgumentException(e); } }).toList());
                    default: throw new IllegalArgumentException("Неизвестный тип AST: " + type);
                }
            }
        });
        // WHERE — полиморфное дерево FilterNode: сериализуется через общий FilterTreeJson,
        // иначе round-trip сохранённого черновика конструктора терял условия.
        module.addSerializer(FilterNode.class, new JsonSerializer<>() {
            @Override public void serialize(FilterNode value, JsonGenerator gen, SerializerProvider provider) throws java.io.IOException {
                gen.writeTree(FilterTreeJson.write(value));
            }
        });
        module.addDeserializer(FilterNode.class, new JsonDeserializer<>() {
            @Override public FilterNode deserialize(JsonParser p, DeserializationContext c) throws java.io.IOException {
                JsonNode node = p.getCodec().readTree(p);
                return node == null || node.isNull() ? null : FilterTreeJson.read(node);
            }
        });
        module.addSerializer(VisualQueryDefinition.HavingValue.class, new JsonSerializer<>() {
            @Override public void serialize(VisualQueryDefinition.HavingValue value, JsonGenerator gen, SerializerProvider provider) throws java.io.IOException {
                if (value instanceof VisualQueryDefinition.HavingNumber n) { gen.writeStartObject(); gen.writeStringField("type", "number"); gen.writeNumberField("value", n.value()); gen.writeEndObject(); }
                else if (value instanceof VisualQueryDefinition.HavingParamRef p) { gen.writeStartObject(); gen.writeStringField("type", "parameter"); gen.writeStringField("name", p.name()); gen.writeEndObject(); }
                else { var a = (VisualQueryDefinition.HavingAggregateRef) value; gen.writeStartObject(); gen.writeStringField("type", "aggregate"); gen.writeStringField("alias", a.alias()); gen.writeEndObject(); }
            }
        });
        module.addDeserializer(VisualQueryDefinition.HavingValue.class, new JsonDeserializer<>() {
            @Override public VisualQueryDefinition.HavingValue deserialize(JsonParser p, DeserializationContext c) throws java.io.IOException {
                JsonNode n = p.getCodec().readTree(p);
                String type = n.path("type").asText("");
                return switch (type) {
                    case "number" -> new VisualQueryDefinition.HavingNumber(n.path("value").asDouble());
                    case "parameter" -> new VisualQueryDefinition.HavingParamRef(n.path("name").asText());
                    case "aggregate" -> new VisualQueryDefinition.HavingAggregateRef(n.path("alias").asText());
                    default -> throw new IllegalArgumentException("Неизвестный тип значения HAVING: " + type);
                };
            }
        });
        mapper.registerModule(module); return mapper;
    }

    /** Разворачивает JsonNode в скаляр Java (String/Number/Boolean/null) для Literal. */
    private static Object unwrapScalar(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isInt() || node.isLong()) return node.asLong();
        if (node.isDouble() || node.isFloat() || node.isBigDecimal()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        return node; // сложные структуры остаются как есть
    }

    public VisualQueryDefinition read(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("JSON visual query обязателен");
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("Visual query должен быть JSON-объектом");
            int version = node.path("version").asInt(1);
            if (version == VisualQueryDefinition.LEGACY_VERSION) node = migrateV1(node);
            if (version > VisualQueryDefinition.CURRENT_VERSION) throw new IllegalArgumentException("Неподдерживаемая версия visual query: " + version);
            return mapper.treeToValue(node, VisualQueryDefinition.class);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Некорректный JSON visual query", e); }
    }

    public String write(VisualQueryDefinition definition) {
        try { return mapper.writeValueAsString(definition); }
        catch (Exception e) { throw new IllegalArgumentException("Не удалось сериализовать visual query", e); }
    }

    private JsonNode migrateV1(JsonNode source) {
        ObjectNode result = source.deepCopy(); result.put("version", VisualQueryDefinition.CURRENT_VERSION);
        JsonNode aggregates = result.get("aggregates");
        if (aggregates != null && aggregates.isArray()) {
            ArrayNode converted = mapper.createArrayNode();
            for (JsonNode item : aggregates) {
                if (item.isObject()) { converted.add(item); continue; }
                if (!item.isTextual()) throw new IllegalArgumentException("Некорректный legacy агрегат");
                String[] parts = item.asText().split(":", 3);
                if (parts.length != 3 || !parts[0].matches("(?i)SUM|AVG|MIN|MAX|COUNT|COUNT_ROWS") || !parts[1].matches("[A-Za-z_][A-Za-z0-9_.]*") || !parts[2].matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Небезопасный legacy агрегат");
                ObjectNode aggregate = mapper.createObjectNode();
                aggregate.put("function", parts[0]); aggregate.put("path", parts[1]); aggregate.put("resultName", parts[2]); converted.add(aggregate);
            }
            result.set("aggregates", converted);
        }
        JsonNode having = result.get("having");
        if (having != null && !having.isNull()) throw new IllegalArgumentException("Legacy HAVING требует ручной миграции");
        return result;
    }
}

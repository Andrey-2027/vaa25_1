package org.ip.unidr.model;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.ip.unidr.model.UniDrValidationException.Issue;

import java.util.List;
import java.util.Objects;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterTreeJson;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.ip.unidr.model.UniDrValidationException.Code.*;

/** Strict public v2 document. v1 is upgraded only in memory; this codec never writes files. */
public final class UniDrDefinitionJsonCodec {
    public static final int MAX_JSON_CHARACTERS = 128_000;
    private final ObjectMapper mapper;
    private final UniDrDefinitionValidator validator;

    public UniDrDefinitionJsonCodec(UniDrDefinitionValidator validator) {
        this.validator = Objects.requireNonNull(validator);
        var factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                .maxStringLength(MAX_JSON_CHARACTERS).maxNumberLength(80).build()).build();
        mapper = JsonMapper.builder(factory)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
        for (CoercionInputShape shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean))
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        var module=new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(FilterNode.class,new JsonSerializer<>() {
            @Override public void serialize(FilterNode value,com.fasterxml.jackson.core.JsonGenerator generator,SerializerProvider provider)throws java.io.IOException {generator.writeTree(FilterTreeJson.write(value));}
        });
        module.addDeserializer(FilterNode.class,new JsonDeserializer<>() {
            @Override public FilterNode deserialize(com.fasterxml.jackson.core.JsonParser parser,DeserializationContext context)throws java.io.IOException {
                JsonNode node=context.readTree(parser);checkFilterTree(node);return FilterTreeJson.read(node);
            }
        });mapper.registerModule(module);
    }

    public UniDrDefinition decode(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_CHARACTERS)
            throw error(INVALID_JSON, "$", "JSON пуст или превышает допустимый размер");
        try {
            JsonNode tree = mapper.readTree(json);
            if (tree == null || !tree.isObject()) throw error(INVALID_JSON, "$", "Ожидается объект настройки отчёта");
            JsonNode version = tree.get("schemaVersion");
            if (version == null || !version.isIntegralNumber() || !version.canConvertToInt())
                throw error(INVALID_JSON, "schemaVersion", "Укажите целочисленную версию формата");
            if (version.intValue()!=1 && version.intValue() != UniDrDefinition.SCHEMA_VERSION)
                throw error(UNSUPPORTED_SCHEMA, "schemaVersion", "Неподдерживаемая версия формата");
            if (!tree.has("grandTotal") || !tree.get("grandTotal").isBoolean())
                throw error(INVALID_JSON, "grandTotal", "Укажите признак общего итога");
            if(!tree.has("rowNumbers"))((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("rowNumbers",false);
            if (tree.has("appearance")) {
                JsonNode appearance = tree.get("appearance");
                if (!appearance.isObject()) throw error(INVALID_JSON, "appearance", "Ожидаются настройки оформления");
                for (String flag : List.of("rowBorders", "columnBorders", "stripeRows"))
                    if (!appearance.has(flag) || !appearance.get(flag).isBoolean())
                        throw error(INVALID_JSON, "appearance." + flag, "Укажите логический признак оформления");
                for(String key:List.of("pageSize","orientation"))
                    if(appearance.has(key) && !appearance.get(key).isTextual())throw error(INVALID_JSON,"appearance."+key,"Укажите значение настройки страницы");
                if(appearance.has("fontSize") && (!appearance.get("fontSize").isIntegralNumber() || !appearance.get("fontSize").canConvertToInt()
                    || appearance.get("fontSize").intValue()<6 || appearance.get("fontSize").intValue()>24))
                    throw error(INVALID_JSON,"appearance.fontSize","Размер шрифта должен быть целым числом от 6 до 24");
                for(String key:List.of("reportHeader","pageHeader","pageFooter","reportFooter"))
                    if(appearance.hasNonNull(key) && (!appearance.get(key).isTextual() || appearance.get(key).textValue().length()>4000))
                        throw error(INVALID_JSON,"appearance."+key,"Ожидается текст длиной до 4000 символов");
            }
            UniDrDefinition definition;
            if(version.intValue()==1) {
                if(tree.path("query").has("tableEntity"))throw error(INVALID_JSON,"query","Неизвестное свойство источника v1");
                var old=mapper.treeToValue(tree,LegacyDefinition.class);
                definition=new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,old.sourceId(),old.sourceVersion(),old.title(),old.columns(),old.measures(),old.groups(),old.filters(),old.parameters(),old.sorts(),
                    old.mode(),old.grandTotal(),old.appearance(),old.query(),old.fieldStyles(),old.computedColumns(),old.rowNumbers());
                validator.requireValid(definition);
            } else {
                if(tree.has("parameterDeclarations"))throw error(INVALID_JSON,"$","Недопустимое внутреннее свойство определения");
                var expanded=((ObjectNode)tree).deepCopy();
                if(expanded.has("filters"))throw error(INVALID_JSON,"filters","Используйте дерево условий или совместимые отборы");
                var compatible=expanded.remove("compatibilityFilters");
                if(compatible==null || !compatible.isArray())throw error(INVALID_JSON,"compatibilityFilters","Укажите совместимые отборы");
                expanded.set("filters",compatible);
                JsonNode declarations=expanded.remove("parameters");
                if(declarations==null || !declarations.isArray())throw error(INVALID_JSON,"parameters","Укажите список параметров");
                expanded.set("parameterDeclarations",declarations);expanded.putArray("parameters");
                if(expanded.hasNonNull("query")) {
                    var query=expanded.get("query");
                    if(!query.isObject() || query.has("parameters"))throw error(INVALID_JSON,"query","Параметры объявляются в общем списке отчёта");
                    ((ObjectNode)query).putArray("parameters");
                }
                definition=mapper.treeToValue(expanded,UniDrDefinition.class);
            }
            definition=validator.describeParameters(definition);
            return validator.requireValid(definition);
        } catch(UniDrValidationException known){throw known;
        } catch (JsonProcessingException invalid) {
            // Do not expose Jackson input excerpts/class names to the UI.
            throw error(INVALID_JSON, "$", "Некорректный JSON: проверьте поля, типы и структуру настройки");
        } catch(IllegalArgumentException | NullPointerException invalid) {
            throw error(INVALID_JSON,"$","Некорректный JSON: проверьте поля, типы и структуру настройки");
        }
    }

    public String encode(UniDrDefinition definition) {
        validator.requireValid(definition);definition=validator.requireValid(validator.describeParameters(definition));
        try {
            ObjectNode tree=mapper.valueToTree(definition);
            tree.set("parameters",tree.remove("parameterDeclarations"));
            tree.set("compatibilityFilters",tree.remove("filters"));
            if(tree.hasNonNull("query"))((ObjectNode)tree.get("query")).remove("parameters");
            String json = mapper.writeValueAsString(tree);
            if (json.length() > MAX_JSON_CHARACTERS) throw error(INVALID_JSON, "$", "Настройка превышает допустимый размер");
            return json;
        } catch (JsonProcessingException invalid) {
            throw error(INVALID_JSON, "$", "Не удалось преобразовать настройку в JSON");
        }
    }
    private record LegacyDefinition(int schemaVersion,String sourceId,int sourceVersion,String title,List<UniDrDefinition.Column> columns,
        List<UniDrDefinition.Measure> measures,List<String> groups,List<UniDrDefinition.Filter> filters,List<UniDrDefinition.Parameter> parameters,
        List<UniDrDefinition.Sort> sorts,UniDrDefinition.Mode mode,boolean grandTotal,UniDrDefinition.Appearance appearance,UniDrQuerySource query,
        java.util.Map<String,UniDrDefinition.FieldStyle> fieldStyles,List<UniDrDefinition.ComputedColumn> computedColumns,boolean rowNumbers) {}

    private static void checkFilterTree(JsonNode tree) {
        if(tree==null || !tree.isObject())throw new IllegalArgumentException("Некорректное дерево условий");
        if(tree.has("condition")) {
            if(tree.size()!=1 || !tree.get("condition").isObject())throw new IllegalArgumentException("Некорректное условие");
            var condition=tree.get("condition");var allowed=java.util.Set.of("path","operator","value","valueTo","dataType","values");
            condition.fieldNames().forEachRemaining(name->{if(!allowed.contains(name))throw new IllegalArgumentException("Неизвестное свойство условия");});
            for(String name:List.of("path","operator","dataType"))if(!condition.path(name).isTextual())throw new IllegalArgumentException("Некорректный тип условия");
            for(String name:List.of("value","valueTo"))if(condition.hasNonNull(name) && !condition.get(name).isTextual())throw new IllegalArgumentException("Некорректное значение условия");
            if(condition.hasNonNull("values")) {
                if(!condition.get("values").isArray())throw new IllegalArgumentException("Некорректный список значений");
                condition.get("values").forEach(value->{if(!value.isValueNode() || value.isNull())throw new IllegalArgumentException("Недопустимое значение условия");});
            }
        } else {
            if(tree.size()!=2 || !tree.path("operator").isTextual() || !tree.path("children").isArray())throw new IllegalArgumentException("Некорректная группа условий");
            tree.get("children").forEach(UniDrDefinitionJsonCodec::checkFilterTree);
        }
    }

    private UniDrValidationException error(UniDrValidationException.Code code, String path, String message) {
        return new UniDrValidationException(List.of(new Issue(code, path, message)));
    }
}

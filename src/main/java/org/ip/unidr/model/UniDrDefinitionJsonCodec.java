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

import static org.ip.unidr.model.UniDrValidationException.Code.*;

/** Strict, bounded v1 codec; never silently migrates or discards unsupported settings. */
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
            if (version.intValue() != UniDrDefinition.SCHEMA_VERSION)
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
            }
            return validator.requireValid(mapper.treeToValue(tree, UniDrDefinition.class));
        } catch (JsonProcessingException invalid) {
            // Do not expose Jackson input excerpts/class names to the UI.
            throw error(INVALID_JSON, "$", "Некорректный JSON: проверьте поля, типы и структуру настройки");
        }
    }

    public String encode(UniDrDefinition definition) {
        validator.requireValid(definition);
        try {
            String json = mapper.writeValueAsString(definition);
            if (json.length() > MAX_JSON_CHARACTERS) throw error(INVALID_JSON, "$", "Настройка превышает допустимый размер");
            return json;
        } catch (JsonProcessingException invalid) {
            throw error(INVALID_JSON, "$", "Не удалось преобразовать настройку в JSON");
        }
    }

    private UniDrValidationException error(UniDrValidationException.Code code, String path, String message) {
        return new UniDrValidationException(List.of(new Issue(code, path, message)));
    }
}

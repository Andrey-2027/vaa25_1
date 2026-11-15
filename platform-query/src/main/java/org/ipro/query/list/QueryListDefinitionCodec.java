package org.ipro.query.list;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.metadata.GridViewState;
import org.ipro.query.model.QueryDefinition;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

final class QueryListDefinitionCodec {
    private static final int SCHEMA_VERSION = 1;
    private static final Set<String> FIELDS =
            Set.of("schemaVersion", "query", "standardView", "mode", "commandObject", "commands", "provenance");
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    String encode(QueryListDefinition value) {
        var json = mapper.createObjectNode();
        json.put("schemaVersion", SCHEMA_VERSION);
        json.set("query", mapper.valueToTree(value.query()));
        try {
            json.set("standardView", mapper.readTree(value.standardView().toJson()));
            json.put("mode", value.mode().name());
            json.set("commandObject", mapper.valueToTree(value.commandObject()));
            json.set("commands", mapper.valueToTree(value.commands().stream().sorted().toList()));
            json.put("provenance", value.provenance().name());
            return mapper.writeValueAsString(json);
        } catch (IOException error) {
            throw new IllegalArgumentException("Не удалось сохранить определение списка", error);
        }
    }

    QueryListDefinition decode(String key, String title, String value) {
        try {
            var json = mapper.readTree(value);
            if (json == null || !json.isObject()) {
                throw new IllegalArgumentException("Определение списка должно быть объектом JSON");
            }
            var fields = new HashSet<String>();
            json.fieldNames().forEachRemaining(fields::add);
            var version = json.path("schemaVersion");
            if (!fields.equals(FIELDS) || !version.isIntegralNumber() || !version.canConvertToInt()
                    || version.intValue() != SCHEMA_VERSION || !json.path("standardView").isObject()
                    || !json.path("query").isObject() || !json.path("commands").isArray()) {
                throw new IllegalArgumentException("Некорректная структура или версия определения списка");
            }
            var commands = new LinkedHashSet<QueryListDefinition.Command>();
            for (JsonNode command : json.path("commands")) {
                commands.add(QueryListDefinition.Command.valueOf(command.asText()));
            }
            return new QueryListDefinition(key, title, mapper.treeToValue(json.get("query"), QueryDefinition.class),
                    GridViewState.fromJson(json.path("standardView").toString()),
                    ProjectionExecutionMode.valueOf(json.path("mode").asText()),
                    mapper.treeToValue(json.get("commandObject"), QueryListDefinition.CommandObject.class),
                    commands, QueryProvenance.valueOf(json.path("provenance").asText()));
        } catch (IOException | IllegalStateException | IllegalArgumentException error) {
            throw new IllegalArgumentException("Некорректный JSON определения списка", error);
        }
    }
}

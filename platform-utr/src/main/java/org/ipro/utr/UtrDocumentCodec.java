package org.ipro.utr;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.metadata.GridViewState;
import org.ipro.query.model.QueryDefinition;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Формат JSON v1 для файлов хранилища и переноса отчётов. */
final class UtrDocumentCodec {
    static final int MAX_BYTES = 600_000;
    private static final Set<String> FILE_FIELDS =
            Set.of("fileVersion", "id", "author", "description", "visibility", "document");
    private static final Set<String> DOCUMENT_FIELDS = Set.of("schemaVersion", "title", "query", "layout", "mode");
    private static final Set<String> QUERY_FIELDS =
            Set.of("jpql", "source", "visualQueryJson", "visualFilterJson", "parameters", "limits");
    private static final Set<String> LAYOUT_FIELDS = Set.of("columns", "filters", "userFilter", "options");
    private static final Set<String> OPTIONS =
            Set.of("query.sort", "query.group", "query.groupAgg", "query.totals", "query.hidden");

    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(40).maxStringLength(128_000).build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    record File(long id, String author, String description, UtrVisibility visibility, UtrDocument document) {}

    byte[] encode(File file) {
        try {
            var root = mapper.createObjectNode();
            root.put("fileVersion", 1);
            root.put("id", file.id());
            root.put("author", file.author());
            root.put("description", file.description());
            root.put("visibility", file.visibility().name());
            var document = root.putObject("document");
            document.put("schemaVersion", 1);
            document.put("title", file.document().title());
            document.set("query", mapper.valueToTree(file.document().query()));
            document.set("layout", mapper.readTree(file.document().layout().toJson()));
            document.put("mode", file.document().mode().name());
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            decode(bytes);
            return bytes;
        } catch (IOException | IllegalArgumentException | IllegalStateException invalid) {
            throw invalid(invalid);
        }
    }

    File decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw new UtrStoreException(UtrStoreException.Code.INVALID_FILE, "Файл отчёта превышает допустимый размер");
        }
        try {
            var root = mapper.readTree(bytes);
            requireFields(root, FILE_FIELDS);
            requireVersion(root.path("fileVersion"));
            var id = root.path("id");
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() < 1) throw invalid(null);
            var author = text(root, "author", 200, false);
            var description = text(root, "description", 1000, true);
            var visibility = UtrVisibility.valueOf(text(root, "visibility", 20, false));
            var document = root.path("document");
            requireFields(document, DOCUMENT_FIELDS);
            requireVersion(document.path("schemaVersion"));
            var title = text(document, "title", 200, false);
            var queryNode = document.path("query");
            requireFields(queryNode, QUERY_FIELDS);
            if (!queryNode.path("parameters").isArray() || !queryNode.path("limits").isObject()) throw invalid(null);
            var query = mapper.treeToValue(queryNode, QueryDefinition.class);
            var layout = decodeLayout(document.path("layout"));
            var mode = ProjectionExecutionMode.valueOf(text(document, "mode", 20, false));
            return new File(id.longValue(), author, description, visibility, new UtrDocument(title, query, layout, mode));
        } catch (IOException | IllegalArgumentException | IllegalStateException | NullPointerException invalid) {
            throw invalid(invalid);
        }
    }

    private static GridViewState decodeLayout(JsonNode layout) {
        if (!layout.isObject() || !LAYOUT_FIELDS.containsAll(fields(layout)) || !layout.path("columns").isArray()) {
            throw invalid(null);
        }
        for (var column : layout.path("columns")) {
            if (!column.isObject() || !column.path("path").isTextual() || column.get("path").textValue().isBlank()) {
                throw invalid(null);
            }
        }
        if (layout.has("filters") && (!layout.path("filters").isArray() || !layout.path("filters").isEmpty())) {
            throw invalid(null);
        }
        if (layout.has("options")) {
            var options = layout.path("options");
            if (!options.isObject() || !OPTIONS.containsAll(fields(options))) throw invalid(null);
            for (var value : options) {
                if (!value.isTextual()) throw invalid(null);
            }
        }
        if (layout.has("userFilter")) requireFilter(layout.get("userFilter"));
        return GridViewState.fromJson(layout.toString());
    }

    private static void requireFilter(JsonNode filter) {
        if (!filter.isObject()) throw invalid(null);
        if (filter.has("condition")) {
            requireFields(filter, Set.of("condition"));
            if (!filter.get("condition").isObject()) throw invalid(null);
        } else {
            requireFields(filter, Set.of("operator", "children"));
            if (!filter.path("operator").isTextual() || !filter.path("children").isArray()) throw invalid(null);
            filter.path("children").forEach(UtrDocumentCodec::requireFilter);
        }
    }

    private static void requireFields(JsonNode object, Set<String> expected) {
        if (object == null || !object.isObject() || !fields(object).equals(expected)) throw invalid(null);
    }

    private static Set<String> fields(JsonNode object) {
        var names = new HashSet<String>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void requireVersion(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() != 1) throw invalid(null);
    }

    private static String text(JsonNode object, String field, int limit, boolean allowBlank) {
        var value = object.path(field);
        if (!value.isTextual() || value.textValue().length() > limit || (!allowBlank && value.textValue().isBlank())) {
            throw invalid(null);
        }
        return value.textValue();
    }

    private static UtrStoreException invalid(Throwable cause) {
        return new UtrStoreException(UtrStoreException.Code.INVALID_FILE, "Некорректная структура или версия файла UTR", cause);
    }
}

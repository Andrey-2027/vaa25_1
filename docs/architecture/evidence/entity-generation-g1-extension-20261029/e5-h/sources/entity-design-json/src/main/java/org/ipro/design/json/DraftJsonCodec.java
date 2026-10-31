package org.ipro.design.json;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import org.ipro.design.EntityDraft;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RecipeRef;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Strict, bounded JSON v1/v2. No polymorphism, services, class loading or file paths. */
public final class DraftJsonCodec {
    public static final int MAX_BYTES = 1_048_576, MAX_DEPTH = 32, MAX_FIELDS = 1000;
    private final ObjectMapper mapper;
    private final JsonNode schema;
    private final JsonNode catalogV2Schema;
    private final JsonNode documentSchema;
    private final JsonNode ownerSchema;
    private final JsonNode ownerDocumentSchema;

    public DraftJsonCodec() {
        var factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH).maxStringLength(8192).maxNumberLength(16).build()).build();
        mapper = new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try (var in = DraftJsonCodec.class.getResourceAsStream("/org/ipro/design/schema/entity-draft-v1.schema.json")) {
            schema = mapper.readTree(Objects.requireNonNull(in, "Packaged draft schema missing"));
        } catch (IOException e) { throw new IllegalStateException("Cannot read packaged draft schema", e); }
        // The captured schema remains byte-for-byte unchanged. Same envelope format,
        // different explicitly registered recipe; never reinterpret an old reference.
        catalogV2Schema=recipeSchema(schema, RecipeCatalog.STANDARD_CATALOG_V2);
        documentSchema=recipeSchema(schema, RecipeCatalog.STANDARD_DOCUMENT);
        try (var in = DraftJsonCodec.class.getResourceAsStream("/org/ipro/design/schema/entity-draft-v2.schema.json")) {
            ownerSchema = mapper.readTree(Objects.requireNonNull(in, "Packaged owner schema missing"));
        } catch (IOException e) { throw new IllegalStateException("Cannot read packaged owner schema", e); }
        ownerDocumentSchema=recipeSchema(ownerSchema, RecipeCatalog.STANDARD_DOCUMENT);
    }

    private JsonNode recipeSchema(JsonNode source, RecipeRef ref) {
        var copy = source.deepCopy();
        var rule = (com.fasterxml.jackson.databind.node.ObjectNode) copy.at("/properties/recipe/properties");
        ((com.fasterxml.jackson.databind.node.ObjectNode) rule.get("id")).put("const", ref.id());
        ((com.fasterxml.jackson.databind.node.ObjectNode) rule.get("version")).put("const", ref.version());
        return copy;
    }

    public EntityDraft decode(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        return decode(bytes);
    }
    public EntityDraft decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw invalid("Файл превышает 1 MiB");
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return decodeText(json);
        } catch (CharacterCodingException e) { throw invalid("Ожидается корректный UTF-8"); }
    }
    public EntityDraft decode(String json) {
        if (json.length() > MAX_BYTES) throw invalid("Файл превышает 1 MiB");
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(json));
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes); return decode(bytes);
        } catch (CharacterCodingException e) { throw invalid("Ожидается корректный Unicode"); }
    }
    private EntityDraft decodeText(String json) {
        try {
            JsonNode tree = mapper.readTree(json);
            if (tree == null) throw invalid("Пустой JSON");
            envelope(tree);
            return mapper.treeToValue(tree, EntityDraft.class);
        } catch (DraftJsonException e) { throw e; }
        catch (IOException | IllegalArgumentException e) { throw invalid("Некорректный JSON или неподдержанный формат черновика"); }
    }
    /** Deterministic for one immutable revision, including semantically invalid input. */
    public byte[] encode(EntityDraft draft) {
        try {
            JsonNode tree = mapper.valueToTree(draft);
            if (draft.schemaVersion() == 1) {
                if (draft.owner() != null) throw invalid("schemaVersion=1 не поддерживает owner");
                ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("owner");
            }
            envelope(tree);
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(tree);
            if (bytes.length > MAX_BYTES) throw invalid("Черновик превышает 1 MiB");
            return bytes;
        } catch (DraftJsonException e) { throw e; }
        catch (IOException | IllegalArgumentException e) { throw invalid("Не удалось выгрузить формат черновика"); }
    }
    private void envelope(JsonNode tree) {
        var recipe=tree.path("recipe");
        boolean v2=recipe.path("id").isTextual() && recipe.path("version").isIntegralNumber()
            && recipe.path("version").canConvertToInt()
            && new RecipeRef(recipe.path("id").textValue(),recipe.path("version").intValue()).equals(RecipeCatalog.STANDARD_CATALOG_V2);
        boolean document=recipe.path("id").isTextual() && recipe.path("version").isIntegralNumber()
            && recipe.path("version").canConvertToInt()
            && new RecipeRef(recipe.path("id").textValue(),recipe.path("version").intValue()).equals(RecipeCatalog.STANDARD_DOCUMENT);
        boolean ownerEnvelope = tree.path("schemaVersion").isIntegralNumber() && tree.path("schemaVersion").intValue() == 2;
        if (ownerEnvelope && !document && !v2) throw invalid("Неподдержанный рецепт envelope v2");
        JsonNode selected = ownerEnvelope ? (document ? ownerDocumentSchema : ownerSchema)
                : document ? documentSchema : v2 ? catalogV2Schema : schema;
        check(tree, selected, "$", selected);
        Set<String> ids = new HashSet<>(); ids.add(tree.get("draftId").textValue());
        int count = tree.get("fields").size();
        for (var field : tree.get("fields")) unique(ids, field.get("id"));
        for (var section : tree.get("sections")) {
            unique(ids, section.get("id")); count += section.get("fields").size();
            for (var field : section.get("fields")) unique(ids, field.get("id"));
        }
        if (count > MAX_FIELDS) throw invalid("Не более 1000 реквизитов суммарно");
    }
    private void unique(Set<String> ids, JsonNode id) {
        if (!ids.add(id.textValue())) throw invalid("Повторяющийся UUID в документе");
    }
    /* Interpreter of the bounded keywords used by the packaged v1 envelope, not a public JSON Schema engine. */
    private void check(JsonNode value, JsonNode rule, String path, JsonNode rootSchema) {
        if (rule.has("$ref")) { check(value, rootSchema.at(rule.get("$ref").textValue().substring(1)), path, rootSchema); return; }
        if (rule.has("anyOf")) {
            for (var alternative : rule.get("anyOf")) {
                try { check(value, alternative, path, rootSchema); return; } catch (DraftJsonException ignored) { }
            }
            throw invalid(path + ": неверный тип значения");
        }
        if (rule.has("const") && !rule.get("const").equals(value)) throw invalid(path + ": неподдержанное значение или версия");
        if (rule.has("enum")) {
            boolean found = false; for (var option : rule.get("enum")) found |= option.equals(value);
            if (!found) throw invalid(path + ": неподдержанное значение");
        }
        if (rule.has("type")) {
            boolean valid = switch (rule.get("type").textValue()) {
                case "object" -> value.isObject(); case "array" -> value.isArray();
                case "string" -> value.isTextual(); case "boolean" -> value.isBoolean(); case "null" -> value.isNull();
                default -> throw new IllegalStateException("Unsupported packaged schema type");
            };
            if (!valid) throw invalid(path + ": неверный тип значения");
        }
        if (value.isObject()) {
            for (var required : rule.path("required")) if (!value.has(required.textValue())) throw invalid(path + ": отсутствует " + required.textValue());
            if (rule.has("properties")) {
                value.fields().forEachRemaining(entry -> {
                    var property = rule.get("properties").get(entry.getKey());
                    if (property == null) {
                        if (rule.path("additionalProperties").isBoolean() && !rule.get("additionalProperties").booleanValue()) throw invalid(path + ": неизвестное свойство");
                    } else check(entry.getValue(), property, path + "." + entry.getKey(), rootSchema);
                });
            }
        }
        if (value.isArray()) {
            if (rule.has("maxItems") && value.size() > rule.get("maxItems").intValue()) throw invalid(path + ": слишком много элементов");
            if (rule.has("items")) for (var item : value) check(item, rule.get("items"), path + "[]", rootSchema);
        }
        if (value.isTextual()) {
            String text = value.textValue();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid(path + ": неверный Unicode");
                } else if (Character.isLowSurrogate(c)) throw invalid(path + ": неверный Unicode");
            }
            if (rule.has("maxLength") && text.codePointCount(0, text.length()) > rule.get("maxLength").intValue()) throw invalid(path + ": слишком длинный текст");
            if (rule.has("pattern") && !text.matches(rule.get("pattern").textValue())) throw invalid(path + ": неверный UUID");
        }
        if (rule.has("if")) {
            boolean matches;
            try { check(value, rule.get("if"), path, rootSchema); matches = true; } catch (DraftJsonException e) { matches = false; }
            if (matches) check(value, rule.get("then"), path, rootSchema);
        }
    }
    private static DraftJsonException invalid(String message) { return new DraftJsonException(message); }
}

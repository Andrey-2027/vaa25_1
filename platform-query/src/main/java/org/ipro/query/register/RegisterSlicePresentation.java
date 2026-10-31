package org.ipro.query.register;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ipro.query.VisualQueryPackageJsonCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/** Optional display text, valid only for the exact saved AST. Never used for execution. */
final class RegisterSlicePresentation {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = "slicePresentation";

    static String attach(String json, String text) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(json);
            ObjectNode presentation = root.putObject(KEY);
            presentation.put("version", 1);
            presentation.put("packageDigest", digest(json));
            presentation.put("text", text);
            return MAPPER.writeValueAsString(root);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Не удалось сохранить представление среза", failure);
        }
    }

    static Optional<String> read(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            var presentation = MAPPER.readTree(json).path(KEY);
            if (presentation.path("version").asInt() != 1
                    || !digest(json).equals(presentation.path("packageDigest").asText())) return Optional.empty();
            String text = presentation.path("text").asText();
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (Exception failure) {
            return Optional.empty();
        }
    }

    static String retain(String originalJson, String editedJson) {
        var text = read(originalJson);
        if (text.isEmpty()) return editedJson;
        try {
            return digest(originalJson).equals(digest(editedJson))
                ? attach(editedJson, text.get()) : editedJson;
        } catch (Exception failure) {
            return editedJson;
        }
    }

    private static String digest(String json) throws Exception {
        var codec = new VisualQueryPackageJsonCodec();
        String canonical = codec.write(codec.read(json));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }
}

package org.ipro.design.apply;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Strict JSON reader/writer used only by the D integration tooling. */
final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Json() {
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static String write(Object value) throws java.io.IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
    }
}

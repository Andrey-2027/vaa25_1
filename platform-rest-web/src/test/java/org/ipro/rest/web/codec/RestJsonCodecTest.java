package org.ipro.rest.web.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.ipro.rest.service.RestDetailResult;
import org.ipro.rest.service.RestPageResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F-REST-READ-4 §6.3: wire nullability, exact numbers and complete-before-send serialization. */
class RestJsonCodecTest {

    /** Application-style mapper whose global inclusion would drop nulls. */
    private static final JsonMapper NON_NULL_MAPPER = JsonMapper.builder()
        .changeDefaultPropertyInclusion(inclusion -> inclusion
            .withValueInclusion(JsonInclude.Include.NON_NULL)
            .withContentInclusion(JsonInclude.Include.NON_NULL))
        .build();

    @Test
    void selectedNullableFieldsStayPresentRegardlessOfGlobalInclusion() {
        RestJsonCodec codec = new RestJsonCodec(NON_NULL_MAPPER);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1001L);
        row.put("codeSpec", "SP-001");
        row.put("draft", null);

        String json = text(codec.writeList(new RestPageResult(List.of(row), 0, 50, 2, 1)));

        assertThat(json).contains("\"draft\":null");
        assertThat(json).contains("\"content\"", "\"page\":0", "\"size\":50",
            "\"totalElements\":2", "\"totalPages\":1");
    }

    @Test
    void detailEnvelopeKeepsSelectedNullableFields() {
        RestJsonCodec codec = new RestJsonCodec(NON_NULL_MAPPER);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", 1001L);
        data.put("codeSpec", "SP-001");
        data.put("draft", null);
        data.put("comment", null);

        String json = text(codec.writeDetail(new RestDetailResult(data)));

        assertThat(json).contains("\"data\"");
        assertThat(json).contains("\"draft\":null", "\"comment\":null");
    }

    @Test
    void int64AndDecimalValuesAreSerializedAsExactNumbers() {
        RestJsonCodec codec = new RestJsonCodec(NON_NULL_MAPPER);
        long beyondDouble = 9_007_199_254_740_993L;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", beyondDouble);
        data.put("amount", new BigDecimal("1.20"));

        String json = text(codec.writeDetail(new RestDetailResult(data)));

        assertThat(json).contains("\"id\":9007199254740993");
        assertThat(json).contains("\"amount\":1.20");
        assertThat(json).doesNotContain("\"9007199254740993\"", "\"1.20\"");
    }

    @Test
    void serializationFailureProducesNoPartialJson() {
        RestJsonCodec codec = new RestJsonCodec(JsonMapper.builder().build());
        Map<String, Object> selfReferencing = new LinkedHashMap<>();
        selfReferencing.put("self", selfReferencing);

        assertThatThrownBy(() -> codec.writeProblem(selfReferencing))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to serialize");
    }

    private static String text(byte[] json) {
        return new String(json, StandardCharsets.UTF_8);
    }
}

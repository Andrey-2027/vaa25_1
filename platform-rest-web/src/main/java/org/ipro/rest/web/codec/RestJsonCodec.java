package org.ipro.rest.web.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.ipro.rest.service.RestDetailResult;
import org.ipro.rest.service.RestPageResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Response writer of the v1 wire contract (F-REST-READ-4 §6.3).
 *
 * <p>It owns a private copy of the managed mapper with forced ALWAYS value/content inclusion, so a
 * selected nullable field is present as JSON {@code null} regardless of the application's global
 * NON_NULL setting. Payloads are platform DTOs without Jackson annotations: the codec builds the
 * envelope itself. Bytes are produced completely before the response is returned, so a
 * serialization failure never emits a partial successful JSON document.</p>
 */
public final class RestJsonCodec {

    private final JsonMapper mapper;

    public RestJsonCodec(JsonMapper managedMapper) {
        Objects.requireNonNull(managedMapper, "managedMapper must not be null");
        this.mapper = managedMapper.rebuild()
            .changeDefaultPropertyInclusion(inclusion -> inclusion
                .withValueInclusion(JsonInclude.Include.ALWAYS)
                .withContentInclusion(JsonInclude.Include.ALWAYS))
            .build();
    }

    /** LIST envelope: content plus effective paging; total counts are never clamped to the window. */
    public byte[] writeList(RestPageResult result) {
        Objects.requireNonNull(result, "result must not be null");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", result.content());
        payload.put("page", result.page());
        payload.put("size", result.size());
        payload.put("totalElements", result.totalElements());
        payload.put("totalPages", result.totalPages());
        return write(payload);
    }

    /** DETAIL envelope: the projected scalar map behind {@code data}. */
    public byte[] writeDetail(RestDetailResult result) {
        Objects.requireNonNull(result, "result must not be null");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", result.data());
        return write(payload);
    }

    /** Problem Details body; the caller owns status, content type and extensions. */
    public byte[] writeProblem(Map<String, Object> problem) {
        Objects.requireNonNull(problem, "problem must not be null");
        return write(problem);
    }

    private byte[] write(Object payload) {
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Failed to serialize the REST response", failure);
        }
    }
}

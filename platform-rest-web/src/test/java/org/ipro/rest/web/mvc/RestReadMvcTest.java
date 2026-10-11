package org.ipro.rest.web.mvc;

import org.ipro.rest.gateway.RestDetailContract;
import org.ipro.rest.gateway.RestDetailRequestFactory;
import org.ipro.rest.gateway.RestFieldFact;
import org.ipro.rest.gateway.RestFilterFact;
import org.ipro.rest.gateway.RestListContract;
import org.ipro.rest.gateway.RestListRequestFactory;
import org.ipro.rest.gateway.RestReadGateway;
import org.ipro.rest.service.RestDetailQuery;
import org.ipro.rest.service.RestDetailResult;
import org.ipro.rest.service.RestListQuery;
import org.ipro.rest.service.RestPageResult;
import org.ipro.rest.service.RestReadException;
import org.ipro.rest.service.RestReadOutcome;
import org.ipro.rest.web.codec.RestJsonCodec;
import org.ipro.rest.web.problem.RestProblemWriter;
import org.ipro.rest.web.problem.RestReadExceptionHandler;
import org.ipro.rest.web.query.RestQueryParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ipro.rest.api.RestFieldFormat.NONE;
import static org.ipro.rest.api.RestFieldType.LONG;
import static org.ipro.rest.api.RestFieldType.STRING;
import static org.ipro.rest.api.RestFilterOperator.EQUALS;
import static org.ipro.rest.api.RestNullability.NOT_NULL;
import static org.ipro.rest.api.RestNullability.NULLABLE;
import static org.ipro.rest.api.RestSortDirection.ASC;
import static org.ipro.rest.api.RestSortDirection.DESC;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-REST-READ-4 §5.3/§7: MVC slice over a substituted gateway. Parsing runs inside the gateway
 * callback, so a denied subject sees 403/401 before any 400, exactly as the real gate order
 * requires.
 */
class RestReadMvcTest {

    private static final RestFieldFact ID = new RestFieldFact("id", LONG, NONE, NOT_NULL);
    private static final RestFieldFact CODE_SPEC = new RestFieldFact("codeSpec", STRING, NONE, NOT_NULL);
    private static final RestFieldFact DRAFT = new RestFieldFact("draft", STRING, NONE, NULLABLE);

    private static final RestListContract LIST = new RestListContract(
        "specifications", 1,
        List.of(ID, CODE_SPEC, DRAFT),
        List.of(ID, CODE_SPEC),
        Map.of("journalId", new RestFilterFact("journalId", LONG, EQUALS)),
        List.of("id", "codeSpec"),
        "id", ASC, 50, 200, 10_000);

    private static final RestDetailContract DETAIL = new RestDetailContract(
        "specifications", 1,
        List.of(ID, CODE_SPEC, DRAFT),
        List.of(ID, CODE_SPEC, DRAFT),
        ID);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RestReadGateway gateway;
    private MockMvc mvc;
    private RestListQuery parsedListQuery;
    private RestDetailQuery parsedDetailQuery;

    @BeforeEach
    void setUp() {
        gateway = mock(RestReadGateway.class);
        RestJsonCodec codec = new RestJsonCodec(JSON);
        RestProblemWriter writer = new RestProblemWriter(codec);
        mvc = MockMvcBuilders
            .standaloneSetup(new RestReadController(gateway, new RestQueryParser(), codec))
            .setControllerAdvice(new RestReadExceptionHandler(writer))
            .build();
    }

    @Test
    void listReturnsVersionedEnvelopeWithExplicitNulls() throws Exception {
        stubList();

        MvcResult result = mvc.perform(get("/api/v1/specifications"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentType()).isEqualTo("application/json");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"draft\":null");
        Map<String, Object> json = json(result);
        assertThat(json).containsEntry("page", 0).containsEntry("size", 50).containsEntry("totalElements", 2);
        assertThat(json).containsKey("totalPages");
    }

    @Test
    void listParsesQueryOnlyInsideTheGatewayCallback() throws Exception {
        stubList();

        mvc.perform(get("/api/v1/specifications")
                .param("fields", "codeSpec")
                .param("page", "1")
                .param("size", "2")
                .param("sort", "codeSpec,desc")
                .param("journalId", "42"))
            .andExpect(status().isOk());

        assertThat(parsedListQuery.fields()).containsExactly("codeSpec");
        assertThat(parsedListQuery.page()).isEqualTo(1);
        assertThat(parsedListQuery.size()).isEqualTo(2);
        assertThat(parsedListQuery.sortBy()).isEqualTo("codeSpec");
        assertThat(parsedListQuery.sortDirection()).isEqualTo(DESC);
        assertThat(parsedListQuery.filters()).containsEntry("journalId", 42L);
    }

    @Test
    void detailReturnsDataEnvelopeWithParsedTypedId() throws Exception {
        when(gateway.detail(eq("specifications"), eq(1), any(RestDetailRequestFactory.class)))
            .thenAnswer(invocation -> {
                parsedDetailQuery = ((RestDetailRequestFactory) invocation.getArgument(2)).create(DETAIL);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", 1001L);
                data.put("codeSpec", "SP-001");
                data.put("draft", null);
                return new RestDetailResult(data);
            });

        MvcResult result = mvc.perform(get("/api/v1/specifications/1001").param("fields", "codeSpec"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(parsedDetailQuery.id()).isEqualTo(1001L);
        assertThat(parsedDetailQuery.fields()).containsExactly("codeSpec");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .contains("\"data\"", "\"draft\":null");
    }

    @Test
    void anonymousRequestIsRejectedBeforeQueryParsing() throws Exception {
        when(gateway.list(any(), anyInt(), any(RestListRequestFactory.class)))
            .thenThrow(new RestReadException(RestReadOutcome.UNAUTHENTICATED, "no subject"));

        MvcResult result = mvc.perform(get("/api/v1/specifications")
                .param("size", "abc")
                .param("foo", "bar"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", "Basic realm=\"rest-read\""))
            .andReturn();

        assertThat(result.getResponse().getContentType()).isEqualTo("application/problem+json");
        assertThat(json(result)).containsEntry("code", "unauthenticated").containsEntry("status", 401);
    }

    @Test
    void forbiddenPrecedesMalformedQuery() throws Exception {
        when(gateway.list(any(), anyInt(), any(RestListRequestFactory.class)))
            .thenThrow(new RestReadException(RestReadOutcome.FORBIDDEN, "denied"));

        MvcResult result = mvc.perform(get("/api/v1/specifications").param("size", "abc"))
            .andExpect(status().isForbidden())
            .andReturn();

        Map<String, Object> json = json(result);
        assertThat(json).containsEntry("code", "forbidden").containsEntry("status", 403);
        assertThat(json).doesNotContainKey("reason");
    }

    @Test
    void parserFailureBecomesProblemWithStableCodeAndNoEcho() throws Exception {
        stubList();

        MvcResult result = mvc.perform(get("/api/v1/specifications").param("size", "abc"))
            .andExpect(status().isBadRequest())
            .andReturn();

        Map<String, Object> json = json(result);
        assertThat(json).containsEntry("code", "invalid_request")
            .containsEntry("param", "size")
            .containsEntry("reason", "invalid_size")
            .containsEntry("instance", "/api/v1/specifications");
        assertThat(json.get("traceId")).isNotNull();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("abc");
    }

    @Test
    void duplicateParameterIsRejectedAtTheMvcBinding() throws Exception {
        stubList();

        MvcResult result = mvc.perform(get("/api/v1/specifications").param("fields", "codeSpec", "draft"))
            .andExpect(status().isBadRequest())
            .andReturn();

        assertThat(json(result)).containsEntry("param", "fields")
            .containsEntry("reason", "duplicate_parameter");
    }

    @Test
    void detailRejectsPagingParametersAfterTheCallbackStarts() throws Exception {
        stubDetail();

        MvcResult result = mvc.perform(get("/api/v1/specifications/1001").param("size", "5"))
            .andExpect(status().isBadRequest())
            .andReturn();

        assertThat(json(result)).containsEntry("param", "size")
            .containsEntry("reason", "unknown_parameter");
    }

    @Test
    void unknownResourceIsNotFoundWithoutParsing() throws Exception {
        when(gateway.list(any(), anyInt(), any(RestListRequestFactory.class)))
            .thenThrow(new RestReadException(RestReadOutcome.RESOURCE_NOT_FOUND, "unknown"));

        MvcResult result = mvc.perform(get("/api/v1/missing").param("size", "abc"))
            .andExpect(status().isNotFound())
            .andReturn();

        assertThat(json(result)).containsEntry("code", "not_found").containsEntry("status", 404);
    }

    @Test
    void nonCanonicalMajorIsNotFoundBeforeTheGateway() throws Exception {
        mvc.perform(get("/api/v01/specifications")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1x/specifications")).andExpect(status().isNotFound());

        verifyNoInteractions(gateway);
    }

    @Test
    void unexpectedFailureDoesNotLeakDiagnostics() throws Exception {
        when(gateway.list(any(), anyInt(), any(RestListRequestFactory.class)))
            .thenThrow(new IllegalStateException("secret SQL detail"));

        MvcResult result = mvc.perform(get("/api/v1/specifications"))
            .andExpect(status().isInternalServerError())
            .andReturn();

        assertThat(json(result)).containsEntry("code", "internal_error");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .doesNotContain("secret SQL detail", "IllegalStateException");
    }

    private void stubList() {
        when(gateway.list(eq("specifications"), eq(1), any(RestListRequestFactory.class)))
            .thenAnswer(invocation -> {
                parsedListQuery = ((RestListRequestFactory) invocation.getArgument(2)).create(LIST);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", 1001L);
                row.put("codeSpec", "SP-001");
                row.put("draft", null);
                int effectiveSize = parsedListQuery.size() == 0 ? LIST.defaultPageSize() : parsedListQuery.size();
                return new RestPageResult(List.of(row), parsedListQuery.page(), effectiveSize, 2, 1);
            });
    }

    @Test
    void restAdviceDoesNotChangeOtherControllersStatus() throws Exception {
        MockMvc isolated = MockMvcBuilders.standaloneSetup(new OutsideController())
            .setControllerAdvice(new RestReadExceptionHandler(
                new RestProblemWriter(new RestJsonCodec(JSON))))
            .build();
        MvcResult result = isolated.perform(get("/outside"))
            .andExpect(status().isNotFound()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("internal_error");
    }

    @org.springframework.web.bind.annotation.RestController
    static class OutsideController {
        @org.springframework.web.bind.annotation.GetMapping("/outside")
        String outside() {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND);
        }
    }

    private void stubDetail() {
        when(gateway.detail(eq("specifications"), eq(1), any(RestDetailRequestFactory.class)))
            .thenAnswer(invocation -> {
                parsedDetailQuery = ((RestDetailRequestFactory) invocation.getArgument(2)).create(DETAIL);
                return new RestDetailResult(Map.of("id", 1001L));
            });
    }

    private static Map<String, Object> json(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsByteArray(),
            new TypeReference<Map<String, Object>>() { });
    }
}

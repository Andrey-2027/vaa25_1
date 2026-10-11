package org.ipro.rest.web.openapi;

import org.ipro.rest.api.*;
import org.ipro.rest.gateway.*;
import org.ipro.rest.service.*;
import org.ipro.rest.web.codec.RestJsonCodec;
import org.ipro.rest.web.mvc.RestReadController;
import org.ipro.rest.web.problem.RestProblemWriter;
import org.ipro.rest.web.problem.RestReadExceptionHandler;
import org.ipro.rest.web.query.RestQueryParser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Contract checks use runtime codec/MVC responses and negative schema cases, not only snapshots. */
class RestOpenApiGeneratorTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RestJsonCodec CODEC = new RestJsonCodec(JSON);

    @Test
    void deterministicAcrossCatalogAndFilterOrderWithoutExecutingReads() {
        var one = resource("items", 1);
        var two = resource("other", 1);
        byte[] first = new RestOpenApiGenerator().generate(gateway(List.of(one, two)));
        assertThat(new RestOpenApiGenerator().generate(gateway(List.of(two, one)))).isEqualTo(first);
        var document = JSON.readTree(first);
        assertThat(document.path("openapi").asText()).isEqualTo("3.1.1");
        assertThat(document.path("paths").size()).isEqualTo(4);
        assertThat(new String(first, java.nio.charset.StandardCharsets.UTF_8))
            .doesNotContain("org.ip", "localhost", "http://", "nullable", "password", "journal.id");
        var empty = JSON.readTree(new RestOpenApiGenerator().generate(gateway(List.of())));
        assertThat(empty.path("paths").isEmpty()).isTrue();
    }

    @Test
    void parametersMatchParserDefaultsGrammarAndWindow() {
        var resource = resource("items", 1);
        var document = document(resource);
        var operation = document.at("/paths/~1api~1v1~1items/get");
        var fields = parameter(operation, "fields");
        assertThat(fields.path("explode").asBoolean()).isFalse();
        assertThat(fields.at("/schema/minItems").asInt()).isEqualTo(1);
        assertThat(fields.at("/schema/uniqueItems").asBoolean()).isTrue();
        assertThat(fields.at("/schema/default")).isEqualTo(JSON.valueToTree(List.of("id", "name")));
        assertThat(parameter(operation, "size").at("/schema/maximum").asInt()).isEqualTo(100);
        assertThat(operation.path("x-max-window").asInt()).isEqualTo(1000);
        var parser = new RestQueryParser();
        assertThat(parser.parseList(resource.list(), Map.of("size", new String[]{"0"})).size()).isZero();
        for (var sort : parameter(operation, "sort").at("/schema/enum")) {
            assertThatCode(() -> parser.parseList(resource.list(), Map.of("sort", new String[]{sort.asText()})))
                .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> parser.parseList(resource.list(), Map.of("page", new String[]{"10"}, "size", new String[]{"100"})))
            .isInstanceOf(org.ipro.rest.web.query.RestQueryException.class);
        assertThat(document.at("/paths/~1api~1v1~1items~1{id}/get/parameters").size()).isEqualTo(2);
    }

    @Test
    void everySuccessExampleRoundTripsThroughRuntimeCodecAndValidates() {
        var document = document(resource("items", 1));
        for (var path : document.path("paths")) {
            var media = path.at("/get/responses/200/content/application~1json");
            var example = media.path("example");
            Map<String, Object> values = JSON.convertValue(example.has("data") ? example.path("data") : example.path("content").get(0), Map.class);
            byte[] wire = example.has("data") ? CODEC.writeDetail(new RestDetailResult(values))
                : CODEC.writeList(new RestPageResult(List.of(values), 0, 50, 1, 1));
            assertThat(JSON.readTree(wire)).isEqualTo(example);
            validate(document, media.path("schema"), JSON.readTree(wire));
        }
    }

    @Test
    void selectedNullOmittedFieldsPrecisionAndForbiddenPropertiesAreDistinguished() {
        var document = document(resource("items", 1));
        var schema = document.at("/components/schemas/items_v1_DetailItem");
        validate(document, schema, JSON.readTree("{\"id\":9007199254740993}"));
        var selected = new LinkedHashMap<String, Object>();
        selected.put("id", Long.MAX_VALUE); selected.put("note", null); selected.put("amount", new BigDecimal("1.234567890123456789"));
        validate(document, schema, JSON.readTree(CODEC.writeDetail(new RestDetailResult(selected))).path("data"));
        for (String invalid : List.of("{}", "{\"id\":null}", "{\"id\":\"1\"}",
                "{\"id\":1,\"name\":null}", "{\"id\":1,\"amount\":\"1.20\"}",
                "{\"id\":1,\"internalSecret\":true}")) {
            assertThatThrownBy(() -> validate(document, schema, JSON.readTree(invalid))).isInstanceOf(AssertionError.class);
        }
        var listSchema = document.at("/components/schemas/items_v1_ListItem");
        assertThatThrownBy(() -> validate(document, listSchema, JSON.readTree("{\"id\":1,\"amount\":1.2}")))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void runtimeProblemsMatchStatusSchemasAndHeaderMatrixIncludingMvcParserFailure() throws Exception {
        var resource = resource("items", 1);
        var document = document(resource);
        var writer = new RestProblemWriter(CODEC);
        var responses = document.at("/paths/~1api~1v1~1items/get/responses");
        for (var outcome : List.of(RestReadOutcome.UNAUTHENTICATED, RestReadOutcome.FORBIDDEN,
                RestReadOutcome.RESOURCE_NOT_FOUND, RestReadOutcome.INVALID_REQUEST, RestReadOutcome.SECURITY_UNAVAILABLE)) {
            var response = new MockHttpServletResponse();
            writer.writeFailure(response, new RestReadException(outcome, "internal secret"), "/api/v1/items");
            checkProblem(document, responses, response);
        }
        for (int status : List.of(405, 406, 500)) {
            var response = new MockHttpServletResponse();
            writer.writeFailure(response, new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatusCode.valueOf(status), "internal secret"), "/api/v1/items");
            checkProblem(document, responses, response);
        }
        var gateway = gateway(List.of(resource));
        when(gateway.list(eq("items"), eq(1), any())).thenAnswer(call -> {
            ((RestListRequestFactory) call.getArgument(2)).create(resource.list());
            return new RestPageResult(List.of(Map.of("id", 1L)), 0, 50, 1, 1);
        });
        var mvc = MockMvcBuilders.standaloneSetup(new RestReadController(gateway, new RestQueryParser(), CODEC))
            .setControllerAdvice(new RestReadExceptionHandler(writer)).build();
        var response = mvc.perform(get("/api/v1/items").param("size", "overflow")).andReturn().getResponse();
        checkProblem(document, responses, response);
        assertThat(JSON.readTree(response.getContentAsByteArray()).path("reason").asText()).isEqualTo("invalid_size");
        var success = mvc.perform(get("/api/v1/items").param("fields", "id")).andReturn().getResponse();
        validate(document, responses.at("/200/content/application~1json/schema"), JSON.readTree(success.getContentAsByteArray()));
    }

    @Test
    void secondMajorDoesNotChangeV1SchemasPathsOrOperationIds() {
        var v1 = resource("items", 1);
        var original = document(v1);
        var extended = JSON.readTree(new RestOpenApiGenerator().generate(gateway(List.of(resource("items", 2), v1))));
        assertThat(extended.at("/paths/~1api~1v1~1items")).isEqualTo(original.at("/paths/~1api~1v1~1items"));
        assertThat(extended.at("/components/schemas/items_v1_ListItem")).isEqualTo(original.at("/components/schemas/items_v1_ListItem"));
        assertThat(extended.at("/paths/~1api~1v2~1items/get/operationId").asText()).isEqualTo("items_v2_list");
    }

    @Test
    void publishingSecondMajorDoesNotInheritFirstMajorGrants() {
        var catalog = mock(org.ipro.rest.catalog.RestResourceCatalog.class);
        List<org.ipro.rest.catalog.ResolvedRestResource> resources = new ArrayList<>();
        for (int major : List.of(1, 2)) {
            var definition = RestResources.publish("items", major, Item.class)
                .field("id", RestFieldType.LONG, RestResources.path("id"), RestNullability.NOT_NULL)
                .listFields("id").listDefaultFields("id").detailFields("id").detailDefaultFields("id").build();
            var resolved = mock(org.ipro.rest.catalog.ResolvedRestResource.class);
            when(resolved.key()).thenReturn(new org.ipro.rest.catalog.RestResourceKey("items", major));
            doReturn(definition).when(resolved).definition();
            resources.add(resolved);
        }
        when(catalog.resources()).thenReturn(resources);
        var c5 = mock(org.ipro.rls.c5.C5PermissionEvaluator.class);
        when(c5.isApiPermitted("alice", "items", 1, "LIST")).thenReturn(true);
        when(c5.isEntityReadPermitted("alice", Item.class)).thenReturn(true);
        var service = mock(RestReadService.class);
        when(service.list(any())).thenReturn(new RestPageResult(List.of(Map.of("id", 1L)), 0, 50, 1, 1));
        var gateway = new DefaultRestReadGateway(catalog, service, () -> "alice", c5, 1000);
        assertThat(JSON.readTree(new RestOpenApiGenerator().generate(gateway)).path("paths").size()).isEqualTo(4);
        verifyNoInteractions(c5, service);
        gateway.list("items", 1, contract -> new RestListQuery("items", 1, null, Map.of(), null, null, 0, 0));
        assertThatThrownBy(() -> gateway.list("items", 2, contract -> {
            throw new AssertionError("v2 parsing must not run under v1 grant");
        })).isInstanceOfSatisfying(RestReadException.class, failure -> assertThat(failure.outcome()).isEqualTo(RestReadOutcome.FORBIDDEN));
        assertThatThrownBy(() -> gateway.detail("items", 2, contract -> {
            throw new AssertionError("v2 parsing must not run under v1 grant");
        })).isInstanceOfSatisfying(RestReadException.class, failure -> assertThat(failure.outcome()).isEqualTo(RestReadOutcome.FORBIDDEN));
    }

    @Test
    void everyExecutableScalarTypeMatchesRuntimeJsonAndIntegerLimits() {
        var id = new RestFieldFact("id", RestFieldType.LONG, RestFieldFormat.NONE, RestNullability.NOT_NULL);
        List<RestFieldFact> fields = new ArrayList<>(List.of(id));
        for (var type : RestFieldType.values()) fields.add(new RestFieldFact(type.name(), type, RestFieldFormat.NONE, RestNullability.NOT_NULL));
        var resource = new RestReadResourceSchema("scalars", 1,
            new RestListContract("scalars", 1, fields, fields, Map.of(), List.of(), null, null, 50, 200, 1000),
            new RestDetailContract("scalars", 1, fields, fields, id));
        var document = document(resource);
        var schema = document.at("/components/schemas/scalars_v1_DetailItem");
        var values = new LinkedHashMap<String, Object>();
        values.put("id", 9_007_199_254_740_993L); values.put("STRING", "literal null"); values.put("BOOLEAN", true);
        values.put("INTEGER", Integer.MAX_VALUE); values.put("LONG", Long.MIN_VALUE); values.put("DECIMAL", new BigDecimal("1234567890.123456789"));
        var wire = JSON.readTree(CODEC.writeDetail(new RestDetailResult(values))).path("data");
        validate(document, schema, wire);
        ((tools.jackson.databind.node.ObjectNode) wire).put("INTEGER", (long) Integer.MAX_VALUE + 1);
        assertThatThrownBy(() -> validate(document, schema, wire)).isInstanceOf(AssertionError.class);
    }

    @Test
    void applicationWireSnapshotsAndDefaultExamplesValidateAgainstReviewedOpenApi() throws Exception {
        var directory = java.nio.file.Path.of("../docs/architecture/contracts/rest-read");
        var document = JSON.readTree(java.nio.file.Files.readAllBytes(directory.resolve("openapi-v1.json")));
        var wire = JSON.readTree(java.nio.file.Files.readAllBytes(directory.resolve("wire-v1.json")));
        assertThat(wire.size()).isEqualTo(12);
        for (var entry : wire.properties()) {
            String path = entry.getKey().split("#", 2)[0];
            var operation = document.path("paths").path(path).path("get");
            var media = operation.at("/responses/200/content/application~1json");
            validate(document, media.path("schema"), entry.getValue());
            if (entry.getKey().endsWith("#default")) assertThat(entry.getValue()).isEqualTo(media.path("example"));
        }
    }

    @Test
    void runtimeProblemSnapshotIsStableAfterTraceNormalization() throws Exception {
        var document = document(resource("items", 1));
        var responses = document.at("/paths/~1api~1v1~1items/get/responses");
        var writer = new RestProblemWriter(CODEC);
        var snapshots = new LinkedHashMap<String, Object>();
        for (int status : List.of(400, 401, 403, 404, 405, 406, 500, 503)) {
            Throwable failure = status == 400
                ? new org.ipro.rest.web.query.RestQueryException("size", org.ipro.rest.web.query.RestQueryReason.INVALID_SIZE, "raw secret")
                : new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(status), "raw secret");
            var response = new MockHttpServletResponse();
            writer.writeFailure(response, failure, "/api/v1/items");
            checkProblem(document, responses, response);
            var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(response.getContentAsByteArray());
            body.put("traceId", "00000000-0000-4000-8000-000000000001");
            Map<String, Object> headers = new TreeMap<>();
            for (String name : List.of("Cache-Control", "WWW-Authenticate", "Allow")) if (response.getHeader(name) != null) headers.put(name, response.getHeader(name));
            snapshots.put(Integer.toString(status), Map.of("status", status, "contentType", response.getContentType(), "headers", headers, "body", body));
        }
        var path = java.nio.file.Path.of("../docs/architecture/contracts/rest-read/problems-v1.json");
        var actual = JSON.valueToTree(snapshots);
        if (Boolean.getBoolean("rest.contract.write")) java.nio.file.Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n");
        assertThat(JSON.readTree(java.nio.file.Files.readAllBytes(path))).as("reviewed Problem contract drift").isEqualTo(actual);
    }

    static class Item { public Long getId() { return 1L; } }

    private static void checkProblem(JsonNode document, JsonNode responses, MockHttpServletResponse response) {
        var contract = responses.path(Integer.toString(response.getStatus()));
        validate(document, contract.at("/content/application~1problem+json/schema"), JSON.readTree(response.getContentAsByteArray()));
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        for (String name : List.of("WWW-Authenticate", "Allow")) {
            if (contract.path("headers").has(name)) assertThat(response.getHeader(name)).isEqualTo(contract.path("headers").path(name).at("/schema/const").asText());
            else assertThat(response.getHeader(name)).isNull();
        }
    }

    private static JsonNode parameter(JsonNode operation, String name) {
        for (var parameter : operation.path("parameters")) if (parameter.path("name").asText().equals(name)) return parameter;
        throw new AssertionError("Missing parameter " + name);
    }
    private static JsonNode document(RestReadResourceSchema resource) { return JSON.readTree(new RestOpenApiGenerator().generate(gateway(List.of(resource)))); }
    private static RestReadGateway gateway(List<RestReadResourceSchema> resources) {
        var gateway = mock(RestReadGateway.class);
        when(gateway.publishedResources()).thenReturn(resources);
        return gateway;
    }
    private static RestReadResourceSchema resource(String key, int major) {
        var id = new RestFieldFact("id", RestFieldType.LONG, RestFieldFormat.NONE, RestNullability.NOT_NULL);
        var name = new RestFieldFact("name", RestFieldType.STRING, RestFieldFormat.NONE, RestNullability.NOT_NULL);
        var note = new RestFieldFact("note", RestFieldType.STRING, RestFieldFormat.NONE, RestNullability.NULLABLE);
        var amount = new RestFieldFact("amount", RestFieldType.DECIMAL, RestFieldFormat.NONE, RestNullability.NOT_NULL);
        return new RestReadResourceSchema(key, major,
            new RestListContract(key, major, List.of(id, name, note), List.of(id, name),
                Map.of("name", new RestFilterFact("name", RestFieldType.STRING, RestFilterOperator.EQUALS)),
                List.of("id", "name"), "id", RestSortDirection.ASC, 50, 100, 1000),
            new RestDetailContract(key, major, List.of(id, name, note, amount), List.of(id, name, note), id));
    }

    /** Bounded JSON Schema assertions for every validation keyword emitted by this generator.
     * Unknown keywords fail rather than silently weakening the contract; not a general OAS validator. */
    private static void validate(JsonNode document, JsonNode schema, JsonNode value) {
        assertThat(schema.isObject()).isTrue();
        schema.properties().forEach(entry -> assertThat(Set.of("$ref", "allOf", "type", "properties",
            "required", "additionalProperties", "items", "minimum", "maximum", "enum", "const", "pattern", "format", "description")
            .contains(entry.getKey())).as("unsupported validation keyword %s", entry.getKey()).isTrue());
        if (schema.has("$ref")) { validate(document, document.at(schema.path("$ref").asText().substring(1)), value); return; }
        for (var member : schema.path("allOf")) validate(document, member, value);
        if (schema.has("type")) {
            var types = schema.path("type");
            boolean matches = types.isArray() ? iterableTypes(types, value) : matches(types.asText(), value);
            assertThat(matches).as("type %s for %s", types, value).isTrue();
        }
        if (schema.has("const")) assertThat(value).isEqualTo(schema.path("const"));
        if (schema.has("enum")) { boolean found = false; for (var allowed : schema.path("enum")) found |= allowed.equals(value); assertThat(found).isTrue(); }
        if (value.isObject()) {
            for (var required : schema.path("required")) assertThat(value.has(required.asText())).isTrue();
            value.properties().forEach(entry -> {
                if (schema.path("properties").has(entry.getKey())) validate(document, schema.path("properties").path(entry.getKey()), entry.getValue());
                else if (schema.has("additionalProperties")) assertThat(schema.path("additionalProperties").asBoolean()).isTrue();
            });
        }
        if (value.isArray() && schema.has("items")) for (var item : value) validate(document, schema.path("items"), item);
        if (value.isNumber()) {
            if (schema.has("minimum")) assertThat(value.decimalValue()).isGreaterThanOrEqualTo(schema.path("minimum").decimalValue());
            if (schema.has("maximum")) assertThat(value.decimalValue()).isLessThanOrEqualTo(schema.path("maximum").decimalValue());
        }
        if (value.isString()) {
            if (schema.has("pattern")) assertThat(value.asText()).matches(schema.path("pattern").asText());
            if (schema.path("format").asText().equals("uuid")) assertThatCode(() -> UUID.fromString(value.asText())).doesNotThrowAnyException();
        }
    }
    private static boolean iterableTypes(JsonNode types, JsonNode value) { for (var type : types) if (matches(type.asText(), value)) return true; return false; }
    private static boolean matches(String type, JsonNode value) { return switch (type) {
        case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isString();
        case "integer" -> value.isIntegralNumber(); case "number" -> value.isNumber(); case "boolean" -> value.isBoolean(); case "null" -> value.isNull();
        default -> throw new AssertionError("Unsupported type " + type);
    }; }
}

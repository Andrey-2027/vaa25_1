package org.ipro.rest.web.openapi;

import org.ipro.rest.api.RestFieldFormat;
import org.ipro.rest.api.RestFieldType;
import org.ipro.rest.api.RestNullability;
import org.ipro.rest.gateway.RestFieldFact;
import org.ipro.rest.gateway.RestReadGateway;
import org.ipro.rest.gateway.RestReadResourceSchema;
import org.ipro.rest.web.problem.RestProblemFactory;
import org.ipro.rest.web.problem.RestProblemWriter;
import org.ipro.rest.web.query.RestQueryReason;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * INTERNAL deterministic OpenAPI 3.1.1 export of reviewed gateway facts (4.5).
 * No model reflection, read execution, environment host, clock or documentation HTTP route.
 * Resource/major keys and map-valued filters are sorted; declared field order is preserved.
 */
public final class RestOpenApiGenerator {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<Integer, String> ERRORS = errors();

    public byte[] generate(RestReadGateway gateway) {
        Map<String, Object> paths = obj();
        Map<String, Object> schemas = obj();
        schemas.put("Problem", problemSchema());
        for (var error : ERRORS.entrySet()) {
            schemas.put("Problem" + error.getKey(), obj("allOf", List.of(ref("Problem"),
                obj("properties", obj("status", obj("const", error.getKey()),
                    "code", obj("const", error.getValue()))))));
        }
        for (var resource : gateway.publishedResources().stream()
                .sorted(Comparator.comparing(RestReadResourceSchema::resource)
                    .thenComparingInt(RestReadResourceSchema::major)).toList()) {
            String base = "/api/v" + resource.major() + "/" + resource.resource();
            String name = resource.resource() + "_v" + resource.major();
            if (paths.containsKey(base)) throw new IllegalArgumentException("Duplicate published resource/major");
            schemas.put(name + "_ListItem", projection(resource.list().maximumFields()));
            schemas.put(name + "_DetailItem", projection(resource.detail().maximumFields()));
            schemas.put(name + "_List", obj("type", "object", "additionalProperties", false,
                "required", List.of("content", "page", "size", "totalElements", "totalPages"),
                "properties", obj("content", obj("type", "array", "items", ref(name + "_ListItem")),
                    "page", obj("type", "integer", "minimum", 0, "maximum", Integer.MAX_VALUE),
                    "size", obj("type", "integer", "minimum", 1, "maximum", resource.list().effectiveMaxPageSize()),
                    "totalElements", obj("type", "integer", "minimum", 0, "maximum", Long.MAX_VALUE),
                    "totalPages", obj("type", "integer", "minimum", 0, "maximum", Integer.MAX_VALUE))));
            schemas.put(name + "_Detail", obj("type", "object", "additionalProperties", false,
                "required", List.of("data"), "properties", obj("data", ref(name + "_DetailItem"))));
            paths.put(base, obj("get", operation(resource, name, false)));
            paths.put(base + "/{id}", obj("get", operation(resource, name, true)));
        }
        var document = obj("openapi", "3.1.1", "jsonSchemaDialect", "https://json-schema.org/draft/2020-12/schema",
            "info", obj("title", "Published REST read API", "version", "1.0",
                "description", "Explicit read resources. Basic credentials require TLS. Unknown/duplicate query parameters are rejected."),
            "security", List.of(obj("basicAuth", List.of())), "paths", paths,
            "components", obj("securitySchemes", obj("basicAuth", obj("type", "http", "scheme", "basic")),
                "schemas", schemas));
        return (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document).replace("\r\n", "\n") + "\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Map<String, Object> operation(RestReadResourceSchema resource, String name, boolean detail) {
        var contract = resource.list();
        var maximum = detail ? resource.detail().maximumFields() : contract.maximumFields();
        var defaults = detail ? resource.detail().defaultFields() : contract.defaultFields();
        List<Object> parameters = new ArrayList<>();
        if (detail) {
            // STRING IDs are opaque to the current parser, even if their output has a format hint.
            var idSchema = inputScalar(resource.detail().idField().type());
            idSchema.put("minLength", 1); // only relevant for opaque string IDs
            if (resource.detail().idField().type() != RestFieldType.STRING) idSchema.remove("minLength");
            parameters.add(obj("name", "id", "in", "path", "required", true, "schema", idSchema,
                "description", "Single simple identifier; integer parsing accepts signed decimal only and rejects overflow."));
        }
        parameters.add(obj("name", "fields", "in", "query", "required", false,
            "style", "form", "explode", false,
            "description", "Comma-separated unique aliases, no whitespace trimming. Absent uses defaults; id is always returned. Empty selection is invalid.",
            "schema", obj("type", "array", "minItems", 1, "uniqueItems", true,
                "maxItems", maximum.size(), "items", obj("type", "string", "enum", aliases(maximum)), "default", aliases(defaults))));
        if (!detail) {
            parameters.add(parameter("page", obj("type", "integer", "minimum", 0,
                "maximum", Math.min(Integer.MAX_VALUE, (long) contract.maxWindow() - 1), "default", 0),
                "Zero-based page; (page * effective size + effective size) must not exceed x-max-window."));
            parameters.add(parameter("size", obj("type", "integer", "minimum", 0,
                "maximum", contract.effectiveMaxPageSize(), "default", contract.defaultPageSize()),
                "Absent or explicit 0 uses the published default. Integer overflow is rejected."));
            if (!contract.sortAliases().isEmpty()) {
                List<String> sorts = new ArrayList<>();
                for (String alias : contract.sortAliases()) {
                    sorts.add(alias); sorts.add(alias + ",asc"); sorts.add(alias + ",desc");
                }
                var sortSchema = obj("type", "string", "enum", sorts);
                if (contract.defaultSortAlias() != null) sortSchema.put("default",
                    contract.defaultSortAlias() + "," + contract.defaultSortDirection().name().toLowerCase(java.util.Locale.ROOT));
                parameters.add(parameter("sort", sortSchema, "One declared alias[,asc|desc]; alias alone means asc. Stable id tie-breaker is server-owned."));
            }
            contract.filters().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                var filter = entry.getValue();
                var parameter = parameter(filter.alias(), inputScalar(filter.type()),
                    "Equality filter; supplied exactly once. Strings are literal, including empty and 'null'; no trim or SQL NULL syntax.");
                parameter.put("x-operator", filter.operator().name());
                if (filter.type() == RestFieldType.STRING) parameter.put("allowEmptyValue", true);
                parameters.add(parameter);
            });
        }
        String suffix = detail ? "_Detail" : "_List";
        var defaultData = example(defaults);
        Object successExample = detail ? obj("data", defaultData)
            : obj("content", List.of(defaultData), "page", 0, "size", contract.defaultPageSize(), "totalElements", 1, "totalPages", 1);
        Map<String, Object> responses = obj("200", obj("description", "Successful scalar projection",
            "headers", obj("Cache-Control", obj("schema", obj("type", "string", "const", "no-store"))),
            "content", obj("application/json", obj("schema", ref(name + suffix), "example", successExample))));
        for (var error : ERRORS.entrySet()) {
            var headers = obj("Cache-Control", obj("schema", obj("type", "string", "const", "no-store")));
            if (error.getKey() == 401) headers.put("WWW-Authenticate", obj("schema", obj("type", "string",
                "const", "Basic realm=\"" + RestProblemWriter.BASIC_REALM + "\"")));
            if (error.getKey() == 405) headers.put("Allow", obj("schema", obj("type", "string", "const", "GET")));
            responses.put(error.getKey().toString(), obj("description", RestProblemFactory.titleFor(error.getKey()),
                "headers", headers, "content", obj("application/problem+json", obj("schema", ref("Problem" + error.getKey())))));
        }
        var operation = obj("operationId", name + (detail ? "_detail" : "_list"),
            "parameters", parameters, "responses", responses, "x-default-fields", aliases(defaults),
            "x-maximum-fields", aliases(maximum), "x-unknown-query-parameters", "reject",
            "x-duplicate-query-parameters", "reject");
        if (!detail) operation.put("x-max-window", contract.maxWindow());
        return operation;
    }

    private static Map<String, Object> parameter(String name, Map<String, Object> schema, String description) {
        return obj("name", name, "in", "query", "required", false, "style", "form", "explode", false,
            "description", description, "schema", schema);
    }

    private static Map<String, Object> projection(List<RestFieldFact> fields) {
        Map<String, Object> properties = obj();
        for (var field : fields) properties.put(field.alias(), scalar(field.type(), field.format(),
            field.nullability() == RestNullability.NULLABLE));
        return obj("type", "object", "additionalProperties", false, "required", List.of("id"), "properties", properties);
    }

    private static Map<String, Object> scalar(RestFieldType type, RestFieldFormat format, boolean nullable) {
        String jsonType = switch (type) {
            case STRING -> "string"; case DECIMAL -> "number"; case BOOLEAN -> "boolean"; case INTEGER, LONG -> "integer";
        };
        var schema = obj("type", nullable ? List.of(jsonType, "null") : jsonType);
        switch (type) {
            case INTEGER -> { schema.put("minimum", Integer.MIN_VALUE); schema.put("maximum", Integer.MAX_VALUE); schema.put("format", "int32"); }
            case LONG -> { schema.put("minimum", Long.MIN_VALUE); schema.put("maximum", Long.MAX_VALUE); schema.put("format", "int64"); }
            case DECIMAL -> schema.put("description", "Exact JSON decimal number; clients must preserve decimal precision.");
            default -> { }
        }
        if (format != RestFieldFormat.NONE) schema.put("format", switch (format) {
            case UUID -> "uuid"; case DATE -> "date"; case DATE_TIME -> "date-time"; case NONE -> throw new IllegalStateException();
        });
        return schema;
    }

    private static Map<String, Object> inputScalar(RestFieldType type) {
        var schema = scalar(type, RestFieldFormat.NONE, false);
        switch (type) {
            case LONG, INTEGER -> schema.put("x-wire-pattern", "^[+-]?[0-9]+$");
            case DECIMAL -> schema.put("x-wire-pattern", "^[+-]?[0-9]+(\\.[0-9]+)?$");
            case BOOLEAN -> schema.put("x-wire-values", List.of("true", "false"));
            case STRING -> { }
        }
        return schema;
    }

    private static Map<String, Object> problemSchema() {
        return obj("type", "object", "additionalProperties", false,
            "required", List.of("type", "title", "status", "detail", "instance", "code", "traceId"),
            "properties", obj("type", obj("type", "string", "const", "about:blank"),
                "title", obj("type", "string"), "status", obj("type", "integer", "enum", new ArrayList<>(ERRORS.keySet())),
                "detail", obj("type", "string"), "instance", obj("type", "string"),
                "code", obj("type", "string", "enum", new ArrayList<>(ERRORS.values())),
                "traceId", obj("type", "string", "format", "uuid"),
                "param", obj("type", "string", "pattern", "^[A-Za-z_][A-Za-z0-9_]{0,127}$"),
                "reason", obj("type", "string", "enum", java.util.Arrays.stream(RestQueryReason.values()).map(RestQueryReason::wireName).toList())));
    }

    private static Map<String, Object> example(List<RestFieldFact> fields) {
        var values = obj();
        for (var field : fields) {
            Object value = field.nullability() == RestNullability.NULLABLE ? null : switch (field.type()) {
                case STRING -> switch (field.format()) {
                    case UUID -> "00000000-0000-4000-8000-000000000001";
                    case DATE -> "2026-01-01"; case DATE_TIME -> "2026-01-01T00:00:00Z"; case NONE -> "example";
                };
                case BOOLEAN -> true; case INTEGER, LONG -> 1; case DECIMAL -> new java.math.BigDecimal("1.00");
            };
            values.put(field.alias(), value);
        }
        return values;
    }

    private static List<String> aliases(List<RestFieldFact> fields) { return fields.stream().map(RestFieldFact::alias).toList(); }
    private static Map<String, Object> ref(String name) { return obj("$ref", "#/components/schemas/" + name); }
    private static Map<Integer, String> errors() {
        var errors = new LinkedHashMap<Integer, String>();
        errors.put(400, "invalid_request"); errors.put(401, "unauthenticated"); errors.put(403, "forbidden");
        errors.put(404, "not_found"); errors.put(405, "method_not_allowed"); errors.put(406, "not_acceptable");
        errors.put(500, "internal_error"); errors.put(503, "security_unavailable");
        return java.util.Collections.unmodifiableMap(errors);
    }
    private static Map<String, Object> obj(Object... entries) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) map.put((String) entries[i], entries[i + 1]);
        return map;
    }
}

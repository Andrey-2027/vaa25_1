package org.ip.rest;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RestReadHttpContractIT extends RestReadHttpTestSupport {
    @Test
    void socketResponsesMatchReviewedV1Snapshots() throws Exception {
        var snapshots = JSON.readTree(java.nio.file.Files.readString(
            java.nio.file.Path.of("docs/architecture/contracts/rest-read/wire-v1.json")));
        for (String resource : List.of("specifications", "nomenclature")) {
            boolean spec = resource.equals("specifications");
            String root = "/api/v1/" + resource;
            for (boolean detail : List.of(false, true)) {
                String path = detail ? root + "/" + (spec ? fixture.specA() : fixture.nomId()) : root;
                for (String projection : List.of("default", "id", "maximum")) {
                    String fields = projection.equals("default") ? "" : projection.equals("id") ? "fields=id"
                        : "fields=" + (spec ? detail ? "id,codeSpec,draft,comment,journalId,nomenclatureCode"
                            : "id,codeSpec,draft,nomenclatureCode" : "id,code,name,typeNom");
                    String filter = !detail && !spec ? "typeNom=" + fixture.typeNom() : "";
                    String query = java.util.stream.Stream.of(fields, filter).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.joining("&"));
                    var reply = get(alice, path + (query.isEmpty() ? "" : "?" + query)); success(reply);
                    var body = reply.json();
                    var row = (tools.jackson.databind.node.ObjectNode) (detail ? body.path("data") : body.path("content").get(0));
                    for (String name : new ArrayList<>(row.propertyNames())) {
                        if (row.path(name).isTextual()) row.put(name, "example");
                        else if (row.path(name).isIntegralNumber()) row.put(name, 1);
                    }
                    assertThat(body).as(root + " " + detail + " " + projection)
                        .isEqualTo(snapshots.path(root + (detail ? "/{id}" : "") + "#" + projection));
                }
            }
        }
    }

    @Test
    void int64AboveJavascriptSafeIntegerSurvivesStorageHttpAndClientParsing() throws Exception {
        long exact = 9_007_199_254_740_993L;
        org.ipro.rls.RlsTestFixture.runAsSuperuser(grants, () -> {
            var original = nomenclatures.findById(fixture.nomId()).orElseThrow();
            var nom = new org.ip.model.Nomenclature(); nom.setCode("HIGH" + fixture.nomCode());
            nom.setName("Exact int64"); nom.setTypeNom(fixture.typeNom());
            nom.setGroupNom(original.getGroupNom()); nom.setUnitOfMeasurement(original.getUnitOfMeasurement());
            var saved = nomenclatures.saveAndFlush(nom);
            // Fixture-only primary-key assignment: this new row has no incoming references.
            assertThat(jdbc.update("update nomenclature set id=? where id=?", exact, saved.getId())).isEqualTo(1);
        });
        var reply = get(alice, "/api/v1/nomenclature/" + exact + "?fields=id"); success(reply);
        assertThat(reply.response().body()).contains("9007199254740993");
        assertThat(reply.json().path("data").path("id").isIntegralNumber()).isTrue();
        assertThat(reply.json().path("data").path("id").asLong()).isEqualTo(exact);
    }

    @Test
    void defaultSubsetMaximumAndNullableFieldsHaveTheExactWireShape() throws Exception {
        for (String resource : List.of("specifications", "nomenclature")) {
            String base = "/api/v1/" + resource;
            long id = resource.equals("specifications") ? fixture.specA() : fixture.nomId();
            for (boolean detail : List.of(false, true)) {
                String path = detail ? base + "/" + id : base;
                var defaults = get(alice, path); success(defaults);
                var row = detail ? defaults.json().path("data") : defaults.json().path("content").get(0);
                Set<String> expected = resource.equals("specifications") ? detail ? Set.of("id", "codeSpec", "draft", "comment") : Set.of("id", "codeSpec", "draft") : Set.of("id", "code", "name");
                assertThat(row.propertyNames()).containsExactlyInAnyOrderElementsOf(expected);
                if (resource.equals("specifications")) assertThat(row.path("draft").isNull()).isTrue();
                var subset = get(alice, path + "?fields=" + (resource.equals("specifications") ? "draft" : "code")); success(subset);
                var selected = detail ? subset.json().path("data") : subset.json().path("content").get(0);
                assertThat(selected.size()).isEqualTo(2); assertThat(selected.has("id")).isTrue();
                String max = resource.equals("specifications") ? detail ? "id,codeSpec,draft,comment,journalId,nomenclatureCode" : "id,codeSpec,draft,nomenclatureCode" : "id,code,name,typeNom";
                var maximum = get(alice, path + "?fields=" + max); success(maximum);
                var full = detail ? maximum.json().path("data") : maximum.json().path("content").get(0);
                assertThat(full.propertyNames()).containsExactlyInAnyOrderElementsOf(List.of(max.split(",")));
                assertThat(full.toString()).doesNotContain("materials", "operations", "unitOfMeasurement", "groupNom");
            }
        }
    }
    @Test
    void strictQueriesIdsMethodsAcceptAndOverflowUseThePublishedProblemContract() throws Exception {
        for (String query : List.of("fields=", "fields=id,id", "fields=nomenclature.code", "fields=journal.id", "fields=materials",
                "unknown=x", "size=1&size=2", "fields=id&fields=codeSpec", "page=-1", "page=2147483648", "size=-1", "size=201",
                "journalId=", "journalId=1.0", "journalId=9223372036854775808", "sort=id,asc,codeSpec", "sort=codeSpec,ASC"))
            problem(get(alice, "/api/v1/specifications?" + query), 400, "invalid_request");
        for (String id : List.of("bad", "1.0", "9223372036854775808"))
            problem(get(alice, "/api/v1/specifications/" + id), 400, "invalid_request");
        problem(get(alice, "/api/v1/specifications/" + fixture.specA() + "?size=1"), 400, "invalid_request");
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
            problem(request(alice, PASSWORD, method, "/api/v1/specifications", Map.of("Accept", "text/html"), null), 405, "method_not_allowed");
        problem(request(alice, PASSWORD, "GET", "/api/v1/specifications", Map.of("Accept", "text/html"), null), 406, "not_acceptable");
        success(request(alice, PASSWORD, "GET", "/api/v1/specifications?fields=id", Map.of("Accept", "application/json"), null));
        var head = request(alice, PASSWORD, "HEAD", "/api/v1/specifications", Map.of(), null);
        assertThat(head.response().statusCode()).isEqualTo(405);
        assertThat(head.response().headers().firstValue("Allow")).contains("GET");
        assertThat(head.response().body()).isEmpty(); // servlet suppresses the HEAD entity
    }
    @Test
    void opaqueStringFiltersDoNotCoerceEmptyOrNullIntoSqlNull() throws Exception {
        for (String value : List.of("", "null", "not-present")) {
            var reply = get(alice, "/api/v1/nomenclature?fields=id&typeNom=" + value); success(reply);
            assertThat(reply.json().path("content").isEmpty()).isTrue();
        }
        var matching = get(alice, "/api/v1/nomenclature?typeNom=" + fixture.typeNom()); success(matching);
        assertThat(matching.json().path("totalElements").asLong()).isEqualTo(1);
    }
}

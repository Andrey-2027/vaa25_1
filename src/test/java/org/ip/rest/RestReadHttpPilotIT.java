package org.ip.rest;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RestReadHttpPilotIT extends RestReadHttpTestSupport {
    @Test
    void pagingWindowOnWPlusOneRowsAndSqlGraphsHaveFixedBudgetsForBothResources() throws Exception {
        seedRows(201); // W=200: totals still report W+1 and the inaccessible final page
        for (String resource : java.util.List.of("specifications", "nomenclature")) {
            boolean spec = resource.equals("specifications");
            String base = "/api/v1/" + resource;
            String max = spec ? "id,codeSpec,draft,nomenclatureCode" : "id,code,name,typeNom";
            String filter = spec ? "journalId=" + fixture.journalA() : "typeNom=" + fixture.typeNom();
            java.util.List<Reply> pages = new java.util.ArrayList<>();
            for (int size : java.util.List.of(1, 50, 200)) {
                var reply = get(alice, base + "?fields=" + max + "&" + filter + "&size=" + size); success(reply); pages.add(reply);
                assertThat(reply.json().path("content").size()).isEqualTo(size);
                assertThat(reply.json().path("totalElements").asLong()).isEqualTo(201);
                assertThat(reply.json().path("totalPages").asInt()).isEqualTo((201 + size - 1) / size);
                assertThat(reply.observation().count(Kind.CONTENT)).isEqualTo(1);
                assertThat(reply.observation().count(Kind.COUNT)).isEqualTo(1);
                reply.observation().print(resource + "-max-size-" + size);
                assertGraphs(reply, spec, false);
                assertMaximumBudget(reply, spec, false, false);
            }
            for (Kind kind : java.util.List.of(Kind.AUTHENTICATION, Kind.C5, Kind.ROLE, Kind.RLS)) {
                assertThat(pages.get(1).observation().count(kind)).as("same security budget at size=50").isEqualTo(pages.get(0).observation().count(kind));
                assertThat(pages.get(2).observation().count(kind)).as("same security budget at size=200").isEqualTo(pages.get(0).observation().count(kind));
            }
            var last = get(alice, base + "?fields=" + max + "&" + filter + "&page=3&size=50"); success(last);
            assertThat(last.json().path("content").size()).isEqualTo(50);
            assertThat(last.json().path("totalElements").asLong()).isEqualTo(201);
            assertThat(last.json().path("totalPages").asInt()).isEqualTo(5);
            assertMaximumBudget(last, spec, false, false); assertGraphs(last, spec, false);
            var defaultSize = get(alice, base + "?fields=" + max + "&" + filter + "&page=3&size=0"); success(defaultSize);
            assertThat(defaultSize.json()).isEqualTo(last.json());
            var one = get(alice, base + "?fields=id&" + filter + "&page=199&size=1"); success(one);
            assertThat(one.json().path("content").size()).isEqualTo(1);
            for (String boundary : java.util.List.of("page=4&size=50", "page=1&size=200", "page=200&size=1", "page=2147483647&size=200"))
                problem(get(alice, base + "?" + filter + "&" + boundary), 400, "invalid_request");
            var second = get(alice, base + "?fields=id&" + filter + "&page=1&size=1"); success(second);
            assertThat(second.json().path("content").get(0).path("id").asLong()).isNotEqualTo(pages.get(0).json().path("content").get(0).path("id").asLong());
            long id = spec ? fixture.specA() : fixture.nomId();
            String detailMax = spec ? "id,codeSpec,draft,comment,journalId,nomenclatureCode" : max;
            var detail = get(alice, base + "/" + id + "?fields=" + detailMax); success(detail);
            assertThat(detail.observation().count(Kind.CONTENT)).isEqualTo(1);
            assertThat(detail.observation().count(Kind.COUNT)).isZero();
            assertGraphs(detail, spec, true); detail.observation().print(resource + "-max-detail");
            assertMaximumBudget(detail, spec, true, false);
            if (spec) {
                var sorted = get(alice, base + "?fields=id&" + filter + "&sort=codeSpec,desc&size=50"); success(sorted);
                java.util.List<Long> ids = new java.util.ArrayList<>(); sorted.json().path("content").forEach(row -> ids.add(row.path("id").asLong()));
                assertThat(ids).isSorted(); // duplicate codeSpec values use ascending id tie-breaker
            }
        }
    }
    @Test
    void resourceSpecificSmallerMaximumIsEnforcedOverHttp() throws Exception {
        grant(org.ipro.rls.AccessGrant.SubjectType.USER, alice, "REST:limited-nomenclature:v1:LIST", "REST:limited-nomenclature:v1:DETAIL");
        var first = get(alice, "/api/v1/limited-nomenclature?size=0"); success(first);
        assertThat(first.json().path("size").asInt()).isEqualTo(1);
        success(get(alice, "/api/v1/limited-nomenclature?size=2"));
        problem(get(alice, "/api/v1/limited-nomenclature?size=3"), 400, "invalid_request");
    }
    @Test
    void realHttpBasicReadsBothPublishedResourcesWithFixedGraphs() throws Exception {
        var specs = get(alice, "/api/v1/specifications"); success(specs);
        assertThat(specs.json().path("content").size()).isEqualTo(1);
        assertThat(specs.json().path("content").get(0).path("id").asLong()).isEqualTo(fixture.specA());
        var detail = get(alice, "/api/v1/specifications/" + fixture.specA()); success(detail);
        var nom = get(alice, "/api/v1/nomenclature/" + fixture.nomId()); success(nom);
        specs.observation().print("spec-default-list"); detail.observation().print("spec-default-detail"); nom.observation().print("nom-default-detail");
        assertThat(specs.observation().graphs).isNotEmpty();
    }
}

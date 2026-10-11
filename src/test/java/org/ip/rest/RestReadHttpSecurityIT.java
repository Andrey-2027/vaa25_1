package org.ip.rest;

import org.ip.model.*;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.RlsTestFixture;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RestReadHttpSecurityIT extends RestReadHttpTestSupport {
    @Test
    void anonymousBadCredentialsDisabledAndUnknownRoutesKeepTheirPriority() throws Exception {
        for (String path : List.of("/api/v1/specifications", "/api/v1/unknown?size=bad", "/api/v1/specifications/bad")) {
            var reply = get(null, path); problem(reply, 401, "unauthenticated");
            assertThat(reply.observation().sql).isEmpty();
        }
        problem(request(alice, "wrong", "GET", "/api/v1/specifications", Map.of(), null), 401, "unauthenticated");
        problem(get(disabled, "/api/v1/specifications"), 401, "unauthenticated");
        for (String path : List.of("/api/v999/specifications", "/api/v01/specifications", "/api/v1/Journal", "/api/v1/app_user", "/api/v1/unknown?size=bad"))
            problem(get(alice, path), 404, "not_found");
    }

    @Test
    void realApiEntityAndReferenceGrantsGateBeforeParsingOrBusinessSql() throws Exception {
        for (String path : List.of("/api/v1/specifications?size=bad", "/api/v1/specifications/bad", "/api/v1/nomenclature?typeNom=bad"))
            problem(get(denied, path), 403, "forbidden");
        for (var entry : Map.of(
                C5PermissionKeys.entityRead(PrdSpec.class), "/api/v1/specifications?size=bad",
                C5PermissionKeys.entityRead(Nomenclature.class), "/api/v1/specifications?fields=nomenclatureCode",
                C5PermissionKeys.entityRead(Journal.class), "/api/v1/specifications?fields=id&journalId=" + fixture.journalA()).entrySet()) {
            revoke(alice, entry.getKey()); problem(get(alice, entry.getValue()), 403, "forbidden");
            grant(AccessGrant.SubjectType.USER, alice, entry.getKey());
        }
    }

    @Test
    void publishedOutputFilterDefaultSortAndIdWorkWithoutAttributeGrants() throws Exception {
        assertThat(Arrays.stream(allReadKeys())).noneMatch(key -> key.startsWith("ATTR:"));
        for (String path : List.of(
                "/api/v1/nomenclature",
                "/api/v1/nomenclature?fields=id,code,name,typeNom&typeNom=" + fixture.typeNom(),
                "/api/v1/nomenclature?fields=id&sort=code",
                "/api/v1/nomenclature/" + fixture.nomId() + "?fields=id,code,name,typeNom",
                "/api/v1/specifications?fields=id,codeSpec,draft,nomenclatureCode&journalId=" + fixture.journalA() + "&sort=codeSpec",
                "/api/v1/specifications/" + fixture.specA() + "?fields=id,codeSpec,draft,comment,journalId,nomenclatureCode")) {
            success(get(alice, path));
        }
        problem(get(alice, "/api/v1/nomenclature?fields=rawPassword"), 400, "invalid_request");
        problem(get(alice, "/api/v1/nomenclature?sort=rawPassword"), 400, "invalid_request");
    }

    @Test
    void listAndDetailApiPermissionsAreIndependentForBothResources() throws Exception {
        for (String resource : List.of("specifications", "nomenclature")) {
            String root = "/api/v1/" + resource;
            Long id = resource.equals("specifications") ? fixture.specA() : fixture.nomId();
            revoke(alice, "REST:" + resource + ":v1:LIST");
            problem(get(alice, root), 403, "forbidden"); success(get(alice, root + "/" + id));
            grant(AccessGrant.SubjectType.USER, alice, "REST:" + resource + ":v1:LIST");
            revoke(alice, "REST:" + resource + ":v1:DETAIL");
            success(get(alice, root)); problem(get(alice, root + "/" + id), 403, "forbidden");
        }
    }

    @Test
    void assignedRoleRevocationRemovalAndUnassignedRoleChangeTheNextHttpRequest() throws Exception {
        Role reader = RlsTestFixture.callAsSuperuser(grants, () -> access.create(Role.class, new Role("reader-" + denied)));
        Role irrelevant = RlsTestFixture.callAsSuperuser(grants, () -> access.create(Role.class, new Role("unused-" + denied)));
        grant(AccessGrant.SubjectType.ROLE, reader.getName(), allReadKeys());
        rowGrant(AccessGrant.SubjectType.ROLE, reader.getName(), fixture.journalA());
        problem(get(denied, "/api/v1/specifications"), 403, "forbidden"); // grant of an unassigned role
        assign(denied, reader, irrelevant);
        var allowed = get(denied, "/api/v1/specifications?fields=id"); success(allowed);
        assertThat(allowed.json().path("content").get(0).path("id").asLong()).isEqualTo(fixture.specA());
        allowed.observation().print("role-two-assignments-id-list");
        seedRows(201);
        for (String resource : List.of("specifications", "nomenclature")) {
            boolean spec = resource.equals("specifications");
            String root = "/api/v1/" + resource;
            String fields = spec ? "id,codeSpec,draft,nomenclatureCode" : "id,code,name,typeNom";
            String filter = spec ? "journalId=" + fixture.journalA() : "typeNom=" + fixture.typeNom();
            for (int size : List.of(1, 50, 200)) {
                var page = get(denied, root + "?fields=" + fields + "&" + filter + "&size=" + size); success(page);
                assertThat(page.json().path("content").size()).isEqualTo(size);
                assertThat(page.json().path("totalElements").asLong()).isEqualTo(201);
                assertThat(page.observation().count(Kind.CONTENT)).isEqualTo(1);
                assertThat(page.observation().count(Kind.COUNT)).isEqualTo(1);
                page.observation().print("role-two-" + resource + "-max-size-" + size);
                assertMaximumBudget(page, spec, false, true); assertGraphs(page, spec, false);
            }
            var detail = get(denied, root + "/" + (spec ? fixture.specA() : fixture.nomId()) + "?fields="
                + (spec ? "id,codeSpec,draft,comment,journalId,nomenclatureCode" : fields)); success(detail);
            assertThat(detail.observation().count(Kind.CONTENT)).isEqualTo(1);
            assertThat(detail.observation().count(Kind.COUNT)).isZero();
            detail.observation().print("role-two-" + resource + "-max-detail");
            assertMaximumBudget(detail, spec, true, true); assertGraphs(detail, spec, true);
        }
        RlsTestFixture.runAsSuperuser(grants, () -> {
            grants.deleteAll(grants.findBySubjectTypeAndSubjectKeyAndDimension(AccessGrant.SubjectType.ROLE, reader.getName(), "REST:specifications:v1:LIST")); grants.flush();
        });
        problem(get(denied, "/api/v1/specifications"), 403, "forbidden");
        grant(AccessGrant.SubjectType.ROLE, reader.getName(), "REST:specifications:v1:LIST");
        success(get(denied, "/api/v1/specifications?fields=id"));
        assign(denied, irrelevant);
        problem(get(denied, "/api/v1/specifications"), 403, "forbidden");
    }
    void assign(String username, Role... assigned) {
        RlsTestFixture.runAsSuperuserWithRead(grants, new Class<?>[] {User.class}, () -> {
            var user = users.findByUsername(username).orElseThrow(); user.setRoles(Set.of(assigned));
            // Назначение ролей — изменение governed-сущности: repository.save мимо preflight отказал бы.
            access.update(User.class, user);
        });
    }

    @Test
    void existingUiAndJpqlChainsRemainDistinctFromRestRead() throws Exception {
        var ui = get(null, "/ureport/index.html");
        assertThat(ui.response().statusCode()).isBetween(300, 399);
        assertThat(ui.response().headers().firstValue("Location").orElse("")).contains("login");
        var anonymous = request(null, PASSWORD, "POST", "/api/report-jpql/preview", Map.of("Content-Type", "application/json"), "{\"jpql\":\"select n.id from Nomenclature n\"}");
        assertThat(anonymous.response().statusCode()).isEqualTo(401);
        assertThat(anonymous.response().body()).doesNotContain("\"traceId\"");
        var noGrant = request(alice, PASSWORD, "POST", "/api/report-jpql/preview", Map.of("Content-Type", "application/json"), "{\"jpql\":\"select n.id from Nomenclature n\"}");
        assertThat(noGrant.response().statusCode()).isEqualTo(403);
        assertThat(noGrant.response().body()).contains("error").doesNotContain("traceId");
        grant(AccessGrant.SubjectType.USER, alice, "REPORTS:JPQL_PREVIEW");
        var invalid = request(alice, PASSWORD, "POST", "/api/report-jpql/preview", Map.of("Content-Type", "application/json"), "{\"jpql\":\"\"}");
        assertThat(invalid.response().statusCode()).isEqualTo(400);
        assertThat(invalid.response().body()).contains("error").doesNotContain("traceId");
    }
}

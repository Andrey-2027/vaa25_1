package org.ip.rest;

import org.ipro.rls.AccessGrant;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

class RestReadHttpSessionIsolationIT extends RestReadHttpTestSupport {
    @Test
    void differentJournalsHiddenDetailsAndFkFiltersDoNotLeakRows() throws Exception {
        var a = get(alice, "/api/v1/specifications?fields=id"); success(a);
        var b = get(bob, "/api/v1/specifications?fields=id"); success(b);
        assertThat(a.json().path("content").get(0).path("id").asLong()).isEqualTo(fixture.specA());
        assertThat(b.json().path("content").get(0).path("id").asLong()).isEqualTo(fixture.specB());
        var hidden = get(alice, "/api/v1/specifications/" + fixture.specB()); problem(hidden, 404, "not_found");
        var missing = get(alice, "/api/v1/specifications/9223372036854775807"); problem(missing, 404, "not_found");
        var hiddenBody = (tools.jackson.databind.node.ObjectNode) hidden.json(); hiddenBody.remove(List.of("traceId", "instance"));
        var missingBody = (tools.jackson.databind.node.ObjectNode) missing.json(); missingBody.remove(List.of("traceId", "instance"));
        assertThat(hiddenBody).isEqualTo(missingBody);
        var foreign = get(alice, "/api/v1/specifications?fields=id&journalId=" + fixture.journalB()); success(foreign);
        assertThat(foreign.json().path("content").isEmpty()).isTrue();
        assertThat(foreign.json().path("totalElements").asLong()).isZero();
        success(get(bob, "/api/v1/nomenclature/" + fixture.nomId())); // no JOURNAL RLS on this entity
    }
    @Test
    void aRealExistingSessionAndStaleCookiesCannotAuthenticateTheRestChain() throws Exception {
        var session = get(null, "/pilot/session");
        assertThat(session.response().statusCode()).isEqualTo(200);
        String cookie = "JSESSIONID=" + session.json().path("id").asText();
        problem(request(null, PASSWORD, "GET", "/api/v1/specifications", Map.of("Cookie", cookie), null), 401, "unauthenticated");
        problem(request(null, PASSWORD, "GET", "/api/v1/specifications", Map.of("Cookie", "JSESSIONID=stale-cookie"), null), 401, "unauthenticated");
        var own = request(alice, PASSWORD, "GET", "/api/v1/specifications?fields=id", Map.of("Cookie", cookie), null); success(own);
        assertThat(own.json().path("content").get(0).path("id").asLong()).isEqualTo(fixture.specA());
        var state = request(null, PASSWORD, "GET", "/pilot/session-state", Map.of("Cookie", cookie), null);
        assertThat(state.response().statusCode()).isEqualTo(200);
        assertThat(state.json().path("id").asText()).isEqualTo(session.json().path("id").asText());
        assertThat(state.json().path("username").asText()).isEqualTo("session-only-user");
        success(get(alice, "/api/v1/specifications?fields=id"));
    }
    @Test
    void revokingRowAndApiGrantsChangesTheNextRequestWithoutCacheReuse() throws Exception {
        success(get(alice, "/api/v1/specifications?fields=id"));
        revoke(alice, "JOURNAL");
        var empty = get(alice, "/api/v1/specifications?fields=id"); success(empty);
        assertThat(empty.json().path("content").isEmpty()).isTrue();
        rowGrant(AccessGrant.SubjectType.USER, alice, fixture.journalA());
        success(get(alice, "/api/v1/specifications/" + fixture.specA()));
        revoke(alice, "REST:specifications:v1:LIST");
        problem(get(alice, "/api/v1/specifications?size=bad"), 403, "forbidden");
        success(get(bob, "/api/v1/specifications?fields=id"));
    }
    @Test
    void parallelSubjectsAndFailurePathsDoNotShareRequestState() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<Reply>> replies = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String subject = i % 2 == 0 ? alice : bob;
                replies.add(executor.submit(() -> get(subject, "/api/v1/specifications?fields=id")));
            }
            for (int i = 0; i < replies.size(); i++) {
                var reply = replies.get(i).get(30, TimeUnit.SECONDS); success(reply);
                assertThat(reply.json().path("content").size()).isEqualTo(1);
                assertThat(reply.json().path("content").get(0).path("id").asLong()).isEqualTo(i % 2 == 0 ? fixture.specA() : fixture.specB());
            }
        }
        problem(get(alice, "/api/v1/specifications?size=bad"), 400, "invalid_request");
        problem(get(denied, "/api/v1/specifications"), 403, "forbidden");
        success(get(bob, "/api/v1/specifications?fields=id"));
        success(get(alice, "/api/v1/specifications?fields=id"));
    }
}

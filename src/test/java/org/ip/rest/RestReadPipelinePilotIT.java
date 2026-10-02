package org.ip.rest;

import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ipro.data.EntityReadAccess;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.service.RestListQuery;
import org.ipro.rest.service.RestPageResult;
import org.ipro.rest.service.RestReadOutcome;
import org.ipro.rest.service.RestReadService;
import org.ipro.rest.service.RestReadException;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Full-context Pilot Integration Tests for Gate 3A and 3B (F-REST-READ-3 §9).
 * Exercises real application entity models (PrdSpec, Nomenclature) and the server read pipeline.
 */
@SpringBootTest(classes = Application.class)
@TestPropertySource(properties = "platform.rest.read.enabled=true")
class RestReadPipelinePilotIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @MockitoBean
    private C5PermissionEvaluator c5Evaluator;

    @MockitoBean
    private RlsCurrentUser currentUser;

    @Autowired
    private RestResourceCatalog catalog;

    @Autowired
    private RestReadService readService;

    @Autowired
    private EntityReadAccess entityReadAccess;

    @Autowired
    private RlsDimensionRegistry dimensionRegistry;

    @Test
    @DisplayName("Gate 3A: Startup registration registers REST operations in RlsDimensionRegistry")
    void startupRegistrationRegistersRestOperations() {
        assertThat(dimensionRegistry.dimensions()).contains(
            "REST:specifications:v1:LIST",
            "REST:specifications:v1:DETAIL",
            "REST:nomenclature:v1:LIST",
            "REST:nomenclature:v1:DETAIL"
        );
    }

    @Test
    @DisplayName("Gate 3A: Rejects anonymous or unauthenticated caller with 401 UNAUTHENTICATED")
    void rejectsUnauthenticatedCaller() {
        when(currentUser.username()).thenReturn(null);

        RestListQuery query = new RestListQuery("specifications", 1, List.of("id"), Map.of(), null, null, 0, 10);
        assertThatThrownBy(() -> readService.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("Gate 3A: Denies caller without API grant with 403 FORBIDDEN")
    void deniesCallerWithoutApiGrant() {
        when(currentUser.username()).thenReturn("operator");
        when(c5Evaluator.isApiPermitted("operator", "specifications", 1, "LIST")).thenReturn(false);

        RestListQuery query = new RestListQuery("specifications", 1, List.of("id"), Map.of(), null, null, 0, 10);
        assertThatThrownBy(() -> readService.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN);
    }

    @Test
    @DisplayName("Gate 3B: Pipeline succeeds and produces pure detached projection without OSIV leak")
    void pipelineExecutesAndProducesDetachedProjection() {
        when(currentUser.username()).thenReturn("operator");
        when(c5Evaluator.isApiPermitted("operator", "nomenclature", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("operator", Nomenclature.class)).thenReturn(true);
        when(c5Evaluator.isAttributeReadPermitted(eq("operator"), eq(Nomenclature.class), any())).thenReturn(true);

        RestListQuery query = new RestListQuery("nomenclature", 1,
            List.of("id", "code", "name"), Map.of(), null, null, 0, 10);

        RestPageResult result = readService.list(query);
        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.page()).isEqualTo(0);

        // Content verification: unmodifiable Map of scalar projections
        for (Map<String, Object> item : result.content()) {
            assertThat(item).containsKeys("id", "code", "name");
            assertThat(item).doesNotContainKey("typeNom");
            assertThatThrownBy(() -> item.put("injected", true))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}

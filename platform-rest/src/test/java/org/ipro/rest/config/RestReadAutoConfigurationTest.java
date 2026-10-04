package org.ipro.rest.config;

import jakarta.persistence.metamodel.Attribute;
import org.ipro.data.EntityReadAccess;
import org.ipro.rest.api.RestFieldType;
import org.ipro.rest.api.RestNullability;
import org.ipro.rest.api.RestResourceDefinition;
import org.ipro.rest.api.RestResources;
import org.ipro.rest.catalog.ResolvedRestField;
import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestResourceKey;
import org.ipro.rest.catalog.RestResourceProjection;
import org.ipro.rest.security.RestReferenceAuthorizationValidator;
import org.ipro.rest.service.RestListQuery;
import org.ipro.rest.service.RestReadException;
import org.ipro.rest.service.RestReadOutcome;
import org.ipro.rest.service.RestReadService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class RestReadAutoConfigurationTest {

    @Test
    void emptyCatalogStartsWithoutReadBackends() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestReadAutoConfiguration.class))
            .withPropertyValues("platform.rest.read.enabled=true")
            .withBean(RestResourceCatalog.class, RestResourceCatalog::empty)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(RestReadService.class);
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2147483648", "not-a-number", ""})
    void invalidWindowStopsEnabledReadStartup(String maxWindow) {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestReadAutoConfiguration.class))
            .withPropertyValues("platform.rest.read.enabled=true", "platform.rest.read.max-window=" + maxWindow)
            .withBean(RestResourceCatalog.class, RestResourceCatalog::empty)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void inactiveReadDoesNotBindWindow() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestReadAutoConfiguration.class))
            .withPropertyValues("platform.rest.read.enabled=false", "platform.rest.read.max-window=not-a-number")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(RestReadService.class);
            });
    }

    @Test
    void missingWindowUsesPlatformDefaultInExecutableService() {
        executableRunner().run(context -> {
            assertThat(context).hasNotFailed();
            RestReadService service = context.getBean(RestReadService.class);
            service.list(query(199, 50));
            assertThatThrownBy(() -> service.list(query(200, 50)))
                .isInstanceOf(RestReadException.class)
                .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.INVALID_REQUEST);
        });
    }

    @Test
    void configuredWindowIsPassedToExecutableService() {
        executableRunner().withPropertyValues("platform.rest.read.max-window=150").run(context -> {
            assertThat(context).hasNotFailed();
            RestReadService service = context.getBean(RestReadService.class);
            service.list(query(2, 0));
            assertThatThrownBy(() -> service.list(query(3, 0)))
                .isInstanceOf(RestReadException.class)
                .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.INVALID_REQUEST);
        });
    }

    @Test
    void windowSmallerThanPublishedDefaultPageFailsFast() {
        executableRunner().withPropertyValues("platform.rest.read.max-window=49").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("platform.rest.read.max-window")
                .hasStackTraceContaining("default page size");
        });
    }

    private RestListQuery query(int page, int size) {
        return new RestListQuery("items", 1, List.of("id"), Map.of(), null, null, page, size);
    }

    private ApplicationContextRunner executableRunner() {
        RestResourceDefinition<Item> definition = RestResources.publish("items", 1, Item.class)
            .field("id", RestFieldType.LONG, RestResources.path("id"), RestNullability.NOT_NULL)
            .listFields("id").listDefaultFields("id")
            .detailFields("id").detailDefaultFields("id").sortBy("id").pageSize(50, 200).build();
        ResolvedRestPath id = new ResolvedRestPath("id", List.of(new ResolvedRestPath.Segment("id",
            Item.class, Long.class, Attribute.PersistentAttributeType.BASIC, false, false)), Long.class, false);
        ResolvedRestResource resource = mock(ResolvedRestResource.class);
        doReturn(definition).when(resource).definition();
        when(resource.key()).thenReturn(new RestResourceKey("items", 1));
        when(resource.projection(RestReadOperation.LIST)).thenReturn(new RestResourceProjection(
            RestReadOperation.LIST, List.of("id"), List.of("id"), Map.of()));
        when(resource.fields()).thenReturn(Map.of("id", new ResolvedRestField(definition.fields().get("id"), id)));
        when(resource.sorts()).thenReturn(Map.of("id", id));
        when(resource.filters()).thenReturn(Map.of());
        when(resource.fixedFetchPaths(RestReadOperation.LIST)).thenReturn(List.of("id"));
        RestResourceCatalog catalog = mock(RestResourceCatalog.class);
        when(catalog.resources()).thenReturn(List.of(resource));
        when(catalog.find("items", 1)).thenReturn(Optional.of(resource));
        EntityReadAccess readAccess = mock(EntityReadAccess.class);
        when(readAccess.list(eq(Item.class), any(), any(Pageable.class), anyCollection()))
            .thenAnswer(call -> new PageImpl<>(List.of(), call.getArgument(2, Pageable.class), 0));
        C5PermissionEvaluator permissions = mock(C5PermissionEvaluator.class);
        when(permissions.isApiPermitted("alice", "items", 1, "LIST")).thenReturn(true);
        when(permissions.isEntityReadPermitted("alice", Item.class)).thenReturn(true);
        when(permissions.isAttributeReadPermitted("alice", Item.class, "id")).thenReturn(true);
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestReadAutoConfiguration.class))
            .withPropertyValues("platform.rest.read.enabled=true")
            .withBean(RestResourceCatalog.class, () -> catalog)
            .withBean(EntityReadAccess.class, () -> readAccess)
            .withBean(C5PermissionEvaluator.class, () -> permissions)
            .withBean(RlsCurrentUser.class, () -> () -> "alice")
            .withBean(RestReferenceAuthorizationValidator.class,
                () -> mock(RestReferenceAuthorizationValidator.class));
    }

    public static class Item {
        public Long getId() { return 1L; }
    }
}

package org.ipro.rest.web.config;

import org.ipro.rest.gateway.RestReadGateway;
import org.ipro.rest.service.RestReadService;
import org.ipro.rest.web.codec.RestJsonCodec;
import org.ipro.rest.web.mvc.RestReadController;
import org.ipro.rest.web.problem.RestProblemWriter;
import org.ipro.rest.web.problem.RestReadExceptionHandler;
import org.ipro.rest.web.query.RestQueryParser;
import org.ipro.rest.web.rls.RestAwareRlsReadableIdsResolver;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F-REST-READ-4 §9 4.1-4.3: disabled / empty-catalog / non-web contexts, the contradictory
 * configurations that must stop startup, and the beans of the enabled namespace.
 */
class RestWebAutoConfigurationTest {

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RestRlsResolverAutoConfiguration.class,
            RestWebAutoConfiguration.class));

    private final ApplicationContextRunner plainRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RestRlsResolverAutoConfiguration.class,
            RestWebAutoConfiguration.class));

    @Test
    void isDisabledByDefaultAndAddsNoBeans() {
        servletRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RestWebStartupCheck.class);
            assertThat(context).doesNotHaveBean(RestReadController.class);
            assertThat(context).doesNotHaveBean(org.ipro.rest.web.openapi.RestOpenApiGenerator.class);
        });
    }

    @Test
    void staysInactiveInANonWebContextEvenWhenExplicitlyEnabled() {
        plainRunner
            .withPropertyValues("platform.rest.web.enabled=true", "platform.rest.read.enabled=true")
            .withBean(RestReadService.class, () -> mock(RestReadService.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(RestWebStartupCheck.class);
            });
    }

    @Test
    void failsStartupWhenTheWebNamespaceIsEnabledWithoutServerRead() {
        servletRunner.withPropertyValues("platform.rest.web.enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("platform.rest.read.enabled");
            });
    }

    @Test
    void failsStartupWhenTheReadGatewayBeanIsMissingWithoutOtherCollaborators() {
        servletRunner
            .withPropertyValues("platform.rest.web.enabled=true", "platform.rest.read.enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("RestReadGateway");
            });
    }

    @Test
    void failsStartupWhenTheReadGatewayBeanIsMissing() {
        servletRunner
            .withPropertyValues("platform.rest.web.enabled=true", "platform.rest.read.enabled=true")
            .withBean(RestReadService.class, () -> mock(RestReadService.class))
            .withBean(AccessService.class, () -> mock(AccessService.class))
            .withBean(RlsCurrentUser.class, () -> (RlsCurrentUser) () -> "system")
            .withBean(UserDetailsService.class, () -> mock(UserDetailsService.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("RestReadGateway");
            });
    }

    @Test
    void failsStartupWhenAccessServiceIsMissing() {
        servletRunner
            .withPropertyValues("platform.rest.web.enabled=true", "platform.rest.read.enabled=true")
            .withBean(RestReadService.class, () -> mock(RestReadService.class))
            .withBean(RestReadGateway.class, () -> mock(RestReadGateway.class))
            .withBean(RlsCurrentUser.class, () -> (RlsCurrentUser) () -> "system")
            .withBean(UserDetailsService.class, () -> mock(UserDetailsService.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("AccessService");
            });
    }

    @Test
    void failsStartupWhenTheIdentityBridgeIsMissing() {
        servletRunner
            .withPropertyValues("platform.rest.web.enabled=true", "platform.rest.read.enabled=true")
            .withBean(RestReadService.class, () -> mock(RestReadService.class))
            .withBean(RestReadGateway.class, () -> mock(RestReadGateway.class))
            .withBean(AccessService.class, () -> mock(AccessService.class))
            .withBean(UserDetailsService.class, () -> mock(UserDetailsService.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("RlsCurrentUser");
            });
    }

    @Test
    void failsStartupWhenNoCredentialsAreConfigured() {
        servletRunner
            .withPropertyValues("platform.rest.web.enabled=true", "platform.rest.read.enabled=true")
            .withBean(RestReadService.class, () -> mock(RestReadService.class))
            .withBean(RestReadGateway.class, () -> mock(RestReadGateway.class))
            .withBean(AccessService.class, () -> mock(AccessService.class))
            .withBean(RlsCurrentUser.class, () -> (RlsCurrentUser) () -> "system")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("UserDetailsService");
            });
    }

    @Test
    void failsStartupWhenNoManagedJsonMapperIsPresent() {
        withStartupCollaborators()
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("JsonMapper");
            });
    }

    @Test
    void startsWithAnEmptyCatalogAndRegistersTheNamespaceBeans() {
        withStartupCollaborators()
            .withUserConfiguration(TestSecurityConfiguration.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(RestWebStartupCheck.class);
                assertThat(context).hasSingleBean(RestReadController.class);
                assertThat(context).hasSingleBean(org.ipro.rest.web.openapi.RestOpenApiGenerator.class);
                assertThat(context).hasSingleBean(RestReadExceptionHandler.class);
                assertThat(context).hasSingleBean(RestJsonCodec.class);
                assertThat(context).hasSingleBean(RestQueryParser.class);
                assertThat(context).hasSingleBean(RestProblemWriter.class);
                assertThat(context).hasSingleBean(RestAwareRlsReadableIdsResolver.class);
                assertThat(context.getBeanNamesForType(
                    org.ipro.rest.web.rls.RestReadableIdsCache.class))
                    .contains("restReadableIdsCache", "scopedTarget.restReadableIdsCache");
                // Execute Boot's actual adaptation of Filter beans, not only the security chain.
                var initializers = new org.springframework.boot.web.servlet.ServletContextInitializerBeans(
                    context.getBeanFactory());
                var filters = initializers.stream()
                    .filter(org.springframework.boot.web.servlet.FilterRegistrationBean.class::isInstance)
                    .map(org.springframework.boot.web.servlet.FilterRegistrationBean.class::cast)
                    .filter(registration -> registration.getFilter() instanceof
                        org.ipro.rest.web.security.RestRequestScopeFilter
                        || registration.getFilter() instanceof
                        org.ipro.rest.web.security.RestReadNamespaceFilter)
                    .toList();
                assertThat(filters).hasSize(2).allMatch(registration -> !registration.isEnabled());
            });
    }

    private WebApplicationContextRunner withStartupCollaborators() {
        return servletRunner
            .withPropertyValues("platform.rest.web.enabled=true", "platform.rest.read.enabled=true")
            .withBean(RestReadGateway.class, () -> mock(RestReadGateway.class))
            .withBean(AccessService.class, () -> mock(AccessService.class))
            .withBean(RlsCurrentUser.class, () -> (RlsCurrentUser) () -> "system")
            .withBean(UserDetailsService.class, () -> mock(UserDetailsService.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));
    }
}

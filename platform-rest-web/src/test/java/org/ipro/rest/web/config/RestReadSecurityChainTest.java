package org.ipro.rest.web.config;

import org.ipro.rest.gateway.RestReadGateway;
import org.ipro.rest.service.RestReadService;
import org.ipro.rest.web.problem.RestProblemWriter;
import org.ipro.rest.web.rls.RestAwareRlsReadableIdsResolver;
import org.ipro.rest.web.rls.RestReadableIdsCache;
import org.ipro.rest.web.security.RestReadNamespaceFilter;
import org.ipro.rest.web.security.RestRequestScopeFilter;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadableIdsResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F-REST-READ-4 §5: the enabled namespace owns exactly one stateless Basic chain ordered after the
 * existing JPQL preview chain, both namespace filters, the shared Problem writer, the
 * request-scoped RLS integration, and a unique independent resolver.
 */
class RestReadSecurityChainTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RestRlsResolverAutoConfiguration.class,
            RestWebAutoConfiguration.class))
        .withUserConfiguration(TestSecurityConfiguration.class);

    @Test
    void registersTheChainFiltersAndTheResolver() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RestRequestScopeFilter.class);
            assertThat(context).hasSingleBean(RestReadNamespaceFilter.class);
            assertThat(context).hasSingleBean(RestProblemWriter.class);
            assertThat(context).hasSingleBean(RestAwareRlsReadableIdsResolver.class);
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(RestReadableIdsCache.class))
                .contains("restReadableIdsCache", "scopedTarget.restReadableIdsCache");
        });
    }

    @Test
    void chainIsOrderedAfterTheJpqlPreviewChain() throws Exception {
        Method method = RestWebAutoConfiguration.class.getDeclaredMethod("restReadSecurityFilterChain",
            HttpSecurity.class, RestProblemWriter.class, RestRequestScopeFilter.class,
            RestReadNamespaceFilter.class);

        Order order = method.getAnnotation(Order.class);

        assertThat(order).as("REST-цепочка обязана идти после существующей @Order(1) JPQL")
            .isNotNull();
        assertThat(order.value()).isEqualTo(2);
    }

    @Test
    void twoIndependentResolversStopStartup() {
        RlsReadableIdsResolver custom = (dimension, username) -> null;

        enabled()
            .withBean(RlsReadableIdsResolver.class, () -> custom)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("exactly one");
            });
    }

    private WebApplicationContextRunner enabled() {
        return runner
            .withPropertyValues("platform.rest.web.enabled=true", "platform.rest.read.enabled=true")
            .withBean(RestReadService.class, () -> mock(RestReadService.class))
            .withBean(RestReadGateway.class, () -> mock(RestReadGateway.class))
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(AccessService.class, () -> mock(AccessService.class))
            .withBean(RlsCurrentUser.class, () -> (RlsCurrentUser) () -> "system")
            .withBean(UserDetailsService.class, () -> mock(UserDetailsService.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));
    }
}

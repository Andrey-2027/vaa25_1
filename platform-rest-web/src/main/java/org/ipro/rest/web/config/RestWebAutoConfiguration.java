package org.ipro.rest.web.config;

import org.ipro.rest.config.RestReadAutoConfiguration;
import org.ipro.rest.gateway.RestReadGateway;
import org.ipro.rest.web.codec.RestJsonCodec;
import org.ipro.rest.web.mvc.RestReadController;
import org.ipro.rest.web.openapi.RestOpenApiGenerator;
import org.ipro.rest.web.problem.RestProblemWriter;
import org.ipro.rest.web.problem.RestReadExceptionHandler;
import org.ipro.rest.web.query.RestQueryParser;
import org.ipro.rest.web.rls.RestReadableIdsCache;
import org.ipro.rest.web.security.RestReadNamespaceFilter;
import org.ipro.rest.web.security.RestRequestScopeFilter;
import org.ipro.rest.web.security.RestRequestTargetParser;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadableIdsResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.context.annotation.RequestScope;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * F-REST-READ-4 (§3, §9 4.1-4.3): optional wiring of the REST read HTTP adapter.
 *
 * <p>Disabled by default: without {@code platform.rest.web.enabled=true} the artifact adds no
 * routes, filters or beans. Enabling the versioned namespace is meaningful only together with the
 * server-side read pipeline, an RLS identity bridge and real credentials, so a contradictory
 * configuration stops startup instead of degrading silently. The request-aware resolver and its
 * ordering before {@code RlsAutoConfiguration} live in
 * {@link RestRlsResolverAutoConfiguration}.</p>
 */
@AutoConfiguration
@AutoConfigureAfter(RestReadAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "platform.rest.web.enabled", havingValue = "true")
public class RestWebAutoConfiguration {

    @Bean
    RestWebStartupCheck restWebStartupCheck(Environment environment,
                                            ObjectProvider<RestReadGateway> gatewayProvider,
                                            ObjectProvider<AccessService> accessServiceProvider,
                                            ObjectProvider<RlsCurrentUser> currentUserProvider,
                                            org.springframework.beans.factory.ListableBeanFactory beanFactory,
                                            ObjectProvider<UserDetailsService> userDetailsServiceProvider,
                                            ObjectProvider<PasswordEncoder> passwordEncoderProvider,
                                            ObjectProvider<AuthenticationProvider> authenticationProviderProvider) {
        if (!environment.getProperty("platform.rest.read.enabled", Boolean.class, false)) {
            throw new IllegalStateException("platform.rest.web.enabled=true requires "
                + "platform.rest.read.enabled=true: the HTTP adapter serves the server-side REST read "
                + "pipeline and must not open a namespace without it");
        }
        if (gatewayProvider.getIfAvailable() == null) {
            throw new IllegalStateException("platform.rest.web.enabled=true requires a RestReadGateway "
                + "bean: the HTTP adapter serves the reviewed MODULE_API read gateway");
        }
        if (accessServiceProvider.getIfAvailable() == null) {
            throw new IllegalStateException("platform.rest.web.enabled=true requires an AccessService "
                + "bean: request-scoped readable ids are resolved through the RLS access service");
        }
        if (currentUserProvider.getIfAvailable() == null) {
            throw new IllegalStateException("platform.rest.web.enabled=true requires an RlsCurrentUser "
                + "bean: the REST principal must be the subject RLS evaluates");
        }
        long resolverCount = 0;
        for (String name : beanFactory.getBeanNamesForType(RlsReadableIdsResolver.class)) {
            // Scoped caches (session UI cache, request cache) are state, not independent sources:
            // the scoped target is not a singleton, its proxy delegate is a ScopedProxyFactoryBean.
            if (!beanFactory.isSingleton(name)
                    || beanFactory instanceof ConfigurableListableBeanFactory configurable
                        && org.springframework.aop.scope.ScopedProxyFactoryBean.class.getName()
                            .equals(configurable.getBeanDefinition(name).getBeanClassName())) {
                continue;
            }
            resolverCount++;
        }
        if (resolverCount != 1) {
            throw new IllegalStateException("platform.rest.web.enabled=true requires exactly one "
                + "independent RlsReadableIdsResolver bean (singleton), found "
                + resolverCount + ": two independent sources must not be chosen silently");
        }
        if (authenticationProviderProvider.getIfAvailable() == null
                && (userDetailsServiceProvider.getIfAvailable() == null
                    || passwordEncoderProvider.getIfAvailable() == null)) {
            throw new IllegalStateException("platform.rest.web.enabled=true requires either an "
                + "AuthenticationProvider bean or both UserDetailsService and PasswordEncoder beans: "
                + "the adapter never creates a default user or password");
        }
        return new RestWebStartupCheck();
    }

    @Bean
    @ConditionalOnMissingBean
    RestOpenApiGenerator restOpenApiGenerator() {
        return new RestOpenApiGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    RestQueryParser restQueryParser() {
        return new RestQueryParser();
    }

    @Bean
    @ConditionalOnMissingBean
    RestJsonCodec restJsonCodec(ObjectProvider<JsonMapper> mapperProvider) {
        JsonMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            throw new IllegalStateException("platform.rest.web.enabled=true requires a managed Jackson "
                + "JsonMapper bean: the REST response writer is a copy of the application mapper");
        }
        return new RestJsonCodec(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    RestProblemWriter restProblemWriter(RestJsonCodec codec) {
        return new RestProblemWriter(codec);
    }

    @Bean
    @ConditionalOnMissingBean
    RestReadExceptionHandler restReadExceptionHandler(RestProblemWriter writer) {
        return new RestReadExceptionHandler(writer);
    }

    @Bean
    @ConditionalOnMissingBean
    RestReadController restReadController(RestReadGateway gateway, RestQueryParser parser,
                                          RestJsonCodec codec) {
        return new RestReadController(gateway, parser, codec);
    }

    /** Request-scoped readable ids: same semantics as the session cache, lifetime of one request. */
    @Bean
    @RequestScope
    @ConditionalOnMissingBean
    RestReadableIdsCache restReadableIdsCache(AccessService accessService) {
        return new RestReadableIdsCache(accessService);
    }

    @Bean
    @ConditionalOnMissingBean
    RestRequestTargetParser restRequestTargetParser() {
        return new RestRequestTargetParser();
    }

    @Bean
    @ConditionalOnMissingBean
    RestRequestScopeFilter restRequestScopeFilter(RestProblemWriter writer) {
        return new RestRequestScopeFilter(writer);
    }

    @Bean
    @ConditionalOnMissingBean
    RestReadNamespaceFilter restReadNamespaceFilter(RestReadGateway gateway,
                                                    RestRequestTargetParser targetParser,
                                                    RestProblemWriter writer) {
        return new RestReadNamespaceFilter(gateway, targetParser, writer);
    }

    /** These filters belong exclusively to the REST security chain, not the servlet container. */
    @Bean
    FilterRegistrationBean<RestRequestScopeFilter> restRequestScopeFilterRegistration(
            RestRequestScopeFilter filter) {
        FilterRegistrationBean<RestRequestScopeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RestReadNamespaceFilter> restReadNamespaceFilterRegistration(
            RestReadNamespaceFilter filter) {
        FilterRegistrationBean<RestReadNamespaceFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Stateless Basic read chain of the versioned namespace (F-REST-READ-4 §5.1), ordered after the
     * existing {@code @Order(1)} JPQL preview chain and before the UI fallback. It owns only
     * the versioned namespace (matcher {@code v{major}} under {@code /api}): there is no broad
     * {@code /api} interception, no form-login redirects, no CORS and no request cache, and a 401
     * response is Problem Details even for XHR.
     */
    @Bean
    @Order(2)
    SecurityFilterChain restReadSecurityFilterChain(HttpSecurity http,
                                                    RestProblemWriter writer,
                                                    RestRequestScopeFilter requestScopeFilter,
                                                    RestReadNamespaceFilter namespaceFilter) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, failure) -> {
            writer.writeFailure(response, failure, request.getRequestURI());
        };
        AccessDeniedHandler deniedHandler = (request, response, denial) ->
            writer.write(response, 403, "forbidden",
                "Access to the requested resource is denied", null, null, request.getRequestURI());

        http
            .securityMatcher("/api/v*/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                // STATELESS prevents persistence but does not suppress fixation callbacks
                // on an incoming UI session. REST Basic must not rotate that session either.
                .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy()))
            .securityContext(context -> context
                .requireExplicitSave(true)
                .securityContextRepository(new NullSecurityContextRepository()))
            .requestCache(cache -> cache.disable())
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .httpBasic(basic -> basic
                .realmName(RestProblemWriter.BASIC_REALM)
                .authenticationEntryPoint(entryPoint))
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(deniedHandler))
            .addFilterBefore(requestScopeFilter, SecurityContextHolderFilter.class)
            .addFilterAfter(namespaceFilter, AuthorizationFilter.class);
        return http.build();
    }
}

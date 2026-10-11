package org.ipro.rest.config;

import org.ipro.data.EntityReadAccess;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.gateway.DefaultRestReadGateway;
import org.ipro.rest.gateway.RestReadGateway;
import org.ipro.rest.security.C5RestOperationRegistrar;
import org.ipro.rest.security.RestReferenceAuthorizationValidator;
import org.ipro.rest.service.RestReadService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the server-side REST read pipeline (F-REST-READ-3 В§6).
 * Activated conditionally when 'platform.rest.read.enabled=true'.
 */
@AutoConfiguration(after = RestResourceCatalogAutoConfiguration.class)
@ConditionalOnProperty(name = "platform.rest.read.enabled", havingValue = "true")
public class RestReadAutoConfiguration {

    @Bean
    @ConditionalOnBean({C5PermissionEvaluator.class, RlsDimensionRegistry.class, RestResourceCatalog.class})
    @ConditionalOnMissingBean
    public RestReferenceAuthorizationValidator restReferenceAuthorizationValidator(
            C5PermissionEvaluator c5Evaluator,
            RlsDimensionRegistry dimensionRegistry,
            ObjectProvider<RlsReadGate> readGateProvider,
            RestResourceCatalog catalog) {
        return new RestReferenceAuthorizationValidator(c5Evaluator, dimensionRegistry,
            readGateProvider.getIfAvailable(), catalog);
    }

    @Bean
    @ConditionalOnBean({RestResourceCatalog.class, RlsDimensionRegistry.class, C5ReadPermissionRegistry.class})
    @ConditionalOnMissingBean
    public C5RestOperationRegistrar c5RestOperationRegistrar(
            RestResourceCatalog catalog,
            RlsDimensionRegistry dimensionRegistry,
            C5ReadPermissionRegistry readPermissionRegistry) {
        return new C5RestOperationRegistrar(catalog, dimensionRegistry, readPermissionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestReadService restReadService(
            RestResourceCatalog catalog,
            ObjectProvider<EntityReadAccess> readAccessProvider,
            ObjectProvider<C5PermissionEvaluator> c5EvaluatorProvider,
            ObjectProvider<RlsCurrentUser> currentUserProvider,
            ObjectProvider<RestReferenceAuthorizationValidator> referenceValidatorProvider,
            @Value("${platform.rest.read.max-window:" + RestReadService.DEFAULT_MAX_WINDOW + "}") int maxWindow) {

        EntityReadAccess readAccess = readAccessProvider.getIfAvailable();
        C5PermissionEvaluator c5Evaluator = c5EvaluatorProvider.getIfAvailable();
        RlsCurrentUser currentUser = currentUserProvider.getIfAvailable();
        RestReferenceAuthorizationValidator referenceValidator = referenceValidatorProvider.getIfAvailable();

        if (catalog != null && !catalog.resources().isEmpty()) {
            if (readAccess == null) {
                throw new IllegalStateException("REST read is enabled and catalog has registered resources, but EntityReadAccess backend is missing");
            }
            if (c5Evaluator == null) {
                throw new IllegalStateException("REST read is enabled and catalog has registered resources, but C5PermissionEvaluator backend is missing");
            }
            if (currentUser == null) {
                throw new IllegalStateException("REST read is enabled and catalog has registered resources, but RlsCurrentUser backend is missing");
            }
            if (referenceValidator == null) {
                throw new IllegalStateException("REST read is enabled and catalog has registered resources, but RestReferenceAuthorizationValidator backend is missing");
            }
        }
        return new RestReadService(catalog, readAccess, c5Evaluator, currentUser, referenceValidator, maxWindow);
    }

    /**
     * MODULE_API read gateway for the optional HTTP adapter (F-REST-READ-4 §3.4). An empty
     * catalog is a legal state: it publishes no schema and resolves nothing, so every HTTP
     * address is a 404 without any JPA type being exposed. With a non-empty catalog the
     * collaborators are mandatory; a missing one already fails the read service, and the
     * gateway repeats the check for user-provided service beans.
     */
    @Bean
    @ConditionalOnMissingBean
    public RestReadGateway restReadGateway(
            RestResourceCatalog catalog,
            RestReadService readService,
            ObjectProvider<RlsCurrentUser> currentUserProvider,
            ObjectProvider<C5PermissionEvaluator> c5EvaluatorProvider,
            @Value("${platform.rest.read.max-window:" + RestReadService.DEFAULT_MAX_WINDOW + "}") int maxWindow) {
        return new DefaultRestReadGateway(catalog, readService,
            currentUserProvider.getIfAvailable(), c5EvaluatorProvider.getIfAvailable(), maxWindow);
    }
}

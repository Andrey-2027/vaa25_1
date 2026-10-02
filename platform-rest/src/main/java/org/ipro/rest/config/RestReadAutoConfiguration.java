package org.ipro.rest.config;

import org.ipro.data.EntityReadAccess;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.security.C5RestOperationRegistrar;
import org.ipro.rest.security.RestReferenceAuthorizationValidator;
import org.ipro.rest.service.RestReadService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.springframework.beans.factory.ObjectProvider;
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
    @ConditionalOnBean({C5PermissionEvaluator.class, RlsDimensionRegistry.class})
    @ConditionalOnMissingBean
    public RestReferenceAuthorizationValidator restReferenceAuthorizationValidator(
            C5PermissionEvaluator c5Evaluator,
            RlsDimensionRegistry dimensionRegistry,
            ObjectProvider<RlsReadGate> readGateProvider) {
        return new RestReferenceAuthorizationValidator(c5Evaluator, dimensionRegistry, readGateProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnBean({RestResourceCatalog.class, RlsDimensionRegistry.class})
    @ConditionalOnMissingBean
    public C5RestOperationRegistrar c5RestOperationRegistrar(
            RestResourceCatalog catalog,
            RlsDimensionRegistry dimensionRegistry) {
        return new C5RestOperationRegistrar(catalog, dimensionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestReadService restReadService(
            RestResourceCatalog catalog,
            ObjectProvider<EntityReadAccess> readAccessProvider,
            ObjectProvider<C5PermissionEvaluator> c5EvaluatorProvider,
            ObjectProvider<RlsCurrentUser> currentUserProvider,
            ObjectProvider<RestReferenceAuthorizationValidator> referenceValidatorProvider) {

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
        return new RestReadService(catalog, readAccess, c5Evaluator, currentUser, referenceValidator);
    }
}
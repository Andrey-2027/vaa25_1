package org.ipro.rls.c5;

import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsRoleResolver;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Default implementation of C5 permission evaluator.
 * Queries the grant store through {@link C5ReadDecisionService} using STRICT exact matching
 * on dimension (excluding the RLS {@code "*"} wildcard) and only global {@code canRead} grants,
 * so the server decision is the same one shown by the grant admin (F-REST-READ-3, З2).
 */
@Component
public class DefaultC5PermissionEvaluator implements C5PermissionEvaluator {

    private final C5ReadDecisionService decisionService;

    public DefaultC5PermissionEvaluator(AccessGrantRepository grantRepository,
                                        RlsRoleResolver roleResolver) {
        this(new C5ReadDecisionService(grantRepository, roleResolver));
    }

    /** Kept for binary compatibility; C5 keys no longer derive from the RLS dimension registry. */
    public DefaultC5PermissionEvaluator(AccessGrantRepository grantRepository,
                                        RlsRoleResolver roleResolver,
                                        RlsDimensionRegistry ignoredDimensionRegistry) {
        this(grantRepository, roleResolver);
    }

    /** Production constructor: the shared read decision service owned by platform-rls. */
    public DefaultC5PermissionEvaluator(C5ReadDecisionService decisionService) {
        this.decisionService = Objects.requireNonNull(decisionService, "decisionService must not be null");
    }

    @Override
    public boolean isApiPermitted(String username, String resourceKey, int major, String operation) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(resourceKey, "resourceKey must not be null");
        Objects.requireNonNull(operation, "operation must not be null");

        String key = String.format("REST:%s:v%d:%s", resourceKey, major, operation);
        return decisionService.isReadGranted(key, username);
    }

    @Override
    public boolean isEntityReadPermitted(String username, Class<?> entityType) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");

        return decisionService.isReadGranted(C5PermissionKeys.entityRead(entityType), username);
    }

    @Override
    public boolean isAttributeReadPermitted(String username, Class<?> entityType, String persistentPath) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(persistentPath, "persistentPath must not be null");

        return decisionService.isReadGranted(
            C5PermissionKeys.attributeRead(entityType, persistentPath), username);
    }
}

package org.ipro.rls.c5;

import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsRoleResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Default implementation of C5 permission evaluator.
 * Queries AccessGrant store using STRICT exact matching on dimension (excluding RLS '*' wildcard).
 */
@Component
public class DefaultC5PermissionEvaluator implements C5PermissionEvaluator {

    private final AccessGrantRepository grantRepository;
    private final RlsRoleResolver roleResolver;
    private final org.ipro.rls.RlsDimensionRegistry dimensionRegistry;

    public DefaultC5PermissionEvaluator(AccessGrantRepository grantRepository,
                                        RlsRoleResolver roleResolver) {
        this(grantRepository, roleResolver, null);
    }

    public DefaultC5PermissionEvaluator(AccessGrantRepository grantRepository,
                                        RlsRoleResolver roleResolver,
                                        org.ipro.rls.RlsDimensionRegistry dimensionRegistry) {
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository must not be null");
        this.roleResolver = Objects.requireNonNull(roleResolver, "roleResolver must not be null");
        this.dimensionRegistry = dimensionRegistry;
    }

    @Override
    public boolean isApiPermitted(String username, String resourceKey, int major, String operation) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(resourceKey, "resourceKey must not be null");
        Objects.requireNonNull(operation, "operation must not be null");

        String key = String.format("REST:%s:v%d:%s", resourceKey, major, operation);
        return hasExactGrant(key, username);
    }

    @Override
    public boolean isEntityReadPermitted(String username, Class<?> entityType) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");

        if (dimensionRegistry != null) {
            org.ipro.rls.RlsPolicyDescriptor policy = dimensionRegistry.policyOf(entityType);
            for (String dim : policy.checkOnlyDimensions()) {
                if (dim.startsWith("ENTITY:") && hasExactGrant(dim, username)) {
                    return true;
                }
            }
        }

        String fqnKey = "ENTITY:" + entityType.getName();
        if (hasExactGrant(fqnKey, username)) {
            return true;
        }

        String key = "ENTITY:" + entityType.getSimpleName();
        return hasExactGrant(key, username);
    }

    @Override
    public boolean isAttributeReadPermitted(String username, Class<?> entityType, String persistentPath) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(persistentPath, "persistentPath must not be null");

        String fqnKey = "ATTR:" + entityType.getName() + ":" + persistentPath;
        if (hasExactGrant(fqnKey, username)) {
            return true;
        }

        String key = "ATTR:" + entityType.getSimpleName() + ":" + persistentPath;
        return hasExactGrant(key, username);
    }

    private boolean hasExactGrant(String dimension, String username) {
        // Direct user grants with exact dimension
        List<AccessGrant> userGrants = grantRepository.findBySubjectTypeAndSubjectKeyAndDimension(
            AccessGrant.SubjectType.USER, username, dimension);
        if (userGrants.stream().anyMatch(AccessGrant::isCanRead)) {
            return true;
        }

        // Role grants with exact dimension
        List<String> roles = roleResolver.rolesOf(username);
        for (String role : roles) {
            List<AccessGrant> roleGrants = grantRepository.findBySubjectTypeAndSubjectKeyAndDimension(
                AccessGrant.SubjectType.ROLE, role, dimension);
            if (roleGrants.stream().anyMatch(AccessGrant::isCanRead)) {
                return true;
            }
        }

        return false;
    }
}

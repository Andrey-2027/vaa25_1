package org.ipro.rest.security;

import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsPolicyDescriptor;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.c5.C5PermissionEvaluator;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validates and authorizes REST to-one references against C5 and effective RLS policy. */
public class RestReferenceAuthorizationValidator {

    private final C5PermissionEvaluator c5Evaluator;
    private final RlsDimensionRegistry dimensionRegistry;
    private final RlsReadGate readGate;

    public RestReferenceAuthorizationValidator(C5PermissionEvaluator c5Evaluator,
                                               RlsDimensionRegistry dimensionRegistry,
                                               RlsReadGate readGate,
                                               RestResourceCatalog catalog) {
        this.c5Evaluator = Objects.requireNonNull(c5Evaluator, "c5Evaluator must not be null");
        this.dimensionRegistry = Objects.requireNonNull(dimensionRegistry, "dimensionRegistry must not be null");
        this.readGate = readGate;
        validateCatalog(Objects.requireNonNull(catalog, "catalog must not be null"));
    }

    /** Compatibility constructor for focused unit tests; production wiring supplies the catalog. */
    public RestReferenceAuthorizationValidator(C5PermissionEvaluator c5Evaluator,
                                               RlsDimensionRegistry dimensionRegistry,
                                               RlsReadGate readGate) {
        this.c5Evaluator = Objects.requireNonNull(c5Evaluator, "c5Evaluator must not be null");
        this.dimensionRegistry = Objects.requireNonNull(dimensionRegistry, "dimensionRegistry must not be null");
        this.readGate = readGate;
    }

    /**
     * Checks the metadata proof at startup/request compilation time. Row-RLS targets are
     * accepted only when the root filters the exact same FK values under the same dimension.
     */
    public void validateReferencePath(Class<?> rootType, ResolvedRestPath path) {
        Objects.requireNonNull(rootType, "rootType must not be null");
        Objects.requireNonNull(path, "path must not be null");
        if (path.segments().size() != 2 || !path.segments().getFirst().association()) {
            throw new IllegalStateException("REST reference profile supports one to-one segment followed by one scalar/id: "
                + rootType.getName() + "." + path.source());
        }

        ResolvedRestPath.Segment association = path.segments().getFirst();
        ResolvedRestPath.Segment terminal = path.segments().getLast();
        Class<?> targetType = association.javaType();
        if (terminal.declaringType() != targetType) {
            throw new IllegalStateException("REST reference terminal is not declared by association target for "
                + rootType.getName() + "." + path.source());
        }

        RlsPolicyDescriptor targetPolicy = dimensionRegistry.policyOf(targetType);
        if (!targetPolicy.checkOnlyDimensions().isEmpty() && readGate == null) {
            throw new IllegalStateException("REST reference " + rootType.getName() + "." + path.source()
                + " targets " + targetType.getName() + " with CHECK_ONLY RLS but no RlsReadGate is configured");
        }

        Set<String> targetFilterable = targetPolicy.filterableDimensions();
        if (targetFilterable.isEmpty()) {
            return;
        }

        RlsPolicyDescriptor rootPolicy = dimensionRegistry.policyOf(rootType);
        for (String dimension : targetFilterable) {
            if (rootPolicy.dimensions().get(dimension) != RlsDimensionKind.FILTERABLE) {
                throw incompatible(rootType, path, dimension,
                    "root does not apply the target's FILTERABLE dimension");
            }
            RlsPolicyDescriptor.ValueRule targetRule = targetPolicy.valueRules().get(dimension);
            RlsPolicyDescriptor.ValueRule rootRule = rootPolicy.valueRules().get(dimension);
            if (targetRule == null || rootRule == null || targetRule.custom() || rootRule.custom()) {
                throw incompatible(rootType, path, dimension,
                    "custom or missing policy rules cannot prove source FK to target id equivalence");
            }
            List<String> translatedTargetPaths = targetRule.paths().stream()
                .map(targetPath -> association.name() + "." + targetPath)
                .toList();
            if (!rootRule.paths().equals(translatedTargetPaths)
                || rootRule.nullsNotApplicable() != targetRule.nullsNotApplicable()) {
                throw incompatible(rootType, path, dimension,
                    "root effective rule " + rootRule.paths() + " does not match target rule "
                        + targetRule.paths() + " through association " + association.name());
            }
        }
    }

    /**
     * Authorizes a to-one association path under the executable 3.4a/3.4b profile.
     * C5 grants are checked for every participating entity and persistent segment.
     */
    public boolean authorizeReferencePath(String username, Class<?> rootType, ResolvedRestPath path) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(rootType, "rootType must not be null");
        Objects.requireNonNull(path, "path must not be null");
        validateReferencePath(rootType, path);

        ResolvedRestPath.Segment association = path.segments().getFirst();
        ResolvedRestPath.Segment terminal = path.segments().getLast();
        Class<?> targetType = association.javaType();
        if (!c5Evaluator.isAttributeReadPermitted(username, rootType, association.name())
            || !c5Evaluator.isEntityReadPermitted(username, targetType)
            || !c5Evaluator.isAttributeReadPermitted(username, targetType, terminal.name())) {
            return false;
        }

        RlsPolicyDescriptor targetPolicy = dimensionRegistry.policyOf(targetType);
        return targetPolicy.checkOnlyDimensions().isEmpty() || readGate.canRead(targetType, username);
    }

    private void validateCatalog(RestResourceCatalog catalog) {
        for (ResolvedRestResource resource : catalog.resources()) {
            Class<?> rootType = resource.definition().resourceType();
            for (RestReadOperation operation : RestReadOperation.values()) {
                for (String alias : resource.projection(operation).maximumFields()) {
                    ResolvedRestPath path = resource.fields().get(alias).source();
                    validateCatalogPath(resource, operation.name(), alias, rootType, path);
                }
            }
            resource.filters().forEach((name, path) ->
                validateCatalogPath(resource, "LIST filter", name, rootType, path));
        }
    }

    private void validateCatalogPath(ResolvedRestResource resource, String operation,
                                     String alias, Class<?> rootType, ResolvedRestPath path) {
        if (path.segments().size() > 1) {
            try {
                validateReferencePath(rootType, path);
            } catch (IllegalStateException failure) {
                throw new IllegalStateException("REST reference policy is not executable for resource "
                    + resource.key() + ", operation " + operation + ", field/filter " + alias
                    + ", path " + path.source() + ": " + failure.getMessage(), failure);
            }
        }
    }

    private IllegalStateException incompatible(Class<?> rootType, ResolvedRestPath path,
                                                String dimension, String reason) {
        return new IllegalStateException("REST reference " + rootType.getName() + "." + path.source()
            + " is not proven safe for RLS dimension " + dimension + ": " + reason);
    }
}

package org.ipro.rest.security;

import jakarta.persistence.metamodel.Attribute;
import org.ipro.rest.api.RestFieldFormat;
import org.ipro.rest.api.RestFieldType;
import org.ipro.rest.api.RestNullability;
import org.ipro.rest.api.RestPropertyPath;
import org.ipro.rest.api.RestResourceDefinition;
import org.ipro.rest.catalog.ResolvedRestField;
import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestResourceKey;
import org.ipro.rest.catalog.RestResourceProjection;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsPolicyDescriptor;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestReferenceAuthorizationValidatorTest {

    static class Root { }
    static class Journal { }

    @Test
    void rejectsSharedDimensionWhenRootPolicyUsesDifferentFk() {
        RlsDimensionRegistry registry = mock(RlsDimensionRegistry.class);
        when(registry.policyOf(Root.class)).thenReturn(policy(Root.class,
            Map.of("JOURNAL", RlsDimensionKind.FILTERABLE),
            Map.of("JOURNAL", rule("otherJournal.id"))));
        when(registry.policyOf(Journal.class)).thenReturn(policy(Journal.class,
            Map.of("JOURNAL", RlsDimensionKind.FILTERABLE),
            Map.of("JOURNAL", rule("id"))));

        RestReferenceAuthorizationValidator validator = validator(registry, mock(C5PermissionEvaluator.class), null);

        assertThatThrownBy(() -> validator.validateReferencePath(Root.class, journalIdPath()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not match target rule");
    }

    @Test
    void rejectsTargetAdditionalFilterableDimensionEvenWhenNamesPartiallyOverlap() {
        RlsDimensionRegistry registry = mock(RlsDimensionRegistry.class);
        when(registry.policyOf(Root.class)).thenReturn(policy(Root.class,
            Map.of("JOURNAL", RlsDimensionKind.FILTERABLE),
            Map.of("JOURNAL", rule("journal.id"))));
        when(registry.policyOf(Journal.class)).thenReturn(policy(Journal.class,
            Map.of("JOURNAL", RlsDimensionKind.FILTERABLE, "BRANCH", RlsDimensionKind.FILTERABLE),
            Map.of("JOURNAL", rule("id"), "BRANCH", rule("branch.id"))));

        RestReferenceAuthorizationValidator validator = validator(registry, mock(C5PermissionEvaluator.class), null);

        assertThatThrownBy(() -> validator.validateReferencePath(Root.class, journalIdPath()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("BRANCH");
    }

    @Test
    void acceptsOnlyExactRootToTargetFilterEquivalenceAndChecksC5() {
        RlsDimensionRegistry registry = mock(RlsDimensionRegistry.class);
        when(registry.policyOf(Root.class)).thenReturn(policy(Root.class,
            Map.of("JOURNAL", RlsDimensionKind.FILTERABLE),
            Map.of("JOURNAL", rule("journal.id"))));
        when(registry.policyOf(Journal.class)).thenReturn(policy(Journal.class,
            Map.of("JOURNAL", RlsDimensionKind.FILTERABLE),
            Map.of("JOURNAL", rule("id"))));
        C5PermissionEvaluator c5 = mock(C5PermissionEvaluator.class);
        when(c5.isAttributeReadPermitted("alice", Root.class, "journal")).thenReturn(true);
        when(c5.isEntityReadPermitted("alice", Journal.class)).thenReturn(true);
        when(c5.isAttributeReadPermitted("alice", Journal.class, "id")).thenReturn(true);

        RestReferenceAuthorizationValidator validator = validator(registry, c5, null);

        assertThat(validator.authorizeReferencePath("alice", Root.class, journalIdPath())).isTrue();
    }

    @Test
    void requiresClassGateForCheckOnlyTargetAtConfigurationTime() {
        RlsDimensionRegistry registry = mock(RlsDimensionRegistry.class);
        when(registry.policyOf(Root.class)).thenReturn(policy(Root.class, Map.of(), Map.of()));
        when(registry.policyOf(Journal.class)).thenReturn(policy(Journal.class,
            Map.of(C5PermissionKeys.entityRead(Journal.class), RlsDimensionKind.CHECK_ONLY),
            Map.of(C5PermissionKeys.entityRead(Journal.class), rule("id"))));

        RestReferenceAuthorizationValidator validator = validator(registry, mock(C5PermissionEvaluator.class), null);

        assertThatThrownBy(() -> validator.validateReferencePath(Root.class, journalIdPath()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no RlsReadGate");
    }

    @Test
    void productionConstructorValidatesDeclaredReferenceProfileAtStartup() {
        RlsDimensionRegistry registry = mock(RlsDimensionRegistry.class);
        when(registry.policyOf(Root.class)).thenReturn(policy(Root.class,
            Map.of("JOURNAL", RlsDimensionKind.FILTERABLE),
            Map.of("JOURNAL", rule("otherJournal.id"))));
        when(registry.policyOf(Journal.class)).thenReturn(policy(Journal.class,
            Map.of("JOURNAL", RlsDimensionKind.FILTERABLE),
            Map.of("JOURNAL", rule("id"))));

        ResolvedRestResource resource = mock(ResolvedRestResource.class);
        RestResourceDefinition<?> definition = mock(RestResourceDefinition.class);
        org.mockito.Mockito.doReturn(Root.class).when(definition).resourceType();
        when(resource.key()).thenReturn(new RestResourceKey("specifications", 1));
        when(resource.definition()).thenReturn((RestResourceDefinition) definition);
        ResolvedRestPath path = journalIdPath();
        when(resource.fields()).thenReturn(Map.of("journalId", new ResolvedRestField(
            new RestResourceDefinition.Field("journalId", RestFieldType.LONG,
                RestNullability.NULLABLE, RestFieldFormat.NONE, new RestPropertyPath("journal.id")), path)));
        when(resource.filters()).thenReturn(Map.of());
        when(resource.projection(RestReadOperation.LIST)).thenReturn(new RestResourceProjection(
            RestReadOperation.LIST, List.of("journalId"), List.of("journalId"), Map.of()));
        when(resource.projection(RestReadOperation.DETAIL)).thenReturn(new RestResourceProjection(
            RestReadOperation.DETAIL, List.of(), List.of(), Map.of()));
        RestResourceCatalog catalog = mock(RestResourceCatalog.class);
        when(catalog.resources()).thenReturn(List.of(resource));

        assertThatThrownBy(() -> new RestReferenceAuthorizationValidator(
            mock(C5PermissionEvaluator.class), registry, mock(RlsReadGate.class), catalog))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("field/filter journalId")
            .hasMessageContaining("does not match target rule");
    }

    private RestReferenceAuthorizationValidator validator(RlsDimensionRegistry registry,
                                                           C5PermissionEvaluator c5,
                                                           RlsReadGate readGate) {
        return new RestReferenceAuthorizationValidator(c5, registry, readGate);
    }

    private RlsPolicyDescriptor policy(Class<?> type,
                                       Map<String, RlsDimensionKind> dimensions,
                                       Map<String, RlsPolicyDescriptor.ValueRule> rules) {
        return new RlsPolicyDescriptor(type, true, dimensions, rules);
    }

    private RlsPolicyDescriptor.ValueRule rule(String path) {
        return new RlsPolicyDescriptor.ValueRule(List.of(path), false, false);
    }

    private ResolvedRestPath journalIdPath() {
        return new ResolvedRestPath("journal.id", List.of(
            new ResolvedRestPath.Segment("journal", Root.class, Journal.class,
                Attribute.PersistentAttributeType.MANY_TO_ONE, true, true),
            new ResolvedRestPath.Segment("id", Journal.class, Long.class,
                Attribute.PersistentAttributeType.BASIC, false, false)), Long.class, true);
    }
}

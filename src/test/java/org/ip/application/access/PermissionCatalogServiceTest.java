package org.ip.application.access;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ip.application.catalog.NamedOperationOwner;
import org.ip.application.catalog.NamedOperationSource;
import org.ip.persistence.access.StoredGrantKeyReader;
import org.ipro.crud.BaseEntity;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Subsystem;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsPolicyDescriptor;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionDefinition;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.ipro.settings.SettingsRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionCatalogServiceTest {
    @jakarta.persistence.Entity
    static class BareCatalog extends org.ipro.crud.StandardCatalogEntity { }

    @Test void annotationlessRootIsGroupedWithoutRequiringARawDeclaration() {
        root(BareCatalog.class, Set.of(DataOperation.CREATE));
        when(metadata.resolve(BareCatalog.class)).thenReturn(new MetadataResolver().resolve(BareCatalog.class));
        var catalog = service().catalog();
        assertThat(catalog.row(PermissionObjectRow.entityId(BareCatalog.class)).orElseThrow().groupId())
            .isEqualTo("aux:unassigned");
        assertThat(catalog.groups()).anyMatch(g -> g.id().equals("aux:unassigned") && g.title().equals("Без подсистемы"));
    }
    EntityDescriptorCatalog descriptors = mock(EntityDescriptorCatalog.class);
    MetadataResolver metadata = mock(MetadataResolver.class);
    SubsystemRegistry subsystems = mock(SubsystemRegistry.class);
    RlsDimensionRegistry dimensions = mock(RlsDimensionRegistry.class);
    SettingsRegistry settings = mock(SettingsRegistry.class);
    C5OperationPermissionRegistry operations = new C5OperationPermissionRegistry();
    C5ReadPermissionRegistry reads = new C5ReadPermissionRegistry(operations);
    @SuppressWarnings("unchecked") ObjectProvider<RestResourceCatalog> rest = mock(ObjectProvider.class);
    StoredGrantKeyReader storedKeys = mock(StoredGrantKeyReader.class);
    List<EntityDescriptor> types = new ArrayList<>();
    Map<String, Class<?>> sources = new HashMap<>();
    Map<String, RlsDimensionKind> kinds = new HashMap<>();
    Map<Class<?>, Set<String>> restrictions = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(descriptors.all()).thenAnswer(invocation -> List.copyOf(types));
        when(subsystems.getRoots()).thenReturn(List.of());
        when(settings.groups()).thenReturn(List.of());
        when(dimensions.dimensions()).thenAnswer(invocation -> Set.copyOf(kinds.keySet()));
        when(dimensions.kindOf(anyString())).thenAnswer(invocation -> kinds.get(invocation.getArgument(0)));
        when(dimensions.grantValueDimensions()).thenAnswer(invocation -> Set.copyOf(sources.keySet()));
        when(dimensions.grantValueType(anyString())).thenAnswer(invocation -> sources.get(invocation.getArgument(0)));
        when(dimensions.policyOf(any())).thenAnswer(invocation -> {
            Class<?> type = invocation.getArgument(0);
            Map<String, RlsDimensionKind> policyKinds = new HashMap<>();
            Map<String, RlsPolicyDescriptor.ValueRule> rules = new HashMap<>();
            for (String key : restrictions.getOrDefault(type, Set.of())) {
                policyKinds.put(key, RlsDimensionKind.FILTERABLE);
                rules.put(key, new RlsPolicyDescriptor.ValueRule(List.of("id"), false, false));
            }
            return new RlsPolicyDescriptor(type, true, policyKinds, rules);
        });
    }

    private PermissionCatalogService service() { return service(List.of()); }
    private PermissionCatalogService service(List<NamedOperationOwner> titles) {
        return service(titles, List.of());
    }
    private PermissionCatalogService service(List<NamedOperationOwner> titles, List<NamedOperationSource> sources) {
        return new PermissionCatalogService(descriptors, metadata, subsystems, dimensions, reads, operations,
            settings, titles, sources, List.of(), rest, storedKeys);
    }

    private void root(Class<?> type, Set<DataOperation> writes) {
        types.add(new EntityDescriptor(type, EntityExposure.STANDARD_ROOT, true, true,
            new EntityCapabilities(Set.of(FetchScenario.LIST, FetchScenario.DETAIL), writes, "fixture"), "fixture"));
        EntityMetadataInfo info = mock(EntityMetadataInfo.class);
        EntityMetadata annotation = mock(EntityMetadata.class);
        doReturn(Subsystem.NoSubsystem.class).when(annotation).subsystem();
        when(info.getAnnotation()).thenReturn(annotation);
        doReturn(Subsystem.NoSubsystem.class).when(info).getSubsystem();
        when(info.getListFormTitle()).thenReturn(type.getSimpleName());
        when(metadata.resolve(type)).thenReturn(info);
        var supported = java.util.EnumSet.of(C5Operation.READ);
        writes.forEach(operation -> supported.add(C5Operation.valueOf(operation.name())));
        operations.register(C5PermissionDefinition.entityOperations(type, supported));
    }

    @Test
    void aNewRootAppearsWithOneKeyAndOneAuthorityForEveryColumn() {
        root(Warehouse.class, Set.of(DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE));
        var row = service().catalog().row(PermissionObjectRow.entityId(Warehouse.class)).orElseThrow();
        assertThat(row.columns()).extracting(PermissionObjectRow.Column::title)
            .containsExactly("Чтение", "Создание и изменение", "Удаление");
        assertThat(row.columns().get(0).binding().key()).isEqualTo(C5PermissionKeys.entityRead(Warehouse.class));
        assertThat(row.columns().get(1).binding().key()).isEqualTo(C5PermissionKeys.entityOperations(Warehouse.class));
        assertThat(row.columns()).extracting(column -> column.binding().key()).containsOnly(C5PermissionKeys.entity(Warehouse.class));
        assertThat(row.columns()).extracting(column -> column.binding().authority()).containsOnly(PermissionBinding.Authority.C5);
        assertThat(row.columns().get(1).binding().operations()).containsExactlyInAnyOrder(C5Operation.CREATE, C5Operation.UPDATE);
    }

    @Test
    void readonlyAndCreateOnlyCapabilitiesDoNotAcquirePhantomOperations() {
        root(Warehouse.class, Set.of(DataOperation.CREATE));
        root(Invoice.class, Set.of());
        var catalog = service().catalog();
        assertThat(catalog.row(PermissionObjectRow.entityId(Warehouse.class)).orElseThrow().columns())
            .extracting(PermissionObjectRow.Column::title).containsExactly("Чтение", "Создание");
        assertThat(catalog.row(PermissionObjectRow.entityId(Invoice.class)).orElseThrow().columns())
            .extracting(PermissionObjectRow.Column::title).containsExactly("Чтение");
    }

    @Test
    void aThirdRegisteredValueSourceBuildsOneSharedScopeForAllItsConsumers() {
        root(Warehouse.class, Set.of(DataOperation.CREATE));
        root(Invoice.class, Set.of(DataOperation.CREATE));
        sources.put("WAREHOUSE", Warehouse.class);
        kinds.put("WAREHOUSE", RlsDimensionKind.FILTERABLE);
        restrictions.put(Warehouse.class, Set.of("WAREHOUSE"));
        restrictions.put(Invoice.class, Set.of("WAREHOUSE"));
        var catalog = service().catalog();
        assertThat(catalog.scopes()).hasSize(1);
        var scope = catalog.scope("WAREHOUSE").orElseThrow();
        assertThat(scope.valueType()).isEqualTo(Warehouse.class);
        assertThat(scope.affectedObjectIds()).containsExactlyInAnyOrder(
            PermissionObjectRow.entityId(Warehouse.class), PermissionObjectRow.entityId(Invoice.class));
        assertThat(scope.columns()).extracting(column -> column.binding().key()).containsOnly("WAREHOUSE");
    }

    @Test
    void aConsumerWithoutARegisteredValueSourceIsDiagnosedRatherThanSilentlyIgnored() {
        root(Invoice.class, Set.of());
        restrictions.put(Invoice.class, Set.of("WAREHOUSE"));
        kinds.put("WAREHOUSE", RlsDimensionKind.FILTERABLE);
        var catalog = service().catalog();
        assertThat(catalog.scopes()).isEmpty();
        assertThat(catalog.diagnostics()).anyMatch(diagnostic -> diagnostic.code().equals("MISSING_SCOPE_SOURCE")
            && diagnostic.key().equals("WAREHOUSE"));
    }

    @Test
    void explicitlyRegisteredReadKeysAreNotGuessedFromTheirPrefix() {
        reads.registerReadKey("REST:custom:v1:LIST");
        kinds.put("ENTITY:LooksLikeRead", RlsDimensionKind.CHECK_ONLY);
        var catalog = service().catalog();
        assertThat(catalog.representedKeys()).contains("REST:custom:v1:LIST").doesNotContain("ENTITY:LooksLikeRead");
        assertThat(catalog.diagnosticKeys()).contains("ENTITY:LooksLikeRead");
        assertThat(catalog.rows()).noneMatch(row -> row.columns().stream()
            .anyMatch(column -> column.binding().key().equals("ENTITY:LooksLikeRead")));
    }

    @Test
    void ownedAndInternalTypesDoNotBecomeGrantableCrudRowsEvenWithASpuriousDefinition() {
        reads.registerReadKey(C5PermissionKeys.entityRead(Warehouse.class));
        for (EntityExposure exposure : List.of(EntityExposure.OWNED_ROW, EntityExposure.INTERNAL_STORE)) {
            types.clear();
            types.add(new EntityDescriptor(Warehouse.class, exposure, true, false,
                new EntityCapabilities(Set.of(), Set.of(), "hidden"), "hidden"));
            var catalog = service().catalog();
            assertThat(catalog.rows()).isEmpty();
            assertThat(catalog.diagnosticKeys()).contains(C5PermissionKeys.entityRead(Warehouse.class));
        }
    }

    @Test
    void hierarchyUsesMarkerIdentityRatherThanMergingEqualTitlesAndIsImmutable() {
        Subsystem sameTitle = mock(Subsystem.class);
        when(sameTitle.title()).thenReturn("Производство");
        SubsystemNode first = new SubsystemNode(FirstModule.class, sameTitle);
        SubsystemNode second = new SubsystemNode(SecondModule.class, sameTitle);
        when(subsystems.getRoots()).thenReturn(List.of(first, second));
        root(Warehouse.class, Set.of());
        var info = metadata.resolve(Warehouse.class);
        var annotation = info.getAnnotation();
        doReturn(SecondModule.class).when(annotation).subsystem();
        doReturn(SecondModule.class).when(info).getSubsystem();
        var catalog = service().catalog();
        assertThat(catalog.groups()).hasSize(2).extracting(PermissionCatalog.Group::id).doesNotHaveDuplicates();
        assertThat(catalog.row(PermissionObjectRow.entityId(Warehouse.class)).orElseThrow().groupId())
            .isEqualTo(PermissionCatalogService.subsystemId(SecondModule.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> catalog.groups().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void namedExecutionHasItsOwnFlagAndLaterRegistrationsAppearInTheNextSnapshot() {
        NamedOperationOwner owner = new NamedOperationOwner() {
            public String permissionId() { return "PILOT:POST"; }
            public String title() { return "Провести документ"; }
        };
        var service = service(List.of(owner));
        assertThat(service.catalog().rows()).isEmpty();
        operations.register(C5PermissionDefinition.action(owner.permissionId()));
        var row = service.catalog().rows().findFirst().orElseThrow();
        assertThat(row.kind()).isEqualTo(PermissionObjectRow.Kind.ACTION);
        assertThat(row.title()).isEqualTo("Провести документ");
        assertThat(row.columns().getFirst().title()).isEqualTo("Выполнение");
        assertThat(row.columns().getFirst().binding().flag()).isEqualTo(PermissionBinding.Flag.UPDATE);
    }

    /**
     * S5(а): источник, объявляющий несколько операций, для каталога неотличим от одиночных
     * владельцев — ровно поэтому приложению достаточно одного адаптера к платформенному списку.
     */
    @Test
    void oneSourceCanNameSeveralOperationsLikeSeveralSingleOwners() {
        NamedOperationSource registerTitles = () -> List.of(
            single("REGISTER:Prices:CHANGE_KEY", "Изменить ключ записи: Цены"),
            single("REGISTER:Prices:SET_KEY", "Записать по ключу: Цены"));
        var service = service(List.of(single("PILOT:POST", "Провести документ")), List.of(registerTitles));
        for (String permissionId : List.of("REGISTER:Prices:CHANGE_KEY", "REGISTER:Prices:SET_KEY", "PILOT:POST")) {
            operations.register(C5PermissionDefinition.action(permissionId));
        }

        assertThat(service.catalog().rows()).extracting(PermissionObjectRow::title)
            .containsExactlyInAnyOrder("Изменить ключ записи: Цены", "Записать по ключу: Цены",
                "Провести документ");
        assertThat(service.catalog().diagnostics())
            .as("названные операции не должны попадать в отчёт о безымянных определениях")
            .noneMatch(diagnostic -> diagnostic.code().equals("MISSING_ACTION_TITLE"));
    }

    private static NamedOperationOwner single(String permissionId, String title) {
        return new NamedOperationOwner() {
            public String permissionId() { return permissionId; }
            public String title() { return title; }
        };
    }

    @Test
    void unsupportedDeclaredWritesAreReportedAndNotOfferedAsCapabilities() {
        root(Warehouse.class, Set.of(DataOperation.CREATE));
        // New registry instance simulates a broken registrar without altering the capability.
        C5OperationPermissionRegistry wrong = new C5OperationPermissionRegistry();
        wrong.register(C5PermissionDefinition.entityOperations(Warehouse.class,
            Set.of(C5Operation.READ, C5Operation.CREATE, C5Operation.UPDATE, C5Operation.DELETE)));
        var service = new PermissionCatalogService(descriptors, metadata, subsystems, dimensions, reads, wrong,
            settings, List.of(), List.of(), List.of(), rest, storedKeys);
        var catalog = service.catalog();
        assertThat(catalog.row(PermissionObjectRow.entityId(Warehouse.class)).orElseThrow().columns())
            .extracting(PermissionObjectRow.Column::title).containsExactly("Чтение", "Создание");
        assertThat(catalog.diagnostics()).anyMatch(diagnostic -> diagnostic.code().equals("WRITE_CAPABILITY_DEFINITION_MISMATCH"));
    }

    @Test
    void historicalFieldUnknownAndWildcardKeysHaveExplicitDiagnosticPaths() {
        var diagnostics = service().diagnosticsForStoredKeys(List.of("ATTR:example.Type:secret", "UNDECLARED", "*"));
        assertThat(diagnostics).extracting(PermissionCatalog.Diagnostic::code)
            .containsExactly("RLS_WILDCARD", "OBSOLETE_FIELD_GRANT", "UNREGISTERED_STORED_KEY");
    }

    // No metadata/RLS annotations: unit fixtures must not enter Spring's org.ip runtime scan.
    static class Warehouse extends BaseEntity { }
    static class Invoice extends BaseEntity { }
    interface FirstModule { }
    interface SecondModule { }
}

package org.ip.views.admin;

import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.ipro.data.*;
import org.ipro.design.*;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.registry.FormType;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.vaadin.explorer.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityDraftCatalogAdapterTest {
    @Test void runtimeClassifiedRegistersAreNotOfferedAsReferenceTargetsEvenWithRootLongIdentity() {
        String id = Root.class.getName();
        var type = new RuntimeDesignSnapshot.TypeFacts(id, "INFORMATION_REGISTER", "DERIVED", "registry", "STANDARD_ROOT",
            "PLATFORM_DEFAULT", "", "DERIVED", "", Set.of("LOOKUP"), Set.of(), false, false, "",
            List.of(), List.of(), List.of(), List.of(), List.of(), "", List.of());
        var runtime = new RuntimeDesignSnapshot("register", true, Map.of(id, type), Map.of());
        var view = snapshot(List.of(entry(Root.class, EntityExposure.STANDARD_ROOT)), Map.of(Root.class, summary(true, true)));
        var target = new EntityDraftCatalogAdapter().adapt(view, Set.of(Root.class), Map.of(), "p", "example", runtime)
            .context().referenceTargets().get(id);
        assertThat(target.supported()).isFalse();
        assertThat(target.reason()).contains("Регистр");
    }
    @Test void portableRuntimeFactsAreAttachedWithoutRevalidatingKindsOrChangingTheDraftCatalog() {
        var view=snapshot(List.of(entry(Root.class,EntityExposure.STANDARD_ROOT)),Map.of(Root.class,summary(true,true)));
        var facts=new RuntimeDesignSnapshot("runtime:1",true,Map.of(),Map.of());
        var adapter=new EntityDraftCatalogAdapter();
        var old=adapter.adapt(view,Set.of(Root.class),Map.of(),"p","example");
        var current=adapter.adapt(view,Set.of(Root.class),Map.of(),"p","example",facts);
        assertThat(current.context().runtimeSnapshot()).isSameAs(facts);
        assertThat(current.context().referenceTargets()).isEqualTo(old.context().referenceTargets());
        assertThat(current.context()).isNotEqualTo(old.context());
    }
    @Table(name="existing_root") static class Root { @Id Long id; }
    @Table(name="existing_row") static class Row { @Id Long id; }
    @Table(name="internal_store") static class Internal { @Id Long id; }
    @Table(name="string_root") static class StringRoot { @Id String id; }
    static class Implicit { @Id Long id; }
    static EntitySummary summary(boolean presentation, boolean selection) {
        var summary = mock(EntitySummary.class);
        when(summary.structure()).thenReturn(new EntitySummary.StructureFacts(true, List.of(), List.of(), presentation ? new EntitySummary.PresentationFacts("FILE",List.of(),"Type#id","","") : null));
        when(summary.forms()).thenReturn(selection ? List.of(new EntitySummary.FormRow(FormType.SELECTION,"default","DEFAULT",true,"")) : List.of());
        return summary;
    }
    static ExplorerSnapshot.Entry entry(Class<?> type, EntityExposure exposure) {
        var descriptor = exposure == null ? null : new EntityDescriptor(type, exposure, true, true, new EntityCapabilities(Set.of(FetchScenario.LOOKUP),Set.of(),""),"fixture");
        return new ExplorerSnapshot.Entry(type,type.getName(),type.getSimpleName(),ResolvedValue.fact(type.getSimpleName(), FactOrigin.DERIVED,""),EntityKind.CATALOG,
            descriptor, Optional.empty(),Optional.empty(),List.of(),ExplorerSnapshot.EntryState.READY,"",List.of(),0,0,true);
    }
    static ExplorerTreeModel.CatalogView snapshot(List<ExplorerSnapshot.Entry> entries, Map<Class<?>,EntitySummary> summaries) {
        return new ExplorerTreeModel.CatalogView() {
            public List<ExplorerSnapshot.Entry> roots() { return entries; }
            public Optional<EntitySummary> summaryOf(Class<?> type) { return Optional.ofNullable(summaries.get(type)); }
            public List<ExplorerSnapshot.OwnedSection> sectionsOf(Class<?> type) { throw new AssertionError("No additional registry query"); }
            public List<ExplorerSnapshot.SearchTerm> searchTerms() { throw new AssertionError("No extra scan"); }
        };
    }
    @Test void projectsKnownFactsWithoutUrlsInstancesOrBusinessLookups() {
        var view = snapshot(List.of(entry(Root.class,EntityExposure.STANDARD_ROOT),entry(Row.class,EntityExposure.OWNED_ROW),entry(Internal.class,EntityExposure.INTERNAL_STORE)),
            Map.of(Root.class,summary(true,true),Row.class,summary(true,true),Internal.class,summary(true,true)));
        var result = new EntityDraftCatalogAdapter().adapt(view,Set.of(Root.class,Row.class,Internal.class),Map.of("subsystem.id","Справочники"),"project","org.example");
        assertThat(result.context().referenceTargets().get(Root.class.getName()).supported()).isTrue();
        assertThat(result.context().referenceTargets().get(Row.class.getName()).supported()).isFalse();
        assertThat(result.context().referenceTargets().get(Internal.class.getName()).supported()).isFalse();
        assertThat(result.context().existingTypes().values()).contains(Root.class.getName(),Row.class.getName(),Internal.class.getName());
        assertThat(result.context().existingTables().values()).containsExactlyInAnyOrder("existing_root","existing_row","internal_store");
        assertThat(result.context().existingTables().complete()).isTrue(); assertThat(result.context().defaultPackage()).isEqualTo("org.example");
        assertThat(result.subsystems()).containsEntry("subsystem.id","Справочники");
        assertThat(new EntityDraftCatalogAdapter().adapt(view,Set.of(Root.class,Row.class,Internal.class),Map.of("subsystem.id","Справочники"),"project","org.example")).isEqualTo(result);
    }
    @Test void missingFactsAndWrongIdentityNeverBecomeSupported() {
        var entries = List.of(entry(Root.class,EntityExposure.STANDARD_ROOT),entry(StringRoot.class,EntityExposure.STANDARD_ROOT),entry(Implicit.class,null));
        var result = new EntityDraftCatalogAdapter().adapt(snapshot(entries,Map.of(Root.class,summary(false,true),StringRoot.class,summary(true,true))),Set.of(Root.class,StringRoot.class,Implicit.class),Map.of(),"p","");
        assertThat(result.context().referenceTargets().values()).noneMatch(ReferenceTargetDescriptor::supported);
        assertThat(result.context().referenceTargets().get(Root.class.getName()).eligibility()).isEqualTo(ReferenceTargetDescriptor.Eligibility.UNKNOWN);
        assertThat(result.context().referenceTargets().get(StringRoot.class.getName()).eligibility()).isEqualTo(ReferenceTargetDescriptor.Eligibility.UNSUPPORTED);
        assertThat(result.context().existingTables().complete()).isFalse(); assertThat(result.context().defaultPackage()).isNull();
    }
    @Test void absentDefaultSelectionIsUnsupportedAndUnannotatedManagedTypesStillReserveNames() {
        var result = new EntityDraftCatalogAdapter().adapt(snapshot(List.of(entry(Root.class,EntityExposure.STANDARD_ROOT)),Map.of(Root.class,summary(true,false))),Set.of(Root.class,Row.class),Map.of(),"p","org.example");
        assertThat(result.context().referenceTargets().get(Root.class.getName()).supported()).isFalse();
        assertThat(result.context().referenceTargets().get(Row.class.getName()).eligibility()).isEqualTo(ReferenceTargetDescriptor.Eligibility.UNKNOWN);
        assertThat(result.context().existingTypes().values()).contains(Row.class.getName());
    }
}

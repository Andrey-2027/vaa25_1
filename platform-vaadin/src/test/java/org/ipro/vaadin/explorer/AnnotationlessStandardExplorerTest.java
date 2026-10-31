package org.ipro.vaadin.explorer;

import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.FormRouteAliasDeclaration;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.*;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AnnotationlessStandardExplorerTest {
    @Entity static class BareCatalog extends StandardCatalogEntity { }
    @Entity static class Internal extends BaseEntity { }

    @Test void noSubsystemEntityStaysVisibleAndGetsDefaultRoutesWithoutManualForms() {
        var types = new ManagedEntityTypes(List.of(BareCatalog.class, Internal.class));
        var resolver = new MetadataResolver();
        var sections = new SectionMetadataRegistry(types, resolver); sections.rebuild();
        var descriptors = new EntityDescriptorCatalog(new ManagedEntityCatalog(types), sections, resolver, List.of());
        var forms = new FormRegistry();
        var routes = FormRouteCatalog.build(descriptors, forms, List.of());
        assertThat(routes.find(BareCatalog.class).orElseThrow().entityKey()).isEqualTo("bare-catalog");
        assertThat(routes.find(Internal.class)).isEmpty();
        var aliases = FormRouteCatalog.build(descriptors, forms, List.of(
            new FormRouteAliasDeclaration(BareCatalog.class, "catalog", List.of("old-catalog"), "compatibility")));
        assertThat(aliases.find("old-catalog")).isPresent();
        var subsystems = new SubsystemRegistry("org.ipro.vaadin.explorer", types, resolver); subsystems.rebuild();
        var numbering = new NumberingMetadataRegistry(types, MetadataResolver::isApplicable); numbering.rebuild();
        var assembler = new EntitySummaryAssembler(types, resolver, forms, new ReferenceIndex(types),
            numbering, subsystems, FacetResolver.none(), descriptors, routes, null, sections,
            null, null, null, null, null, null);
        assertThat(assembler.entities()).extracting(EntitySummaryAssembler.EntityRef::entityClass)
            .containsExactly(BareCatalog.class);
        var summary = assembler.summarize(BareCatalog.class);
        assertThat(summary.displayName().value()).isEqualTo("BareCatalog");
        assertThat(summary.fieldsForm()).extracting(EntitySummary.FieldRow::name).containsExactly("code", "name");
        var dimensions = org.mockito.Mockito.mock(org.ipro.rls.RlsDimensionRegistry.class);
        assertThat(new SubsystemSummaryAssembler(assembler, subsystems, dimensions).catalog().unassigned())
            .extracting(SubsystemSummaryAssembler.EntityFacet::entityClass).containsExactly(BareCatalog.class);
    }
}

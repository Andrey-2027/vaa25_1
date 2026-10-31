package org.ip.views.admin;

import org.ip.model.NomAttributeValue;
import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.junit.jupiter.api.Test;
import org.ip.views.admin.fixture.EntityExplorerDensityFixture;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import jakarta.persistence.Entity;
import jakarta.validation.constraints.Size;
import org.ipro.metadata.FactOrigin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EntityExplorerPilotBoundaryTest {
    @Test
    void pilotRequiresOptInAndAnAllowedTypeWithoutLegacyAnchor() {
        var disabled = new EntityExplorerPilotPolicy(false);
        var enabled = new EntityExplorerPilotPolicy(true);
        assertThat(disabled.opensPilot(Nomenclature.class, null)).isFalse();
        assertThat(enabled.opensPilot(Nomenclature.class, null)).isTrue();
        assertThat(enabled.opensPilot(ReceivingDocument.class, "")).isTrue();
        assertThat(enabled.opensPilot(NomenclaturePrice.class, null)).isFalse();
        assertThat(enabled.opensPilot(null, null)).isFalse();
        for (String anchor : new String[]{"fields", "fields/form", "unknown", " "}) {
            assertThat(enabled.opensPilot(Nomenclature.class, anchor)).isFalse();
        }
    }

    @Test
    void sameFieldNameKeepsRootAndOwnedContextsDistinct() {
        var section = new EntityExplorerPilotPlace.OwnedSection("nomenclature", NomAttributeValue.class);
        var rootField = new EntityExplorerPilotPlace.RootField("name");
        var sectionField = new EntityExplorerPilotPlace.SectionField(section, "name");
        assertThat(rootField).isNotEqualTo(sectionField);
        assertThat(sectionField).isNotEqualTo(new EntityExplorerPilotPlace.SectionField(
            new EntityExplorerPilotPlace.OwnedSection("document", ReceivingDocumentItem.class), "name"));
        assertThat(new EntityExplorerPilotPlace(Nomenclature.class, EntityExplorerPilotPlace.Tab.DATA, sectionField))
            .isNotEqualTo(new EntityExplorerPilotPlace(ReceivingDocument.class, EntityExplorerPilotPlace.Tab.DATA, sectionField));
        assertThatIllegalArgumentException().isThrownBy(() -> new EntityExplorerPilotPlace(
            Nomenclature.class, EntityExplorerPilotPlace.Tab.FORMS, rootField));
    }

    @Test
    void localPlacesStaySeparateFromLegacyAnchorsAndClearWithUiContext() {
        var state = new ExplorerUiState();
        state.remember(Nomenclature.class, "fields/form");
        var place = new EntityExplorerPilotPlace(Nomenclature.class, EntityExplorerPilotPlace.Tab.DATA,
            new EntityExplorerPilotPlace.RootField("name"));
        state.rememberPilotPlace(place);
        assertThat(state.placeOf(Nomenclature.class)).isEqualTo("fields/form");
        assertThat(state.pilotPlaceOf(Nomenclature.class)).isEqualTo(place);
        assertThat(state.pilotPlaceOf(ReceivingDocument.class))
            .isEqualTo(EntityExplorerPilotPlace.general(ReceivingDocument.class));
        state.clear();
        assertThat(state.pilotPlaceOf(Nomenclature.class))
            .isEqualTo(EntityExplorerPilotPlace.general(Nomenclature.class));
    }

    @Test
    void densityFixtureProvidesThirtyFieldsWithoutJoiningTheEntityCatalog() {
        assertThat(EntityExplorerDensityFixture.class.isAnnotationPresent(Entity.class)).isFalse();
        assertThat(EntityExplorerDensityFixture.class.isAnnotationPresent(EntityMetadata.class)).isFalse();
        var fields = new MetadataResolver().resolveAllAnnotatedFields(EntityExplorerDensityFixture.class);
        assertThat(fields).hasSize(30);
        assertThat(fields).extracting(f -> f.getName()).doesNotHaveDuplicates();
    }

    @Test
    void pilotSourcesKeepValueConstraintsRequirednessAndOwnedIdentitySeparate() {
        var resolver = new MetadataResolver();
        var name = resolver.resolve(Nomenclature.class).getAllAnnotatedFields().stream()
            .filter(f -> f.getName().equals("name")).findFirst().orElseThrow();
        assertThat(name.getField().getAnnotation(Size.class).max()).isEqualTo(200);
        var journal = resolver.resolve(ReceivingDocument.class).getAllAnnotatedFields().stream()
            .filter(f -> f.getName().equals("journal")).findFirst().orElseThrow();
        assertThat(journal.isRequired()).isTrue();
        assertThat(journal.getRequiredOrigin()).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(journal.isServerRequired()).isFalse();
        assertThat(resolver.resolveTableSections(Nomenclature.class)).singleElement().satisfies(section -> {
            assertThat(section.getParentEntityClass()).isEqualTo(Nomenclature.class);
            assertThat(section.getParentFieldName()).isEqualTo("nomenclature");
            assertThat(section.getRowClass()).isEqualTo(NomAttributeValue.class);
        });
        assertThat(resolver.resolveTableSections(ReceivingDocument.class)).singleElement().satisfies(section -> {
            assertThat(section.getParentFieldName()).isEqualTo("document");
            assertThat(section.getRowClass()).isEqualTo(ReceivingDocumentItem.class);
        });
    }
}

package org.ip.metadata;

import jakarta.persistence.EntityManagerFactory;
import org.ip.config.DataInitializer;
import org.ip.model.AttributeValue;
import org.ip.model.SklNomOpa;
import org.ip.model.NomenclaturePrice;
import org.ip.model.NomenclatureAccountingObject;
import org.ip.model.ReceivingDocumentItem;
import org.ip.model.ReceivingDocument;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RuntimeDesignSnapshot;
import org.ipro.design.runtime.EntityDesignRuntimeProjection;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;

/** Actual PU/final-classification projection, independent of Explorer, Vaadin sessions and business reads. */
@SpringBootTest(classes=org.ip.Application.class)
class EntityDesignRuntimeProjectionIT {
    @MockitoBean DataInitializer dataInitializer;
    @Autowired EntityDesignRuntimeProjection projection;
    @Autowired EntityDescriptorCatalog catalog;
    @Autowired MetadataConsistencyStartupCheck startup;
    @Autowired EntityManagerFactory emf;
    @Autowired NumberingMetadataRegistry numbering;

    @Test void snapshotComesFromABackendArtifactAndReadsNoBusinessRows() throws Exception {
        assertThat(EntityDesignRuntimeProjection.class.getProtectionDomain().getCodeSource().getLocation().toString()).endsWith(".jar");
        var statistics=emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        long before=statistics.getPrepareStatementCount();
        var first=projection.snapshot(); var second=projection.snapshot();
        assertThat(first).isEqualTo(second); assertThat(first.complete()).isTrue();
        assertThat(first.types().keySet()).containsExactlyInAnyOrderElementsOf(catalog.managedTypes().stream().map(Class::getName).toList());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(before);
        assertThat(first.diagnostics()).hasSize(startup.diagnostics().size());
        assertThat(first.diagnostics()).noneMatch(d -> d.severity().equals("ERROR"));
        assertThat(RecipeCatalog.readiness(RecipeCatalog.STANDARD_CATALOG_V2,first).runtimeReady()).isTrue();
        assertThat(RecipeCatalog.readiness(RecipeCatalog.STANDARD_CATALOG_V2,first).generationEnabled()).isFalse();
        String output=System.getProperty("entity.design.snapshot.output");
        if (output!=null) new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new java.io.File(output),first);
    }
    @Test void identityKindCapabilitiesAndOwnershipArePreservedRatherThanRecomputedInUi() {
        var facts=projection.snapshot();
        var value=facts.types().get(AttributeValue.class.getName());
        assertThat(value.kind()).isEqualTo("CATALOG"); assertThat(value.interned()).isTrue();
        assertThat(value.capabilitiesOrigin()).isEqualTo("DERIVED"); assertThat(value.capabilitiesSource()).contains("InternedContract");
        assertThat(value.identityOrigin()).isEqualTo("DERIVED");
        assertThat(value.writes()).containsExactly("CREATE"); assertThat(value.naturalKeys()).hasSize(2);
        assertThat(value.naturalKeys()).anySatisfy(k -> {
            assertThat(k.properties()).containsExactly("attrType","refId"); assertThat(k.nullProperties()).containsExactly("codeUp");
        });
        assertThat(value.specialOperations()).anyMatch(operation -> operation.contains("RENAME"));
        var set=facts.types().get(SklNomOpa.class.getName());
        assertThat(set.kind()).isEqualTo("PLAIN"); assertThat(set.writes()).isEmpty(); assertThat(set.naturalKeys()).hasSize(1);
        var independent=facts.types().get(NomenclaturePrice.class.getName());
        var recorded=facts.types().get(NomenclatureAccountingObject.class.getName());
        assertThat(independent.kind()).isEqualTo("INFORMATION_REGISTER"); assertThat(recorded.kind()).isEqualTo(independent.kind());
        assertThat(independent.recorderOwned()).isFalse(); assertThat(recorded.recorderOwned()).isTrue();
        // Runtime capabilities remain factual even though a future register recipe is still blocked.
        assertThat(recorded.writes()).containsExactlyInAnyOrder("CREATE","UPDATE","DELETE");
        assertThat(facts.prerequisite("REGISTER_ENGINE_OPERATIONS").status()).isEqualTo(RuntimeDesignSnapshot.Status.UNAVAILABLE);
        assertThat(facts.types().values()).flatExtracting(RuntimeDesignSnapshot.TypeFacts::sections).isNotEmpty();
        var row=facts.types().get(ReceivingDocumentItem.class.getName());
        assertThat(row.sectionOwnerTypeId()).isEqualTo(ReceivingDocument.class.getName());
        assertThat(row.attributes()).anyMatch(a -> a.name().equals("nomenclature") && a.form() && a.table() && !a.list() && !a.selection());
        assertThat(value.attributes()).allSatisfy(a -> assertThat(a.propertyId()).isEqualTo(value.typeId()+"#"+a.name()));
        assertThat(value.attributes()).anyMatch(a -> a.name().equals("codeUp") && !a.form());
        assertThat(numbering.all()).isNotEmpty();
        for (var declared : numbering.all()) {
            var attribute=facts.types().get(declared.entityClass().getName()).attributes().stream()
                .filter(a -> a.name().equals(declared.fieldName())).findFirst().orElseThrow();
            assertThat(attribute.numbering().role()).isEqualTo(declared.definition().role().name());
            assertThat(attribute.numbering().scope()).isEqualTo(declared.definition().scope());
        }
    }
}

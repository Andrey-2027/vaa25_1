package org.ip.rest;

import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the application-owned declarations against the application's real JPA metamodel. */
@SpringBootTest(classes = Application.class)
class RestResourceCatalogPilotIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private RestResourceCatalog catalog;

    @Test
    void specificationsDeclarationResolvesAgainstTheApplicationPersistenceUnit() {
        var resource = catalog.find("specifications", 1).orElseThrow();

        assertThat(resource.definition().resourceType()).isEqualTo(PrdSpec.class);
        assertThat(resource.fields()).containsKeys(
            "id", "codeSpec", "draft", "comment", "nomenclatureCode", "journalId");
        assertThat(resource.filters()).containsOnlyKeys("journalId");
        assertThat(resource.filters().get("journalId").source()).isEqualTo("journal.id");
        assertThat(resource.fetchRequirements(RestReadOperation.LIST))
            .containsExactly("nomenclature");
        assertThat(resource.fetchRequirements(RestReadOperation.DETAIL))
            .containsExactly("journal", "nomenclature");
    }

    @Test
    void nomenclatureDeclarationResolvesAsAnIndependentApplicationResource() {
        var resource = catalog.find("nomenclature", 1).orElseThrow();

        assertThat(resource.definition().resourceType()).isEqualTo(Nomenclature.class);
        assertThat(resource.fields()).containsKeys("id", "code", "name", "typeNom");
        assertThat(resource.filters()).containsOnlyKeys("typeNom");
        assertThat(resource.fetchRequirements(RestReadOperation.LIST)).isEmpty();
        assertThat(catalog.resources()).hasSize(2);
    }
}

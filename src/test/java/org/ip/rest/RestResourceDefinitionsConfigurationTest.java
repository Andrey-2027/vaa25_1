package org.ip.rest;

import org.ip.config.rest.RestResourceDefinitionsConfiguration;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ipro.rest.api.RestResourceDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RestResourceDefinitionsConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(RestResourceDefinitionsConfiguration.class);

    @Test
    void declaresPrdSpecAndNomenclatureAsSeparateVersionedBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(RestResourceDefinition.class)).hasSize(2);

            RestResourceDefinition<?> specifications = context.getBean(
                "specificationsRestResourceDefinition", RestResourceDefinition.class);
            assertThat(specifications.resourceKey()).isEqualTo("specifications");
            assertThat(specifications.majorVersion()).isEqualTo(1);
            assertThat(specifications.resourceType()).isEqualTo(PrdSpec.class);
            assertThat(specifications.listFields())
                .containsExactly("id", "codeSpec", "draft", "nomenclatureCode");
            assertThat(specifications.filters().keySet()).containsExactly("journalId");

            RestResourceDefinition<?> nomenclature = context.getBean(
                "nomenclatureRestResourceDefinition", RestResourceDefinition.class);
            assertThat(nomenclature.resourceType()).isEqualTo(Nomenclature.class);
            assertThat(nomenclature.fields().keySet())
                .containsExactly("id", "code", "name", "typeNom");
        });
    }
}

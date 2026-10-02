package org.ipro.rest.api;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ipro.rest.api.RestFieldType.LONG;
import static org.ipro.rest.api.RestFieldType.STRING;
import static org.ipro.rest.api.RestNullability.NOT_NULL;
import static org.ipro.rest.api.RestNullability.NULLABLE;
import static org.ipro.rest.api.RestResources.path;
import static org.ipro.rest.api.RestResources.publish;
import static org.ipro.rest.api.RestSortDirection.ASC;

class RestResourceRegistrationTest {

    @Test
    void resourceDefinitionsCanBeRegisteredAsBeansWithoutWebInfrastructure() {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(ResourceDefinitions.class)) {
            assertThat(context.getBeansOfType(RestResourceDefinition.class)).hasSize(2);

            RestResourceDefinition<?> specifications = context.getBean(
                "specifications", RestResourceDefinition.class);
            assertThat(specifications.resourceKey()).isEqualTo("specifications");
            assertThat(specifications.fields()).containsKeys("nomenclatureCode", "journalId");
            assertThat(specifications.filters().keySet()).containsExactly("journalId");

            RestResourceDefinition<?> nomenclature = context.getBean(
                "nomenclature", RestResourceDefinition.class);
            assertThat(nomenclature.resourceKey()).isEqualTo("nomenclature");
            assertThat(nomenclature.fields().keySet())
                .containsExactly("id", "code", "name", "typeNom");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ResourceDefinitions {

        @Bean
        RestResourceDefinition<PrdSpecFixture> specifications() {
            return publish("specifications", 1, PrdSpecFixture.class)
                .field("id", LONG, path("id"), NOT_NULL)
                .field("codeSpec", STRING, path("codeSpec"), NOT_NULL)
                .field("draft", STRING, path("draft"), NULLABLE)
                .field("comment", STRING, path("comment"), NULLABLE)
                .field("nomenclatureCode", STRING, path("nomenclature.code"), NOT_NULL)
                .field("journalId", LONG, path("journal.id"), NOT_NULL)
                .listFields("id", "codeSpec", "draft", "nomenclatureCode")
                .listDefaultFields("id", "codeSpec", "draft")
                .detailFields("id", "codeSpec", "draft", "comment", "journalId", "nomenclatureCode")
                .detailDefaultFields("id", "codeSpec", "draft", "comment")
                .filter("journalId", RestFilterOperator.EQUALS, LONG, path("journal.id"))
                .sortBy("id", "codeSpec")
                .defaultSort("id", ASC)
                .build();
        }

        @Bean
        RestResourceDefinition<NomenclatureFixture> nomenclature() {
            return publish("nomenclature", 1, NomenclatureFixture.class)
                .field("id", LONG, path("id"), NOT_NULL)
                .field("code", STRING, path("code"), NOT_NULL)
                .field("name", STRING, path("name"), NOT_NULL)
                .field("typeNom", STRING, path("typeNom"), NULLABLE)
                .listFields("id", "code", "name", "typeNom")
                .listDefaultFields("id", "code", "name")
                .detailFields("id", "code", "name", "typeNom")
                .detailDefaultFields("id", "code", "name")
                .build();
        }
    }

    private static final class PrdSpecFixture {
    }

    private static final class NomenclatureFixture {
    }
}

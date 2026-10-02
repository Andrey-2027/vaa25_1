package org.ip.config.rest;

import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ipro.rest.api.RestResourceDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.ipro.rest.api.RestFieldType.LONG;
import static org.ipro.rest.api.RestFieldType.STRING;
import static org.ipro.rest.api.RestFilterOperator.EQUALS;
import static org.ipro.rest.api.RestNullability.NOT_NULL;
import static org.ipro.rest.api.RestNullability.NULLABLE;
import static org.ipro.rest.api.RestResources.path;
import static org.ipro.rest.api.RestResources.publish;
import static org.ipro.rest.api.RestSortDirection.ASC;

/** Application-owned REST resource declarations; no HTTP routes are registered in this slice. */
@Configuration(proxyBeanMethods = false)
public class RestResourceDefinitionsConfiguration {

    @Bean
    public RestResourceDefinition<PrdSpec> specificationsRestResourceDefinition() {
        return publish("specifications", 1, PrdSpec.class)
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
            .filter("journalId", EQUALS, LONG, path("journal.id"))
            .sortBy("id", "codeSpec")
            .defaultSort("id", ASC)
            .pageSize(50, 200)
            .build();
    }

    @Bean
    public RestResourceDefinition<Nomenclature> nomenclatureRestResourceDefinition() {
        return publish("nomenclature", 1, Nomenclature.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("code", STRING, path("code"), NOT_NULL)
            .field("name", STRING, path("name"), NOT_NULL)
            .field("typeNom", STRING, path("typeNom"), NULLABLE)
            .listFields("id", "code", "name", "typeNom")
            .listDefaultFields("id", "code", "name")
            .detailFields("id", "code", "name", "typeNom")
            .detailDefaultFields("id", "code", "name")
            .filter("typeNom", EQUALS, STRING, path("typeNom"))
            .sortBy("id", "code")
            .defaultSort("code", ASC)
            .pageSize(50, 200)
            .build();
    }
}

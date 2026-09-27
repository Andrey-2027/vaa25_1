package org.ipro.jr;

import jakarta.persistence.EntityManager;
import org.ipro.jr.config.JrPersistenceAutoConfiguration;
import org.ipro.jr.dom.JrxmlTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JR persistence belongs to the application, so its focused persistence slice lives here too.
 * The test deliberately imports the app-owned registration instead of depending on a platform
 * module to discover report-specific entity/repository packages.
 */
@DataJpaTest(properties = "spring.sql.init.mode=never")
@ImportAutoConfiguration(JrPersistenceAutoConfiguration.class)
@ContextConfiguration(classes = JrPersistenceRegistrationTest.TestApplication.class)
class JrPersistenceRegistrationTest {

    @Autowired
    private JrxmlTemplateRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void applicationRegistrationCreatesTheRepository() {
        assertThat(repository).isNotNull();
        assertThat(repository.count()).isZero();
    }

    @Test
    void applicationRegistrationPutsItsEntityIntoThePersistenceUnit() {
        assertThat(entityManager.getMetamodel().managedType(JrxmlTemplate.class).getJavaType())
            .as("app-owned @EntityScan обязан покрывать JR entity")
            .isEqualTo(JrxmlTemplate.class);
    }

    @Test
    void entityIsWritableThroughTheApplicationOwnedRepository() {
        JrxmlTemplate template = new JrxmlTemplate();
        template.setName("slice-template");
        template.setFileName("slice-template.jrxml");

        repository.saveAndFlush(template);

        assertThat(repository.findByName("slice-template")).isPresent();
        assertThat(repository.existsByFileName("slice-template.jrxml")).isTrue();
    }

    /** Minimal slice configuration: the production application and broad scans are absent. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}

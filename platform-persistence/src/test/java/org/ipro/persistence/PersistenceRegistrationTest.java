package org.ipro.persistence;

import jakarta.persistence.EntityManager;
import org.ipro.jr.JrxmlTemplateRepository;
import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.persistence.config.PersistenceAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 (persistence slice): модуль доказывает свою регистрацию сам, без приложения.
 *
 * <p>Проверяется ровно тот риск, который карта D1 (§3.5) назвала самым неприятным: entity и
 * репозиторий вынесенного артефакта могут остаться без регистрации, и в полном приложении это
 * заметит только оно само. Здесь нет ни приложения, ни его конфигурации — только срез
 * {@code @DataJpaTest}, который подключает <b>собственную</b> автоконфигурацию модуля. Если
 * модуль перестанет себя регистрировать, этот тест упадёт вместе с сборкой модуля, а не
 * где-то в потребителе.</p>
 *
 * <p>Композиция и объявления сканирования проверяются статически в
 * {@link PersistenceModuleCompositionTest}; здесь — последствия: репозиторий существует как бин,
 * а entity входит в persistence unit.</p>
 */
@DataJpaTest
@ImportAutoConfiguration(PersistenceAutoConfiguration.class)
@ContextConfiguration(classes = PersistenceRegistrationTest.TestApplication.class)
class PersistenceRegistrationTest {

    @Autowired
    private JrxmlTemplateRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void moduleRegistrationCreatesItsRepository() {
        assertThat(repository).isNotNull();
        assertThat(repository.count()).isZero();
    }

    @Test
    void moduleRegistrationPutsItsEntityIntoThePersistenceUnit() {
        assertThat(entityManager.getMetamodel().managedType(JrxmlTemplate.class).getJavaType())
            .as("@EntityScan модуля обязан покрывать его собственную entity")
            .isEqualTo(JrxmlTemplate.class);
    }

    @Test
    void entityIsWritableThroughTheModuleOwnedRepository() {
        JrxmlTemplate template = new JrxmlTemplate();
        template.setName("slice-template");
        template.setFileName("slice-template.jrxml");

        repository.saveAndFlush(template);

        assertThat(repository.findByName("slice-template")).isPresent();
        assertThat(repository.existsByFileName("slice-template.jrxml")).isTrue();
    }

    /** Минимальная конфигурация среза: приложения (org.ip) в контексте нет. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}

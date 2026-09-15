package org.ip;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.persistence.config.PersistenceAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 (persistence slice): проверка того, что ответ на риск §3.5 карты D1 — верный.
 *
 * <p>Риск был сформулирован так: entity и репозитории регистрируются централизованно,
 * поэтому вынесенный артефакт может потерять свои persistence-типы — и неизвестно, упадёт
 * ли это громко. Срез отвечает кодом (модуль объявляет свои {@code @EntityScan} и
 * {@code @EnableJpaRepositories}) и этой проверкой в реальном контексте:</p>
 * <ol>
 * <li>репозиторий из пакета модуля существует как бин, хотя приложение его пакет больше не
 *     перечисляет;</li>
 * <li>entity из пакета модуля попал в persistence unit (иначе запрос упал бы в рантайме);</li>
 * <li><b>ни один чужой пакет не потерялся</b>: у каждой декларации {@code @EntityScan} и
 *     {@code @EnableJpaRepositories} в проекте — своя точка проверки. Это главное: если бы
 *     вторая декларация перекрывала первую, исчезли бы репозитории платформенного хаба, и
 *     приложение просто не поднялось бы — но проверить это до старта нельзя, только
 *     контекстом.</li>
 * </ol>
 */
@SpringBootTest
class PersistenceRegistrationIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** По одному репозиторию на каждую декларацию @EnableJpaRepositories в проекте. */
    private static final Map<String, Class<?>> REPOSITORIES_BY_DECLARATION = repositoriesByDeclaration();

    /** По одной entity на каждый объявленный @EntityScan-пакет (там, где entities есть). */
    private static final Map<String, String> ENTITIES_BY_PACKAGE = Map.of(
        "org.ip.model", "org.ip.model.Branch",
        "org.ipro.jr.dom", "org.ipro.jr.dom.JrxmlTemplate",
        "org.ipro.settings", "org.ipro.settings.SettingValue");

    @Test
    void everyDeclaredRepositoryPackageContributesItsRepository() {
        Map<String, String> missing = new LinkedHashMap<>();
        REPOSITORIES_BY_DECLARATION.forEach((declarationPackage, repositoryType) -> {
            Object repository = context.getBeanProvider(repositoryType).getIfAvailable();
            if (repository == null) {
                missing.put(declarationPackage, repositoryType.getName());
            }
        });

        assertThat(missing)
            .as("репозитории из каждого объявленного пакета должны существовать. Пропажа"
                + " означает, что одна из деклараций @EnableJpaRepositories перекрыла другую"
                + " (или пакет выпал из своей декларации) — то есть именно тихую потерю"
                + " persistence при выносе артефакта")
            .isEmpty();
    }

    @Test
    void entitiesFromEveryDeclaredScanPackageAreInThePersistenceUnit() {
        var entityNames = entityManagerFactory.getMetamodel().getEntities().stream()
            .map(type -> type.getJavaType().getName())
            .toList();

        assertThat(entityNames).containsAll(ENTITIES_BY_PACKAGE.values());
    }

    @Test
    void persistenceAutoConfigurationComesFromTheArtifact() {
        assertThat(PersistenceAutoConfiguration.class.getProtectionDomain()
            .getCodeSource().getLocation().toString())
            .as("регистрация persistence-пакетов должна приходить из артефакта"
                + " platform-persistence, а не из дерева приложения")
            .contains("platform-persistence");
    }

    private static Map<String, Class<?>> repositoriesByDeclaration() {
        Map<String, Class<?>> repositories = new LinkedHashMap<>();
        // приложение объявляет свои репозитории само
        repositories.put("org.ip", org.ip.repository.BranchRepository.class);
        // платформенный хаб RlsAutoConfiguration
        repositories.put("org.ipro.rls", org.ipro.rls.AccessGrantRepository.class);
        repositories.put("org.ipro.reportstudio", org.ipro.reportstudio.ReportTemplateRepository.class);
        repositories.put("org.ipro.numbering", org.ipro.numbering.NumberingRuleRepository.class);
        repositories.put("org.ipro.settings", org.ipro.settings.SettingValueRepository.class);
        repositories.put("org.ipro.ureport", org.ipro.ureport.UreportTemplateRepository.class);
        repositories.put("org.ipro.telemetry.repository", org.ipro.telemetry.repository.OperationLogRepository.class);
        // вынесенный модуль объявляет свои пакеты сам
        repositories.put("org.ipro.jr", org.ipro.jr.JrxmlTemplateRepository.class);
        return repositories;
    }
}

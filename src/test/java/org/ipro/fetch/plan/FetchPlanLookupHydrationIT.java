package org.ipro.fetch.plan;

import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ipro.crud.LookupService;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3.4 / ADX-07: lookup возвращает представление, пригодное для объявленного сценария.
 *
 * <p>Зависимости выбора (единица измерения номенклатуры, номенклатура спецификации)
 * объявлены в форме поля и расширяют её запрос {@code LOOKUP}.
 * Поиск применяет этот план как fetch-граф, поэтому выбранное значение приходит уже
 * готовым к чтению — форме больше не нужно перечитывать сущность по ID с EntityGraph и
 * константой глубины.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
@Transactional
class FetchPlanLookupHydrationIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private FetchPlanRegistry fetchPlanRegistry;

    @Autowired
    private LookupService lookupService;

    @Autowired
    private org.ipro.form.registry.FormResolver formResolver;

    private List<String> fieldPaths(String variant, String field) {
        return formResolver.resolveItemForm(org.ip.model.PrdSpecMtr.class, variant, null, null)
            .entityField(field).getLookupPaths();
    }

    @Autowired
    private UnitOfMeasurementRepository unitOfMeasurementRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAsSuperuser() {
        String username = "plan-test-" + UUID.randomUUID();
        org.ipro.rls.RlsTestFixture.grantC5EntityRead(accessGrantRepository, username,
            Nomenclature.class, PrdSpec.class);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));

        AccessGrant wildcard = new AccessGrant();
        wildcard.setSubjectType(AccessGrant.SubjectType.USER);
        wildcard.setSubjectKey(username);
        wildcard.setDimension("*");
        wildcard.setCanRead(true);
        wildcard.setCanUpdate(true);
        wildcard.setCanDelete(true);
        accessGrantRepository.saveAndFlush(wildcard);
    }

    @Test
    void declaredLookupDependenciesArePartOfThePilotLookupPlan() {
        assertThat(fetchPlanRegistry.pathsWith(Nomenclature.class, FetchScenario.LOOKUP,
                fieldPaths("material", "nomenclature")))
            .contains("unitOfMeasurement");
        assertThat(fetchPlanRegistry.pathsWith(PrdSpec.class, FetchScenario.LOOKUP,
                fieldPaths("product", "prdSpecMtr")))
            .contains("nomenclature", "nomenclature.unitOfMeasurement");
        assertThat(fetchPlanRegistry.paths(Nomenclature.class, FetchScenario.LOOKUP))
            .doesNotContain("unitOfMeasurement");
    }

    @Test
    void lookupSearchHydratesDeclaredDependenciesWithoutManualReload() {
        loginAsSuperuser();

        String suffix = UUID.randomUUID().toString().substring(0, 4);
        UnitOfMeasurement unit = unitOfMeasurementRepository.save(
            new UnitOfMeasurement("KG" + suffix, "Килограмм", "PKG" + suffix));
        Nomenclature nomenclature = new Nomenclature("PN" + suffix, "Деталь план", unit);
        entityManager.persist(nomenclature);
        entityManager.flush();
        entityManager.clear();

        List<Nomenclature> found = lookupService.search(Nomenclature.class,
            new String[]{"code"}, nomenclature.getCode(), 20, fieldPaths("material", "nomenclature"));
        entityManager.clear();

        assertThat(found).hasSize(1);
        assertThat(Hibernate.isInitialized(found.get(0).getUnitOfMeasurement()))
            .as("объявленная зависимость выбора должна приходить загруженной")
            .isTrue();
        assertThat(found.get(0).getUnitOfMeasurement().getShortCode()).isEqualTo("KG" + suffix);
    }

    @Test
    void findAllDoesNotInheritAnotherFormsDependencies() {
        loginAsSuperuser();

        String suffix = UUID.randomUUID().toString().substring(0, 4);
        UnitOfMeasurement unit = unitOfMeasurementRepository.save(
            new UnitOfMeasurement("KG" + suffix, "Килограмм", "PKG" + suffix));
        Nomenclature nomenclature = new Nomenclature("PN" + suffix, "Деталь план", unit);
        entityManager.persist(nomenclature);
        entityManager.flush();
        entityManager.clear();

        Nomenclature found = lookupService.findAll(Nomenclature.class).stream()
            .filter(item -> nomenclature.getCode().equals(item.getCode()))
            .findFirst().orElseThrow();

        assertThat(Hibernate.isInitialized(found.getUnitOfMeasurement()))
            .as("потребность формы строки не становится глобальным правилом чтения")
            .isFalse();
    }
}

package org.ipro.data;

import jakarta.persistence.EntityManager;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ipro.crud.LookupService;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Б1: отбор выбора («путь → значение») действует в чтении поля выбора — автокомплит не
 * предлагает то, чего диалог выбора не покажет.
 *
 * <p>Диалог и автокомплит строят отбор одним и тем же кодом ({@code SelectionFilters});
 * совпадение правил проверяет {@code EntityFieldSelectionFilterTest} в platform-vaadin.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
@Transactional
class LookupSelectionFilterIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private LookupService lookupService;

    @Autowired
    private UnitOfMeasurementRepository unitOfMeasurementRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private EntityManager entityManager;

    private String suffix;

    @BeforeEach
    void setUp() {
        String username = "b1-test-" + UUID.randomUUID();
        org.ipro.rls.RlsTestFixture.grantC5EntityRead(accessGrantRepository, username,
            Nomenclature.class);
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

        suffix = UUID.randomUUID().toString().substring(0, 4);
        UnitOfMeasurement unit = unitOfMeasurementRepository.save(
            new UnitOfMeasurement("KG" + suffix, "Килограмм", "PKG" + suffix));
        persist("MAT", "Материал", unit);
        persist("NODE", "Узел", unit);
        persist("ASM", "Сборка", unit);
        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void persist(String kind, String type, UnitOfMeasurement unit) {
        Nomenclature nomenclature = new Nomenclature("B1" + kind + suffix, "Позиция " + kind, unit);
        nomenclature.setTypeNom(type);
        entityManager.persist(nomenclature);
    }

    private List<String> codes(Map<String, Object> filters, String term) {
        return lookupService.search(Nomenclature.class, new String[]{"code"}, term, 20, null, filters)
            .stream().map(Nomenclature::getCode).sorted().toList();
    }

    @Test
    void scalarFilterKeepsOnlyMatchingRecords() {
        assertThat(codes(Map.of("typeNom", "Материал"), "B1"))
            .containsExactly("B1MAT" + suffix);
    }

    @Test
    void collectionFilterWorksAsIn() {
        assertThat(codes(Map.of("typeNom", List.of("Узел", "Сборка")), "B1"))
            .containsExactly("B1ASM" + suffix, "B1NODE" + suffix);
    }

    @Test
    void filterAppliesWithBlankTermToo() {
        List<Nomenclature> found = lookupService.search(Nomenclature.class,
            new String[]{"code"}, "", 200, null, Map.of("typeNom", "Материал"));
        assertThat(found).isNotEmpty().allMatch(n -> "Материал".equals(n.getTypeNom()));
    }

    @Test
    void filterThatMatchesNothingGivesEmptyResult() {
        assertThat(codes(Map.of("typeNom", "Услуга"), "B1")).isEmpty();
    }

    @Test
    void searchWithoutFilterIsUnchanged() {
        List<String> expected = List.of("B1ASM" + suffix, "B1MAT" + suffix, "B1NODE" + suffix);
        assertThat(codes(Map.of(), "B1")).containsExactlyElementsOf(expected);
        assertThat(codes(null, "B1")).containsExactlyElementsOf(expected);
        assertThat(lookupService.search(Nomenclature.class, new String[]{"code"}, "B1", 20)
            .stream().map(Nomenclature::getCode).sorted().toList())
            .containsExactlyElementsOf(expected);
    }

    @Test
    void emptyKeysAndNullValuesAreIgnoredLikeInTheDialog() {
        Map<String, Object> noise = new LinkedHashMap<>();
        noise.put("", "x");
        noise.put("typeNom", null);
        assertThat(codes(noise, "B1")).hasSize(3);
    }
}

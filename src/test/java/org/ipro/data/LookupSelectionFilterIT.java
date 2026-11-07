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
    private String searchTerm;

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

        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // Каждый тест ищет только свой набор: короткий "B1" встречается в кодах других fixtures.
        searchTerm = "B1" + suffix;
        String unitSuffix = suffix.substring(0, 7); // коды единицы ограничены 10 символами
        UnitOfMeasurement unit = unitOfMeasurementRepository.save(
            new UnitOfMeasurement("KG" + unitSuffix, "Килограмм", "PKG" + unitSuffix));
        persist("MAT", "Материал", unit);
        persist("NODE", "Узел", unit);
        persist("ASM", "Сборка", unit);
        // Воспроизводим соседнюю запись с "B1": она не должна попасть в наш поисковый набор.
        Nomenclature unrelated = new Nomenclature("OTHER" + suffix + "B1", "Другая позиция", unit);
        unrelated.setTypeNom("Материал");
        entityManager.persist(unrelated);
        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void persist(String kind, String type, UnitOfMeasurement unit) {
        Nomenclature nomenclature = new Nomenclature(searchTerm + kind, "Позиция " + kind, unit);
        nomenclature.setTypeNom(type);
        entityManager.persist(nomenclature);
    }

    private List<String> codes(Map<String, Object> filters, String term) {
        return lookupService.search(Nomenclature.class, new String[]{"code"}, term, 20, null, filters)
            .stream().map(Nomenclature::getCode).sorted().toList();
    }

    @Test
    void scalarFilterKeepsOnlyMatchingRecords() {
        assertThat(codes(Map.of("typeNom", "Материал"), searchTerm))
            .containsExactly(searchTerm + "MAT");
    }

    @Test
    void collectionFilterWorksAsIn() {
        assertThat(codes(Map.of("typeNom", List.of("Узел", "Сборка")), searchTerm))
            .containsExactly(searchTerm + "ASM", searchTerm + "NODE");
    }

    @Test
    void filterAppliesWithBlankTermToo() {
        List<Nomenclature> found = lookupService.search(Nomenclature.class,
            new String[]{"code"}, "", 200, null, Map.of("typeNom", "Материал"));
        assertThat(found).isNotEmpty().allMatch(n -> "Материал".equals(n.getTypeNom()));
    }

    @Test
    void filterThatMatchesNothingGivesEmptyResult() {
        assertThat(codes(Map.of("typeNom", "Услуга"), searchTerm)).isEmpty();
    }

    @Test
    void searchWithoutFilterIsUnchanged() {
        List<String> expected = List.of(searchTerm + "ASM", searchTerm + "MAT", searchTerm + "NODE");
        assertThat(codes(Map.of(), searchTerm)).containsExactlyElementsOf(expected);
        assertThat(codes(null, searchTerm)).containsExactlyElementsOf(expected);
        assertThat(lookupService.search(Nomenclature.class, new String[]{"code"}, searchTerm, 20)
            .stream().map(Nomenclature::getCode).sorted().toList())
            .containsExactlyElementsOf(expected);
    }

    @Test
    void emptyKeysAndNullValuesAreIgnoredLikeInTheDialog() {
        Map<String, Object> noise = new LinkedHashMap<>();
        noise.put("", "x");
        noise.put("typeNom", null);
        assertThat(codes(noise, searchTerm))
            .containsExactly(searchTerm + "ASM", searchTerm + "MAT", searchTerm + "NODE");
    }
}

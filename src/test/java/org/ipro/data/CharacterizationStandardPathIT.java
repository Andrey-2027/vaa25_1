package org.ipro.data;

import org.ip.config.DataInitializer;
import org.ip.model.Branch;
import org.ipro.data.CanonicalEntityService;
import org.ipro.crud.ServiceLocator;
import org.ipro.crud.LookupService;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C4.0 characterization: фиксирует текущее поведение стандартного read/search-пути
 * <b>до</b> появления canonical executor'а. Тесты описывают то, что есть, а не то, что
 * должно быть: они намеренно падают, когда C4.1–C4.4 меняют семантику, и тогда изменение
 * принимается осознанно (per-entity compatibility matrix, ADR-0007 §7).
 *
 * <p>Проверяются именно расхождения, из-за которых C4 планирует единый search contract:</p>
 * <ul>
 *   <li>{@code LookupService.search} трактует пользовательские {@code %} и {@code _} как SQL
 *       wildcard — <b>изменено в C4.4</b>: теперь literal escaping;</li>
 *   <li>blank term у lookup ограничен только {@code limit} (сохранено);</li>
 *   <li>неизвестное search field у lookup молча пропускается, а канонический
 *       search такой вызов отклоняет;</li>
 *   <li>{@code search(String, Pageable)} сервисной базы — production-time trap —
 *       <b>изменено в C4.4</b>: метод делегирует canonical engine;</li>
 *   <li>blank legacy-search уходит в неограниченный {@code findAll()} — <b>изменено
 *       в C4.4</b>: blank term даёт bounded выдачу с детерминированным id-порядком.</li>
 * </ul>
 *
 * <p>Пилот — {@code Branch}: явный {@code serviceClass}, без предметных методов
 * (см. {@code c4-inventory.md} §9). После C4.4 {@code Branch} использует default engine,
 * поэтому тесты ниже фиксируют уже принятую семантику.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
class CharacterizationStandardPathIT {

    private static final int LIMIT = 5;

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private ServiceLocator serviceLocator;

    @Autowired
    private LookupService lookupService;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    /**
     * Волна A: {@code Branch} больше не имеет application service, но characterization
     * проверяется через тот же контракт {@code BaseService} — теперь его даёт canonical
     * handle, поэтому принятая семантика C4.4 подтверждается на целевом пути, а не на
     * удаляемом compatibility-классе.
     */
    private CanonicalEntityService<Branch> branches() {
        return (CanonicalEntityService<Branch>) serviceLocator.<Branch, Long>findService(Branch.class);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void lookupBlankTermIsBoundedOnlyByLimit() {
        withSuperuser(() -> {
            List<Branch> seeded = seed("CHR-BLANK-", LIMIT + 3);
            try {
                List<Branch> found = lookupService.search(
                    Branch.class, new String[]{"code"}, "   ", LIMIT);

                assertThat(found).hasSize(LIMIT);
            } finally {
                delete(seeded);
            }
        });
    }

    @Test
    void lookupTreatsUserPercentLiterallyAfterC44() {
        withSuperuser(() -> {
            List<Branch> seeded = seed("CHR-WILD-", 2);
            try {
                // C4.4: '%' — литеральный символ, а не wildcard. Ни один код не содержит
                // литерального '%', поэтому совпадений быть не должно.
                List<Branch> found = lookupService.search(
                    Branch.class, new String[]{"code"}, "CHR-WILD-%", LIMIT);

                assertThat(found).isEmpty();
            } finally {
                delete(seeded);
            }
        });
    }

    @Test
    void lookupTreatsUserUnderscoreLiterallyAfterC44() {
        withSuperuser(() -> {
            List<Branch> seeded = seed("CHR-UND-", 2);
            try {
                // C4.4: '_' — литеральный символ; в сидированных кодах подчёркиваний нет.
                List<Branch> found = lookupService.search(
                    Branch.class, new String[]{"code"}, "CHR-UND-_", LIMIT);

                assertThat(found).isEmpty();
            } finally {
                delete(seeded);
            }
        });
    }

    @Test
    void unknownSearchFieldIsSilentlySkipped() {
        withSuperuser(() -> {
            List<Branch> seeded = seed("CHR-UNK-", 1);
            String code = seeded.get(0).getCode();
            try {
                assertThat(lookupService.search(
                    Branch.class, new String[]{"noSuchField"}, code, LIMIT))
                    .isEmpty();

                // Смешанный список: невалидное поле не мешает валидному.
                assertThat(lookupService.search(
                    Branch.class, new String[]{"noSuchField", "code"}, code, LIMIT))
                    .extracting(Branch::getCode)
                    .containsExactly(code);
            } finally {
                delete(seeded);
            }
        });
    }

    @Test
    void pagedBranchSearchUsesTheCanonicalEngine() {
        withSuperuser(() -> {
            // C4.4: production-time trap закрыт — standard search делегирует canonical engine.
            Page<Branch> page = branches().search("x", PageRequest.of(0, 10));

            assertThat(page).isNotNull();
            assertThat(page.getSize()).isEqualTo(10);
        });
    }

    @Test
    void blankLegacySearchIsBoundedAfterC44() {
        withSuperuser(() -> {
            List<Branch> seeded = seed("CHR-BLANKLEG-", LIMIT + 3);
            try {
                // C4.4: blank term — не фильтр, но выдача bounded (page/limit) и
                // упорядочена по id, а не неограниченный findAll().
                List<Branch> found = branches().search("");

                assertThat(found).hasSizeLessThanOrEqualTo(100);
                assertThat(found).extracting(Branch::getCode)
                    .contains(seeded.get(0).getCode(), seeded.get(seeded.size() - 1).getCode());
            } finally {
                delete(seeded);
            }
        });
    }

    @Test
    void nonBlankLegacySearchReturnsCanonicalMatches() {
        withSuperuser(() -> {
            List<Branch> seeded = seed("CHR-HIT-", 2);
            String code = seeded.get(1).getCode();
            try {
                assertThat(branches().search(code))
                    .extracting(Branch::getCode)
                    .contains(code);
            } finally {
                delete(seeded);
            }
        });
    }

    // === Helpers ===

    private void withSuperuser(Runnable action) {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, action);
    }

    private List<Branch> seed(String prefix, int count) {
        // Уникальный суффикс, чтобы прогоны и классы не делили строки одного префикса.
        String run = UUID.randomUUID().toString().substring(0, 4);
        List<Branch> branches = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Branch branch = new Branch();
            branch.setCode(prefix + run + "-" + i);
            branch.setName("characterization " + prefix + i);
            branches.add(branches().save(branch));
        }
        return branches;
    }

    private void delete(List<Branch> branches) {
        // Через сервис, а не repository.deleteById: RLS-граница требует entity values и
        // запрещает id/bulk-mutation даже субъекту с полным грантом.
        for (Branch branch : branches) {
            if (branch.getId() != null) {
                branches().delete(branch.getId());
            }
        }
    }
}

package org.ip.application.register;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ip.model.UnitOfMeasurement;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-0013 критерий закрытия v1.0: {@code getLast} совпадает с наивным расчётом в памяти
 * («оракулом») на краевых случаях и на случайной истории цен. Каждый тест откатывается.
 */
@SpringBootTest(classes = Application.class)
@Transactional
class NomenclaturePriceRegisterOracleTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private NomenclaturePriceService service;

    private String username;

    @BeforeEach
    void setUp() {
        username = "orc" + UUID.randomUUID().toString().substring(0, 8);
        authenticate(username);
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(username);
        grant.setDimension("ENTITY:" + NomenclaturePrice.class.getName());
        grant.setCanRead(true);
        entityManager.persist(grant);
        entityManager.flush();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyBeforeTheFirstRecord() {
        Nomenclature item = nomenclature("ORC-1");
        price(item, START.plusDays(10), "100");

        assertThat(service.getLast(item, START.plusDays(9))).isEmpty();
        assertThat(service.priceOn(item, START.plusDays(9))).isEmpty();
    }

    @Test
    void recordOnTheExactDateIsIncluded() {
        Nomenclature item = nomenclature("ORC-2");
        price(item, START.plusDays(10), "100");

        assertThat(service.priceOn(item, START.plusDays(10))).hasValueSatisfying(
            value -> assertThat(value).isEqualByComparingTo("100"));
    }

    @Test
    void latestRecordNotAfterTheDateWins() {
        Nomenclature item = nomenclature("ORC-3");
        price(item, START, "100");
        price(item, START.plusDays(10), "150");
        price(item, START.plusDays(20), "90");

        assertThat(service.priceOn(item, START.plusDays(5))).hasValueSatisfying(
            value -> assertThat(value).isEqualByComparingTo("100"));
        assertThat(service.priceOn(item, START.plusDays(15))).hasValueSatisfying(
            value -> assertThat(value).isEqualByComparingTo("150"));
        assertThat(service.priceOn(item, START.plusDays(500))).hasValueSatisfying(
            value -> assertThat(value).isEqualByComparingTo("90"));
    }

    @Test
    void nomenclaturesDoNotSeeEachOthersHistory() {
        Nomenclature priced = nomenclature("ORC-4A");
        Nomenclature unpriced = nomenclature("ORC-4B");
        price(priced, START, "100");

        assertThat(service.getLast(unpriced, START.plusDays(30))).isEmpty();
        assertThat(service.getLast(priced, START.plusDays(30))).isPresent();
    }

    @Test
    void acceptsAPersistedDetachedDimension() {
        Nomenclature item = nomenclature("ORC-D");
        price(item, START, "123.4567");
        entityManager.flush();
        entityManager.clear();

        assertThat(service.priceOn(item, START)).hasValueSatisfying(
            value -> assertThat(value).isEqualByComparingTo("123.4567"));
    }

    @Test
    void deniesReadWithoutTheEntityPermission() {
        Nomenclature item = nomenclature("ORC-C5");
        price(item, START, "100");
        entityManager.flush();
        entityManager.clear();
        authenticate(username + "-denied");

        assertThatThrownBy(() -> service.getLast(item, START))
            .isInstanceOf(C5AccessDeniedException.class);
    }

    @Test
    void matchesTheInMemoryOracleOnRandomHistory() {
        Random random = new Random(20261003L);
        List<Nomenclature> items = new ArrayList<>();
        List<TreeMap<LocalDate, BigDecimal>> histories = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Nomenclature item = nomenclature("ORC-R" + i);
            TreeMap<LocalDate, BigDecimal> history = new TreeMap<>();
            int records = i == 3 ? 0 : 3 + random.nextInt(10);
            while (history.size() < records) {
                LocalDate day = START.plusDays(random.nextInt(60));
                if (history.containsKey(day)) {
                    continue;
                }
                BigDecimal value = BigDecimal.valueOf(10 + random.nextInt(990));
                history.put(day, value);
                price(item, day, value.toPlainString());
            }
            items.add(item);
            histories.add(history);
        }

        int probes = 0;
        for (int i = 0; i < items.size(); i++) {
            for (int offset = -5; offset <= 65; offset++) {
                LocalDate asOf = START.plusDays(offset);
                Optional<BigDecimal> expected = oracle(histories.get(i), asOf);
                Optional<BigDecimal> actual = service.priceOn(items.get(i), asOf);
                String where = "номенклатура " + i + ", дата " + asOf;

                assertThat(actual.isPresent()).as("наличие цены: " + where).isEqualTo(expected.isPresent());
                if (expected.isPresent()) {
                    assertThat(actual.orElseThrow()).as("цена: " + where).isEqualByComparingTo(expected.get());
                }
                probes++;
            }
        }
        assertThat(probes).isEqualTo(4 * 71);
    }

    /** Наивный расчёт: последняя запись истории с датой не позже {@code asOf}. */
    private static Optional<BigDecimal> oracle(TreeMap<LocalDate, BigDecimal> history, LocalDate asOf) {
        return Optional.ofNullable(history.floorEntry(asOf)).map(java.util.Map.Entry::getValue);
    }

    private Nomenclature nomenclature(String code) {
        UnitOfMeasurement unit = new UnitOfMeasurement("шт" + code, "Штука " + code, "ШТ" + code);
        entityManager.persist(unit);
        Nomenclature item = new Nomenclature(code, code, unit);
        entityManager.persist(item);
        return item;
    }

    private void price(Nomenclature item, LocalDate period, String value) {
        entityManager.persist(new NomenclaturePrice(item, period, new BigDecimal(value)));
    }

    private static void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }
}

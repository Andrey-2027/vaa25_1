package org.ip.application.register;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ip.model.UnitOfMeasurement;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.AccessGrant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Реальная схема H2 и canonical write: ресурс изменяем, ключ защищён lifecycle и unique. */
@SpringBootTest(classes = Application.class)
@Transactional
class NomenclaturePricePersistenceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityDataAccess access;

    private Nomenclature nomenclature;

    @BeforeEach
    void setUp() {
        String code = "rp" + UUID.randomUUID().toString().substring(0, 8);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(code, "n/a", List.of()));
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(code);
        grant.setDimension("ENTITY:" + NomenclaturePrice.class.getName());
        grant.setCanRead(true);
        entityManager.persist(grant);
        UnitOfMeasurement unit = new UnitOfMeasurement(code, "Тестовая единица", code);
        entityManager.persist(unit);
        nomenclature = new Nomenclature(code, "Тестовая номенклатура", unit);
        entityManager.persist(nomenclature);
        entityManager.flush();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void canonicalUpdatePersistsTheResourceAndKeepsTheKey() {
        NomenclaturePrice payload = createPrice();
        entityManager.clear();
        payload.setPrice(new BigDecimal("90.1234"));

        access.update(NomenclaturePrice.class, payload);
        entityManager.flush();
        entityManager.clear();

        NomenclaturePrice stored = entityManager.find(NomenclaturePrice.class, payload.getId());
        assertThat(stored.getPrice()).isEqualByComparingTo("90.1234");
        assertThat(stored.getPeriod()).isEqualTo(DAY);
        assertThat(stored.getNomenclature().getId()).isEqualTo(nomenclature.getId());
    }

    @Test
    void canonicalUpdateRejectsAChangedPeriod() {
        NomenclaturePrice payload = createPrice();
        entityManager.clear();
        payload.setPeriod(DAY.plusDays(1));

        assertThatThrownBy(() -> access.update(NomenclaturePrice.class, payload))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Период");
    }

    @Test
    void canonicalUpdateRejectsAnUnknownOriginalForAManagedEntity() {
        NomenclaturePrice managed = createPrice();
        managed.setPeriod(DAY.plusDays(1));

        assertThatThrownBy(() -> access.update(NomenclaturePrice.class, managed))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("исходные");
    }

    @Test
    void theDatabaseRejectsADuplicateBusinessKey() {
        createPrice();

        assertThatThrownBy(() -> {
            access.create(NomenclaturePrice.class,
                new NomenclaturePrice(nomenclature, DAY, new BigDecimal("80.00")));
            entityManager.flush();
        }).hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class);
    }

    @Test
    void canonicalDeleteRemovesOnlyTheSelectedVersion() {
        NomenclaturePrice first = createPrice();
        NomenclaturePrice next = access.create(NomenclaturePrice.class,
            new NomenclaturePrice(nomenclature, DAY.plusDays(1), new BigDecimal("150")));
        entityManager.flush();
        entityManager.clear();

        access.delete(NomenclaturePrice.class, first.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(access.detail(NomenclaturePrice.class, first.getId())).isEmpty();
        assertThat(access.detail(NomenclaturePrice.class, next.getId())).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nomenclature_id", "period_date", "price"})
    void physicalNotNullConstraintsRejectNativeWrites(String nullColumn) {
        // Native INSERT обходит Bean Validation: проверяется именно созданная схема H2.
        Throwable error = catchThrowable(() -> entityManager.createNativeQuery(
                "insert into nomenclature_price (nomenclature_id, period_date, price, version) "
                    + "values (:nom, :day, :price, 0)")
            .setParameter("nom", nullColumn.equals("nomenclature_id") ? null : nomenclature.getId())
            .setParameter("day", nullColumn.equals("period_date") ? null : DAY)
            .setParameter("price", nullColumn.equals("price") ? null : new BigDecimal("100"))
            .executeUpdate());

        assertThat(error).isNotNull();
        while (error.getCause() != null) {
            error = error.getCause();
        }
        assertThat(error).isInstanceOf(SQLIntegrityConstraintViolationException.class);
        assertThat(((SQLIntegrityConstraintViolationException) error).getSQLState()).isEqualTo("23502");
        assertThat(error.getMessage()).contains(nullColumn.toUpperCase(java.util.Locale.ROOT));
    }

    private NomenclaturePrice createPrice() {
        NomenclaturePrice price = access.create(NomenclaturePrice.class,
            new NomenclaturePrice(nomenclature, DAY, new BigDecimal("100.00")));
        entityManager.flush();
        return price;
    }
}

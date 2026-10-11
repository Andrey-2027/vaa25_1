package org.ip.application.register;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ip.model.UnitOfMeasurement;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
import org.ipro.register.RegisterKeyChanges;
import org.ipro.register.RegisterKeyChangePermissions;
import org.ipro.rls.c5.C5PermissionKeys;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Реальная схема H2 и canonical write: ресурс изменяем, ключ защищён lifecycle и unique. */
@SpringBootTest(classes = Application.class)
@Transactional
class NomenclaturePricePersistenceTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 1, 0, 0);

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityDataAccess access;

    @Autowired private RegisterKeyChanges keyChanges;
    @Autowired private RegisterKeyChangePermissions keyPermissions;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String username;

    private Nomenclature nomenclature;

    @BeforeEach
    void setUp() {
        String code = "rp" + UUID.randomUUID().toString().substring(0, 8);
        username = code;
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(code, "n/a", List.of()));
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(code);
        grant.setDimension("ENTITY:" + NomenclaturePrice.class.getName());
        grant.setCanRead(true);
        entityManager.persist(grant);
        // ADR-0017: операция — отдельное право; запись цены без этой строки отклоняется C5.
        AccessGrant operations = new AccessGrant();
        operations.setSubjectType(AccessGrant.SubjectType.USER);
        operations.setSubjectKey(code);
        operations.setDimension(org.ipro.rls.c5.C5PermissionKeys.entityOperations(NomenclaturePrice.class));
        operations.setCanUpdate(true);
        operations.setCanDelete(true);
        entityManager.persist(operations);
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
    void canonicalCreateNormalizesDailyPeriodBeforePersistence() {
        var saved = access.create(NomenclaturePrice.class,
            new NomenclaturePrice(nomenclature, DAY.plusHours(15).withNano(123456789), new BigDecimal("100")));
        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.find(NomenclaturePrice.class, saved.getId()).getPeriod()).isEqualTo(DAY);
    }

    @Test
    void directJpaWriteAlsoNormalizesTheDeclaredDailyPeriod() {
        var value = new NomenclaturePrice(nomenclature, DAY.plusHours(15).withNano(123456789), new BigDecimal("100"));
        entityManager.persist(value);
        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.find(NomenclaturePrice.class, value.getId()).getPeriod()).isEqualTo(DAY);
    }

    @Test
    void resourceUpdateWithAnotherTimeInTheSameDailyBucketKeepsTheKey() {
        var payload = createPrice();
        entityManager.clear();
        payload.setPeriod(DAY.plusHours(23));
        payload.setPrice(new BigDecimal("90"));
        access.update(NomenclaturePrice.class, payload);
        entityManager.flush();
        entityManager.clear();
        var stored = entityManager.find(NomenclaturePrice.class, payload.getId());
        assertThat(stored.getPeriod()).isEqualTo(DAY);
        assertThat(stored.getPrice()).isEqualByComparingTo("90");
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

    @Test void dedicatedKeyChangeKeepsIdAndResourceAndNormalizesThePeriod() {
        var row = createPrice();
        grantKeyChange();
        Nomenclature second = new Nomenclature("x" + username, "Другая", nomenclature.getUnitOfMeasurement());
        entityManager.persist(second);
        entityManager.flush();
        long version = row.getVersion();
        var changed = keyChanges.change(NomenclaturePrice.class, row.getId(), version,
            java.util.Map.of("nomenclature", second.getId()), DAY.plusDays(1).plusHours(15));
        assertThat(changed.getId()).isEqualTo(row.getId());
        assertThat(changed.getVersion()).isEqualTo(version + 1);
        entityManager.clear();
        var stored = entityManager.find(NomenclaturePrice.class, row.getId());
        assertThat(stored.getNomenclature().getId()).isEqualTo(second.getId());
        assertThat(stored.getPeriod()).isEqualTo(DAY.plusDays(1));
        assertThat(stored.getPrice()).isEqualByComparingTo("100");
        var audit = jdbc.queryForMap("select user_id, field_count, payload from entity_change_log where entity = ? and entity_id = ?",
            "NomenclaturePrice", row.getId().toString());
        assertThat(audit.get("USER_ID")).isEqualTo(username);
        assertThat(((Number) audit.get("FIELD_COUNT")).intValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select payload from entity_change_log where entity = ? and entity_id = ?",
            String.class, "NomenclaturePrice", row.getId().toString())).contains("change-register-key:NomenclaturePrice");
    }

    @Test void normalizedKeyNoOpDoesNotAdvanceVersion() {
        var row = createPrice(); grantKeyChange();
        Long version = row.getVersion();
        keyChanges.change(NomenclaturePrice.class, row.getId(), version,
            java.util.Map.of("nomenclature", nomenclature.getId()), DAY.plusHours(12));
        assertThat(row.getVersion()).isEqualTo(version);
        assertThat(jdbc.queryForObject("select count(*) from entity_change_log where entity = ? and entity_id = ?",
            Long.class, "NomenclaturePrice", row.getId().toString())).isZero();
    }

    @Test void oldWriteRightDoesNotGrantTheNewAction() {
        var row = createPrice();
        assertThat(keyPermissions.canChangeKey(NomenclaturePrice.class, row)).isFalse();
        assertThatThrownBy(() -> keyChanges.change(NomenclaturePrice.class, row.getId(), row.getVersion(),
            java.util.Map.of("nomenclature", nomenclature.getId()), DAY.plusDays(1)))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void staleVersionDoesNotMoveTheKey() {
        var row = createPrice(); grantKeyChange();
        assertThatThrownBy(() -> keyChanges.change(NomenclaturePrice.class, row.getId(), row.getVersion() + 1,
            java.util.Map.of("nomenclature", nomenclature.getId()), DAY.plusDays(1)))
            .isInstanceOf(jakarta.persistence.OptimisticLockException.class);
        assertThat(row.getPeriod()).isEqualTo(DAY);
    }

    @Test void independentKeyCollisionIsRejectedRatherThanMerged() {
        var first = createPrice();
        var second = access.create(NomenclaturePrice.class,
            new NomenclaturePrice(nomenclature, DAY.plusDays(1), new BigDecimal("150")));
        grantKeyChange();
        assertThatThrownBy(() -> keyChanges.change(NomenclaturePrice.class, first.getId(), first.getVersion(),
            java.util.Map.of("nomenclature", nomenclature.getId()), second.getPeriod()))
            .isInstanceOf(ValidationException.class).hasMessageContaining("уже существует");
    }

    @Test void managedKeyChangeIsRejectedAtFlushEvenWithoutCanonicalSave() {
        var row = createPrice();
        row.setPeriod(DAY.plusDays(1));
        assertThatThrownBy(entityManager::flush).hasMessageContaining("REGISTER_KEY_CHANGE_REQUIRED");
    }

    @Test void managedDimensionChangeCannotRideWithAResourceUpdate() {
        var row = createPrice();
        Nomenclature second = new Nomenclature("x" + username, "Другая", nomenclature.getUnitOfMeasurement());
        entityManager.persist(second); entityManager.flush();
        row.setNomenclature(second); row.setPrice(new BigDecimal("80"));
        assertThatThrownBy(entityManager::flush).hasMessageContaining("REGISTER_KEY_CHANGE_REQUIRED");
    }

    @Test void unavailableReferenceDoesNotRemoveTheFilterOrChangeTheKey() {
        var row = createPrice(); grantKeyChange();
        assertThatThrownBy(() -> keyChanges.change(NomenclaturePrice.class, row.getId(), row.getVersion(),
            java.util.Map.of("nomenclature", Long.MAX_VALUE), DAY.plusDays(1)))
            .isInstanceOf(ValidationException.class).hasMessageContaining("недоступны");
        assertThat(row.getPeriod()).isEqualTo(DAY);
    }

    @Test void allDimensionsAndPeriodAreMandatory() {
        var row = createPrice(); grantKeyChange();
        assertThatThrownBy(() -> keyChanges.change(NomenclaturePrice.class, row.getId(), row.getVersion(),
            java.util.Map.of(), DAY.plusDays(1)))
            .isInstanceOf(ValidationException.class).hasMessageContaining("все измерения");
    }

    private void grantKeyChange() {
        grant(C5PermissionKeys.action(RegisterKeyChangePermissions.permissionId(NomenclaturePrice.class)), true);
        grant(C5PermissionKeys.entityRead(Nomenclature.class), false);
        entityManager.flush();
    }

    private void grant(String key, boolean update) {
        AccessGrant value = new AccessGrant(); value.setSubjectType(AccessGrant.SubjectType.USER);
        value.setSubjectKey(username); value.setDimension(key);
        value.setCanRead(!update); value.setCanUpdate(update); entityManager.persist(value);
    }
}

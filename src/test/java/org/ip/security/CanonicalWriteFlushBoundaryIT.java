package org.ip.security;

import jakarta.persistence.EntityManager;
import org.ip.config.DataInitializer;
import org.ip.model.UnitOfMeasurement;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C5.6 (ADR-0017 §9): вооружённая финальная граница записи в настоящем приложении.
 *
 * <p>Тип {@link UnitOfMeasurement} — предмет этого контекста: canonical write handle у него
 * есть, измерений RLS нет. Пока флаг {@code rls.write-boundary.enforced} снят, прямой
 * {@code EntityManager} в обход canonical path не отклоняется вовсе; здесь флаг включён, и
 * граница обязана отказать на flush, а canonical path — пройти.</p>
 *
 * <p>Свойство задано на самом тесте, поэтому вооружён именно этот контекст. Реестр измерений
 * принадлежит экземплярам (регистратор, слушатель, enforcer) внутри контекста: раньше
 * вооружение жило в статическом мосте и после этого класса оставалось включённым для всех
 * последующих контекстов той же JVM. Теперь состояние контекста умирает вместе с ним, и
 * соседние тесты (например, {@code RlsAutoConfigurationSmokeTest}, пишущий {@code Role})
 * не получают чужого вооружения.</p>
 *
 * <p>{@link DataInitializer} подменён намеренно: демо-данные писали бы те же типы на старте и
 * отказ прилетел бы из инициализации, а не из проверяемого сценария.</p>
 */
@SpringBootTest(properties = "rls.write-boundary.enforced=true")
class CanonicalWriteFlushBoundaryIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private EntityDataAccess access;

    @Autowired
    private AccessGrantRepository grants;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Разрешённый путь: авторизация зафиксирована canonical write preflight и потреблена
     * настоящим PRE_INSERT. Пара к тесту ниже — без неё отказ был бы вакуумным.
     */
    @Test
    void canonicalCreateAndUpdateOfAnUnprotectedTypePassTheBoundary() {
        login();

        UnitOfMeasurement created = access.create(UnitOfMeasurement.class,
            unit(code("OK")));
        assertThat(created.getId()).isNotNull();
        assertThat(read(created.getId())).isNotNull();

        created.setName("Обновлено через canonical path");
        UnitOfMeasurement updated = access.update(UnitOfMeasurement.class, created);
        assertThat(updated.getName()).isEqualTo("Обновлено через canonical path");
    }

    /** Прямая запись мимо canonical path: авторизации нет, поэтому отказ приходит на flush. */
    @Test
    void directPersistOfAnUnprotectedTypeIsDeniedAtFlush() {
        login();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
            entityManager.persist(unit(code("DN")))))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("canonical write preflight")
            .hasMessageContaining(UnitOfMeasurement.class.getName());
    }

    /**
     * Изменение managed-сущности в новой транзакции: canonical update не вызывался, поэтому
     * dirty-checking на flush обязан отказать. Это и есть суть ADR-0017 §9 — до границы такой
     * вход не проверял никто.
     */
    @Test
    void dirtyChangeOutsideTheCanonicalPathIsDeniedAtFlush() {
        login();
        Long id = access.create(UnitOfMeasurement.class, unit(code("DR"))).getId();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            UnitOfMeasurement managed = entityManager.find(UnitOfMeasurement.class, id);
            managed.setName("в обход canonical path");
            entityManager.flush();
        }))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("canonical write preflight");

        assertThat(read(id).getName()).isNotEqualTo("в обход canonical path");
    }

    /** DELETE в обход canonical path: авторизация не выдана ничем — отказ на commit-time flush. */
    @Test
    void directRemoveOfAnUnprotectedTypeIsDeniedAtFlush() {
        login();
        Long id = access.create(UnitOfMeasurement.class, unit(code("RM"))).getId();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            UnitOfMeasurement managed = entityManager.find(UnitOfMeasurement.class, id);
            entityManager.remove(managed);
        }))
            .isInstanceOf(TransactionSystemException.class)
            .hasRootCauseInstanceOf(RlsAccessDeniedException.class);

        assertThat(read(id)).isNotNull();
    }

    /** Удаление через canonical path: авторизация отдельная, но фиксируется тем же preflight. */
    @Test
    void canonicalDeleteOfAnUnprotectedTypePassesTheBoundary() {
        login();
        Long id = access.create(UnitOfMeasurement.class, unit(code("DL"))).getId();

        access.delete(UnitOfMeasurement.class, id);

        assertThat(read(id)).isNull();
    }

    private String login() {
        String actor = RlsTestFixture.authenticateAsSuperuser(grants);
        RlsTestFixture.grantC5EntityRead(grants, actor, UnitOfMeasurement.class);
        return actor;
    }

    private UnitOfMeasurement read(Long id) {
        return transactions.execute(status -> entityManager.find(UnitOfMeasurement.class, id));
    }

    private static UnitOfMeasurement unit(String code) {
        return new UnitOfMeasurement(code, "Единица " + code, code);
    }

    /**
     * {@code shortCode} и {@code code} ограничены десятью символами ({@code @Size(max = 10)}),
     * поэтому суффикс короткий: тест проверяет границу записи, а не валидацию длины.
     */
    private static String code(String tag) {
        return tag + UUID.randomUUID().toString().substring(0, 6);
    }
}

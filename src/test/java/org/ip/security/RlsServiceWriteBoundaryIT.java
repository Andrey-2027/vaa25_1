package org.ip.security;

import jakarta.persistence.EntityManager;
import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.repository.JournalRepository;
import org.ip.service.JournalService;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.events.EntityEventPublisher;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Characterization границы write/delete enforcement (срез C3.0.1).
 *
 * <p>Фиксирует контракт write/delete enforcement. Ранняя сервисная проверка
 * (`checkRlsWrite/checkRlsDelete`) была удалена в C3.0.1 как «дубль» repository-aspect'а
 * и возвращена в C3.7 — но уже на входе в операцию, а не перед {@code repository.save}:
 * отказ обязан приходить ДО bean-валидации, business-правил, lifecycle hooks и
 * before-событий, иначе запрещённая операция запускает пользовательский код
 * (ср. {@code authorizeWrite} в {@code AbstractBaseService}).</p>
 * <ul>
 * <li>сервисный путь защищён ранним гейтом, repository-aspect и flush-listener остаются
 *     последним рубежом для каналов в обход сервиса;</li>
 * <li>отказ не раскрывает результаты бизнес-валидации и не вызывает lifecycle-хуки,
 *     before-события и удаление owned sections;</li>
 * <li>прямой {@code EntityManager} в обход сервиса защищён flush-time listener'ом.</li>
 * </ul>
 *
 * <p>Используется измерение {@code JOURNAL}: {@link Journal} объявляет
 * {@code @RlsDimension("JOURNAL")} с default {@code valuePaths = {"id"}}, поэтому новые
 * записи проверяются по {@code id == null}, существующие — по своему id.</p>
 */
@SpringBootTest
class RlsServiceWriteBoundaryIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    /**
     * Шпионы пользовательских точек расширения: ими заменяется
     * «внешний побочный эффект тестового listener'а» — важно не то, что он изменил,
     * а сам факт получения управления.
     */
    @MockitoSpyBean
    private EntityEventPublisher entityEventPublisher;

    @MockitoSpyBean
    private EntityLifecycleRegistry lifecycleRegistry;

    @MockitoSpyBean
    private GenericOwnedSectionService ownedSections;

    @Autowired
    private JournalService journals;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private AccessGrantRepository grants;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- сервисный путь

    @Test
    void serviceCreateAllowedByUpdateGrantPersists() {
        String actor = actor("allow-create");
        login(actor);
        grants.saveAndFlush(grant(actor, null, true, true, false));

        Journal saved = journals.create(journal("WSB-AC-" + suffix()));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void serviceCreateDeniedWithoutUpdateGrantLeavesNoRow() {
        String actor = actor("deny-create");
        String code = "WSB-DC-" + suffix();
        // Единственный грант измерения JOURNAL — чужой, поэтому bootstrap создания новых
        // значений выключен, а у actor прав нет вовсе.
        grants.saveAndFlush(grant(actor("holder"), null, true, true, false));
        login(actor);

        assertThatThrownBy(() -> journals.create(journal(code)))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Нет прав на изменение");

        assertThat(existsAsSuperuser(code)).isFalse();
    }

    @Test
    void serviceUpdateAllowedByUpdateGrantPersists() {
        String actor = actor("allow-update");
        Long id = createAsSuperuser("WSB-AU-" + suffix());
        login(actor);
        grants.saveAndFlush(grant(actor, id, true, true, false));

        Journal detached = loadAsSuperuser(id);
        detached.setName("обновлено");
        journals.update(detached);

        assertThat(loadAsSuperuser(id).getName()).isEqualTo("обновлено");
    }

    @Test
    void serviceUpdateDeniedWithoutUpdateGrantDoesNotPersist() {
        String actor = actor("deny-update");
        Long id = createAsSuperuser("WSB-DU-" + suffix());
        login(actor);
        grants.saveAndFlush(grant(actor, id, true, false, false));

        Journal detached = loadAsSuperuser(id);
        detached.setName("запрещено");

        assertThatThrownBy(() -> journals.update(detached))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Нет прав на изменение");

        assertThat(loadAsSuperuser(id).getName()).isNotEqualTo("запрещено");
    }

    @Test
    void serviceDeleteAllowedByDeleteGrantRemovesRow() {
        String actor = actor("allow-delete");
        Long id = createAsSuperuser("WSB-AD-" + suffix());
        login(actor);
        grants.saveAndFlush(grant(actor, id, true, false, true));

        journals.delete(id);

        assertThat(findAsSuperuser(id)).isEmpty();
    }

    @Test
    void serviceDeleteDeniedWithoutDeleteGrantKeepsRow() {
        String actor = actor("deny-delete");
        Long id = createAsSuperuser("WSB-DD-" + suffix());
        login(actor);
        grants.saveAndFlush(grant(actor, id, true, false, false));

        assertThatThrownBy(() -> journals.delete(id))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Нет прав на удаление");

        assertThat(findAsSuperuser(id)).isPresent();
    }

    // ------------------------------------------------ ранний гейт: пользовательский код

    /**
     * Запрещённая операция не должна доходить до валидации и lifecycle-пути. Проверяется
     * без мока валидатора: entity нарушает {@code @NotBlank}, но ожидаемый отказ — от
     * авторизации. Если бы валидатор получил управление раньше, тест увидел бы
     * {@code ValidationException} — то есть запрещённая операция раскрыла бы результат
     * бизнес-валидации.
     */
    @Test
    void deniedCreateReportsAuthorizationNotValidationVerdict() {
        String actor = actor("deny-create-early");
        grants.saveAndFlush(grant(actor("holder"), null, true, true, false));
        login(actor);

        Journal invalid = new Journal(); // code/name пусты — @NotBlank не выполнен

        assertThatThrownBy(() -> journals.create(invalid))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Нет прав на изменение");

        verify(entityEventPublisher, never()).publishSaving(any(), any());
        verify(lifecycleRegistry, never()).beforeSave(any(), any(), any());
    }

    /**
     * Запрещённый update не трогает даже нормализацию: {@code normalizeVersion} выставил бы
     * version = 0 у detached-строки с version = null. Значит, объект остаётся неизменным.
     */
    @Test
    void deniedUpdateLeavesEntityUntouchedBeforeNormalization() {
        String actor = actor("deny-update-early");
        Long id = createAsSuperuser("WSB-DUE-" + suffix());
        login(actor);
        grants.saveAndFlush(grant(actor, id, true, false, false));

        Journal detached = loadAsSuperuser(id);
        detached.setVersion(null);
        detached.setName("запрещено");

        assertThatThrownBy(() -> journals.update(detached))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Нет прав на изменение");

        assertThat(detached.getVersion()).as("normalizeVersion не выполнялся").isNull();
        verify(entityEventPublisher, never()).publishSaving(any(), any());
        verify(lifecycleRegistry, never()).beforeUpdate(any(), any(), any(), any());
    }

    /**
     * Запрещённое удаление останавливается до before-события, lifecycle-хука и шага
     * удаления owned sections. До C3.7 owned sections удалялись ДО проверки права: их
     * отменял только rollback, а внешний побочный эффект listener'а — ничто.
     */
    @Test
    void deniedDeleteStopsBeforeEventsLifecycleAndOwnedSections() {
        String actor = actor("deny-delete-early");
        Long id = createAsSuperuser("WSB-DDE-" + suffix());
        login(actor);
        grants.saveAndFlush(grant(actor, id, true, false, false));

        assertThatThrownBy(() -> journals.delete(id))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Нет прав на удаление");

        verify(entityEventPublisher, never()).publishDeleting(any(), any());
        verify(lifecycleRegistry, never()).beforeDelete(any(), any(), any());
        verify(ownedSections, never()).deleteAllOwnedSections(any());
        assertThat(findAsSuperuser(id)).isPresent();
    }

    /**
     * Контроль не-вакуумности проверок выше: на разрешённом пути те же шпионы обязаны
     * фиксировать вызовы. Без этой пары {@code never()} проходил бы и при отключённом
     * lifecycle-контуре.
     */
    @Test
    void allowedWriteStillRunsTheSameLifecycleSteps() {
        String actor = actor("allow-hooks");
        Long id = createAsSuperuser("WSB-AH-" + suffix());
        login(actor);
        grants.saveAndFlush(grant(actor, id, true, true, true));

        journals.update(loadAsSuperuser(id));
        verify(entityEventPublisher).publishSaving(any(), any());
        verify(lifecycleRegistry).beforeSave(any(), any(), any());

        journals.delete(id);
        verify(entityEventPublisher).publishDeleting(any(), any());
        verify(lifecycleRegistry).beforeDelete(any(), any(), any());
        verify(ownedSections).deleteAllOwnedSections(any());
        assertThat(findAsSuperuser(id)).isEmpty();
    }

    // ---------------------------------------------- прямой EntityManager (flush-listener)

    /**
     * INSERT в обход сервиса и repository: capability не выдана ничем, поэтому отказ обязан
     * прийти от Hibernate flush-listener'а, а не от удаляемой сервисной проверки.
     */
    @Test
    void directEntityManagerPersistWithoutAuthorizationIsDeniedAtFlush() {
        String actor = actor("em-persist");
        grants.saveAndFlush(grant(actor("holder"), null, true, true, false));
        login(actor);

        // IDENTITY-стратегия выполняет insert на persist(), поэтому отказ уходит наружу
        // без обёртки транзакции (в отличие от commit-time flush у remove ниже).
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
            entityManager.persist(journal("WSB-EP-" + suffix()))))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("without an RLS authorization");
    }

    /** DELETE в обход сервиса: право чтения есть, права удаления нет — отказ на flush. */
    @Test
    void directEntityManagerRemoveWithoutDeleteGrantIsDeniedAtFlush() {
        String actor = actor("em-remove");
        Long id = createAsSuperuser("WSB-ER-" + suffix());
        login(actor);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            Journal managed = entityManager.find(Journal.class, id);
            entityManager.remove(managed);
        }))
            .isInstanceOf(TransactionSystemException.class)
            .hasRootCauseInstanceOf(RlsAccessDeniedException.class);

        assertThat(findAsSuperuser(id)).isPresent();
    }

    // ------------------------------------------------------------------------ helpers

    private Long createAsSuperuser(String code) {
        return RlsTestFixture.callAsSuperuser(grants, () ->
            journalRepository.saveAndFlush(journal(code)).getId());
    }

    private Journal loadAsSuperuser(Long id) {
        return RlsTestFixture.callAsSuperuser(grants, () ->
            journalRepository.findById(id).orElseThrow());
    }

    private java.util.Optional<Journal> findAsSuperuser(Long id) {
        return RlsTestFixture.callAsSuperuser(grants, () -> journalRepository.findById(id));
    }

    private boolean existsAsSuperuser(String code) {
        return RlsTestFixture.callAsSuperuser(grants, () ->
            journalRepository.findByCode(code).isPresent());
    }

    private void login(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private AccessGrant grant(String subjectKey, Long dimensionValueId,
                              boolean read, boolean update, boolean delete) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(subjectKey);
        grant.setDimension("JOURNAL");
        grant.setDimensionValueId(dimensionValueId);
        grant.setCanRead(read);
        grant.setCanUpdate(update);
        grant.setCanDelete(delete);
        return grant;
    }

    private static Journal journal(String code) {
        Journal journal = new Journal();
        journal.setCode(code);
        journal.setName(code);
        return journal;
    }

    private static String actor(String kind) {
        return "wsb-" + kind + "-" + suffix();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

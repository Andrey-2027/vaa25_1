package org.ip.application.register;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.*;
import org.ipro.vaadin.register.RecorderRefPresentation;
import org.ipro.vaadin.register.RecorderRefPresentationResolver;
import org.ipro.crud.MetadataDrivenAggregateSaveService.SectionInput;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EventSource;
import org.ipro.register.*;
import org.ipro.rls.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.data.domain.PageRequest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = Application.class, properties = "rls.write-boundary.enforced-types=org.ip.model.AccountingObjectInstallation,org.ip.model.AccountingObjectCorrection,org.ip.model.NomenclatureAccountingObject")
class AccountingObjectPilotIT {
    @Autowired RegisterRecords registerRecords;
    @Autowired RegisterKeyChanges keyChanges;
    @Autowired RegisterKeyChangePermissions keyPermissions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private static final LocalDateTime DATE = LocalDateTime.of(2026, 10, 6, 10, 0);
    @MockitoBean DataInitializer demo;
    @Autowired AccountingObjectSaveService commands;
    @Autowired org.ipro.crud.MetadataDrivenAggregateSaveService aggregateSave;
    @Autowired AccountingObjectRegisterService reader;
    @Autowired EntityDataAccess access;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AccessGrantRepository grants;
    @Autowired RlsFilterActivator filters;
    @Autowired RlsPolicyEnforcer policies;
    @Autowired org.ipro.rls.c5.C5PermissionDecisionService permissions;
    @Autowired RecorderRefPresentationResolver captions;
    @Autowired org.ipro.form.registry.FormResolver forms;
    @Autowired org.ip.application.form.ItemFormSaveDispatcher formSaves;
    @Autowired org.ipro.metadata.MetadataResolver metadata;
    @Autowired org.ipro.form.FieldFactory fields;
    @Autowired org.ipro.form.TableSectionFactory tables;
    @Autowired org.ipro.crud.EntityCopyService copies;
    private String username;
    private Fixture fixture;

    @BeforeEach void setUp() {
        username = RlsTestFixture.authenticateAsSuperuser(grants);
        RlsTestFixture.grantC5EntityRead(grants, username, Workshop.class, Nomenclature.class,
            AccountingType.class, UnitOfMeasurement.class, AccountingObjectInstallation.class,
            AccountingObjectCorrection.class, NomenclatureAccountingObject.class);
        fixture = new TransactionTemplate(transactionManager).execute(status -> {
            filters.ensureRlsEnabled(entityManager);
            String key = UUID.randomUUID().toString().substring(0, 8);
            Workshop a = new Workshop("a" + key, "Цех А");
            Workshop b = new Workshop("b" + key, "Цех Б");
            UnitOfMeasurement unit = new UnitOfMeasurement("u" + key, "Штуки", "u" + key);
            a = access.create(Workshop.class, a);
            b = access.create(Workshop.class, b);
            unit = access.create(UnitOfMeasurement.class, unit);
            Nomenclature n = new Nomenclature("n" + key, "Изделие", unit);
            Nomenclature n2 = new Nomenclature("m" + key, "Второе изделие", unit);
            AccountingType fifo = new AccountingType("f" + key, "ФИФО");
            AccountingType lifo = new AccountingType("l" + key, "ЛИФО");
            AccountingType ske = new AccountingType("s" + key, "СКЕ");
            n = aggregateSave.save(n, List.of(), EventSource.UI).aggregate();
            n2 = aggregateSave.save(n2, List.of(), EventSource.UI).aggregate();
            fifo = access.create(AccountingType.class, fifo);
            lifo = access.create(AccountingType.class, lifo);
            ske = access.create(AccountingType.class, ske);
            entityManager.flush();
            return new Fixture(a, b, n, n2, fifo, lifo, ske);
        });
    }
    @AfterEach void clearSubject() { SecurityContextHolder.clearContext(); }

    @Test void recordedRegisterUsesStandardListAndKeepsRecorderColumnAndSliceState() {
        install(fixture.a, fixture.n, fixture.fifo, DATE);
        var last = correct(fixture.a, fixture.n, fixture.lifo, DATE.plusDays(1));
        var form = forms.<NomenclatureAccountingObject, Long>resolveListForm(NomenclatureAccountingObject.class, null, null);
        assertThat(form).isInstanceOf(org.ipro.form.builtin.ListForm.class);
        assertThat(form.isReadOnly()).isTrue();
        assertThat(form.getGrid().getColumnByKey("recorder")).isNotNull();
        form.setOpeningContextFilters(Map.of("workshop", fixture.a, "nomenclature", fixture.n));
        assertThat(form.getGrid().getDataProvider().size(new com.vaadin.flow.data.provider.Query<>())).isEqualTo(2);
        var state = form.captureViewState().withOptions(Map.of("register.mode", "Срез последних", "register.asOf", DATE.plusDays(2).toString(),
            "register.context.workshop", fixture.a.getId().toString(), "register.context.nomenclature", fixture.n.getId().toString()));
        form.applyViewState(state);
        assertThat(form.getGrid().getDataProvider().size(new com.vaadin.flow.data.provider.Query<>())).isEqualTo(1);
        var rows = form.getGrid().getDataProvider().fetch(new com.vaadin.flow.data.provider.Query<>(0, 50, List.of(), null, null)).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getRecorder().getId()).isEqualTo(last.getId());
        assertThat(rows.getFirst().getAccountingType().getId()).isEqualTo(fixture.lifo.getId());
        var reopened = forms.<NomenclatureAccountingObject, Long>resolveListForm(NomenclatureAccountingObject.class, null, null);
        reopened.applyViewState(org.ipro.metadata.GridViewState.fromJson(form.captureViewState().toJson()));
        assertThat(reopened.getGrid().getDataProvider().size(new com.vaadin.flow.data.provider.Query<>())).isEqualTo(1);
    }

    @Test void onSaveRegenerationReplacesAnEarlierManualKeyCorrectionExactlyOnce() {
        var owner = install(fixture.a, fixture.n, fixture.fifo, DATE);
        var row = reader.history(fixture.a, PageRequest.of(0, 20)).getContent().getFirst();
        var grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER); grant.setSubjectKey(username);
        grant.setDimension("ACTION:" + RegisterKeyChangePermissions.permissionId(NomenclatureAccountingObject.class));
        grant.setCanUpdate(true); grants.save(grant);
        keyChanges.change(NomenclatureAccountingObject.class, row.getId(), row.getVersion(),
            Map.of("workshop", fixture.b.getId(), "nomenclature", fixture.n2.getId()), DATE.plusDays(3));
        owner = access.detail(AccountingObjectInstallation.class, owner.getId()).orElseThrow();
        var position = new AccountingObjectInstallationItem(); position.setNomenclature(fixture.n); position.setAccountingType(fixture.ske);
        var saved = commands.save(owner, List.of(new SectionInput(AccountingObjectInstallationItem.class, List.of(position))), EventSource.UI).aggregate();
        var replacement = reader.history(fixture.a, PageRequest.of(0, 20)).getContent();
        assertThat(replacement).hasSize(1);
        assertThat(replacement.getFirst().getId()).isNotEqualTo(row.getId());
        assertThat(replacement.getFirst().getRecorder().getId()).isEqualTo(saved.getId());
        assertThat(replacement.getFirst().getAccountingType().getId()).isEqualTo(fixture.ske.getId());
        assertThat(reader.history(fixture.b, PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test void correctionFormRecalculatesComparisonWithoutDiscardingDraftRows() {
        install(fixture.a, fixture.n, fixture.fifo, DATE);
        var correction = correct(fixture.a, fixture.n, fixture.lifo, DATE.plusDays(1));
        org.ipro.form.builtin.ItemForm<AccountingObjectCorrection> form = forms.resolveItemForm(
            AccountingObjectCorrection.class, null, null, null);
        form.setEntity(access.detail(AccountingObjectCorrection.class, correction.getId()).orElseThrow());
        assertThat(form.isDirty()).isFalse();
        var table = form.tableSection(AccountingObjectCorrectionItem.class);
        var row = table.getRows().getFirst();
        assertThat(row.getDocument()).isSameAs(form.peekEntity());
        assertThat(row.getCurrentAccountingType().getId()).isEqualTo(fixture.fifo.getId());
        row.setAccountingType(fixture.ske); table.markDirty();
        form.getEntityField("date", LocalDateTime.class).setValue(DATE.minusDays(1));
        assertThat(form.isDirty()).isTrue();
        assertThat(table.getRows().getFirst()).isSameAs(row);
        assertThat(row.getCurrentAccountingType()).isNull();
        assertThat(row.getAccountingType()).isSameAs(fixture.ske);
        assertThat(table.isDirty()).isTrue();
        form.getEntityField("date", LocalDateTime.class).setValue(DATE.plusDays(2));
        assertThat(row.getCurrentAccountingType().getId()).isEqualTo(fixture.fifo.getId());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            form.<Workshop>entityField("workshop"), "onSelectedInDialog", fixture.b);
        assertThat(row.getCurrentAccountingType()).isNull();
        assertThat(table.getRows()).hasSize(1);
    }

    @Test void correctionRowFormDisplaysCurrentValueAndClearsNewTypeWhenSelectionChanges() {
        install(fixture.a, fixture.n, fixture.fifo, DATE);
        var document = new AccountingObjectCorrection(); document.setDate(DATE.plusDays(1)); document.setWorkshop(fixture.a);
        var row = new AccountingObjectCorrectionItem(); row.setDocument(document);
        row.setNomenclature(fixture.n); row.setAccountingType(fixture.lifo);
        org.ipro.form.builtin.ItemForm<AccountingObjectCorrectionItem> form = forms.resolveItemForm(
            AccountingObjectCorrectionItem.class,
            org.ip.views.forms.AccountingObjectCorrectionRowConfig.VARIANT, null, null);
        form.setEntity(row);
        assertThat(form.isDirty()).isFalse();
        assertThat(form.<Nomenclature>entityField("nomenclature").isRequiredIndicatorVisible()).isTrue();
        assertThat(form.<AccountingType>entityField("accountingType").isRequiredIndicatorVisible()).isTrue();
        assertThat(form.<AccountingType>entityField("currentAccountingType").isReadOnly()).isTrue();
        assertThat(form.<AccountingType>entityField("currentAccountingType").getValue().getId())
            .isEqualTo(fixture.fifo.getId());
        assertThat(form.<AccountingType>entityField("accountingType").getValue()).isSameAs(fixture.lifo);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            form.<Nomenclature>entityField("nomenclature"), "onSelectedInDialog", fixture.n2);
        assertThat(form.<AccountingType>entityField("currentAccountingType").getValue()).isNull();
        assertThat(form.<AccountingType>entityField("accountingType").getValue()).isNull();
    }

    @Test void correctionOpeningRecalculatesCurrentTypesExactlyOnce() {
        install(fixture.a, fixture.n, fixture.fifo, DATE);
        var correction = correct(fixture.a, fixture.n, fixture.lifo, DATE.plusDays(1));
        var counting = mock(AccountingObjectRegisterService.class);
        doAnswer(invocation -> {
            reader.refreshCurrentTypes(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(counting).refreshCurrentTypes(any(), anyList());
        var form = new org.ip.views.forms.AccountingObjectCorrectionFormConfig.CorrectionForm(counting);
        form.buildDescription(metadata.resolve(AccountingObjectCorrection.class), fields);
        tables.attachTableSections(form, AccountingObjectCorrection.class);
        form.setEntity(access.detail(AccountingObjectCorrection.class, correction.getId()).orElseThrow());
        verify(counting, times(1)).refreshCurrentTypes(any(), anyList());
        assertThat(form.<AccountingObjectCorrectionItem>tableSection(AccountingObjectCorrectionItem.class)
            .getRows().getFirst().getCurrentAccountingType().getId()).isEqualTo(fixture.fifo.getId());
        form.applySavedState(() -> form.applyPersistedEntity(form.peekEntity()));
        verify(counting, times(1)).refreshCurrentTypes(any(), anyList());
        form.getEntityField("date", LocalDateTime.class).setValue(DATE.minusDays(1));
        verify(counting, times(2)).refreshCurrentTypes(any(), anyList());
    }

    @Test void correctionFormSavesDraftRowAndReopensWithCurrentAndNewTypes() {
        install(fixture.a, fixture.n, fixture.fifo, DATE);
        var correction = correct(fixture.a, fixture.n, fixture.lifo, DATE.plusDays(1));
        org.ipro.form.builtin.ItemForm<AccountingObjectCorrection> form = forms.resolveItemForm(
            AccountingObjectCorrection.class, null, null, null);
        form.setEntity(access.detail(AccountingObjectCorrection.class, correction.getId()).orElseThrow());
        var table = form.tableSection(AccountingObjectCorrectionItem.class);
        var row = table.getRows().getFirst();
        row.setAccountingType(fixture.ske);
        table.markDirty();
        form.getEntityField("date", LocalDateTime.class).setValue(DATE);
        assertThat(row.getCurrentAccountingType().getId()).isEqualTo(fixture.fifo.getId());
        assertThat(row.getAccountingType().getId()).isEqualTo(fixture.ske.getId());
        form.setSaveHandler(value -> (org.ipro.form.FormSaveResult) formSaves.save((org.ipro.form.builtin.ItemForm) value));
        assertThat(form.save()).isInstanceOf(org.ipro.form.FormSaveResult.Success.class);
        assertThat(form.isDirty()).isFalse();
        assertThat(table.isDirty()).isFalse();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.flush(); entityManager.clear();
        });
        org.ipro.form.builtin.ItemForm<AccountingObjectCorrection> reopened = forms.resolveItemForm(
            AccountingObjectCorrection.class, null, null, null);
        reopened.setEntity(access.detail(AccountingObjectCorrection.class, correction.getId()).orElseThrow());
        var reopenedRow = reopened.tableSection(AccountingObjectCorrectionItem.class).getRows().getFirst();
        assertThat(reopenedRow.getAccountingType().getId()).isEqualTo(fixture.ske.getId());
        assertThat(reopenedRow.getCurrentAccountingType().getId()).isEqualTo(fixture.fifo.getId());
        assertThat(reopened.isDirty()).isFalse();
    }

    @Test void correctionCopyKeepsRowsAndSavedNewTypesWithoutFalseHeaderDirty() {
        install(fixture.a, fixture.n, fixture.fifo, DATE);
        var source = correct(fixture.a, fixture.n, fixture.lifo, DATE.plusDays(1));
        var loaded = access.detail(AccountingObjectCorrection.class, source.getId()).orElseThrow();
        var headerCopy = copies.copyEntity(loaded);
        var rowsCopy = tables.copyRowsFor(AccountingObjectCorrection.class, loaded, headerCopy, copies);
        org.ipro.form.builtin.ItemForm<AccountingObjectCorrection> form = forms.resolveItemForm(
            AccountingObjectCorrection.class, null, null, null);
        form.setEntityFactory(() -> headerCopy);
        form.initializeNewEntity(Map.of());
        assertThat(form.isDirty()).isFalse();
        var table = form.tableSection(AccountingObjectCorrectionItem.class);
        @SuppressWarnings("unchecked")
        var rows = (List<AccountingObjectCorrectionItem>) rowsCopy.get(AccountingObjectCorrectionItem.class);
        table.applyPersistedRows(form.peekEntity(), rows);
        table.markDirty();
        assertThat(table.getRows()).hasSize(1);
        assertThat(table.getRows().getFirst().getAccountingType().getId()).isEqualTo(fixture.lifo.getId());
        assertThat(table.getRows().getFirst().getCurrentAccountingType().getId()).isEqualTo(fixture.fifo.getId());
        assertThat(table.isDirty()).isTrue();
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void documentFormUsesAggregateHandlerAndAppliesCommittedRows() {
        org.ipro.form.builtin.ItemForm<AccountingObjectInstallation> form = forms.resolveItemForm(
            AccountingObjectInstallation.class, null, null, null);
        form.initializeNewEntity(Map.of("number", "ui-" + UUID.randomUUID(), "date", DATE, "workshop", fixture.a));
        form.getEntityField("number", String.class).setValue("ui-" + UUID.randomUUID().toString().substring(0, 8));
        var table = form.tableSection(AccountingObjectInstallationItem.class);
        table.applyPersistedRows(form.getEntity(), List.of(installationLine(fixture.n, fixture.fifo)));
        table.markDirty();
        form.setSaveHandler(value -> (org.ipro.form.FormSaveResult) formSaves.save((org.ipro.form.builtin.ItemForm) value));
        assertThat(form.save()).isInstanceOf(org.ipro.form.FormSaveResult.Success.class);
        assertThat(table.getRows().getFirst().getId()).isNotNull();
        assertThat(table.isDirty()).isFalse();
        assertThat(history()).hasSize(1);
    }

    @Test void installationAndCorrectionProduceIndependentHistoryAndInclusiveSlice() {
        install(fixture.a, fixture.n, fixture.fifo, DATE);
        var correction = correct(fixture.a, fixture.n, fixture.lifo, DATE.plusDays(1));
        assertThat(history()).hasSize(2);
        assertThat(typeOn(DATE)).isEqualTo(fixture.fifo.getId());
        assertThat(typeOn(DATE.plusDays(1))).isEqualTo(fixture.lifo.getId());
        assertThat(reader.currentType(correction, fixture.n.getId()).orElseThrow().getId())
            .as("editable correction must show the previous value, excluding its own recorder").isEqualTo(fixture.fifo.getId());
        var line = correctionRows(correction).getFirst();
        assertThat(line.getCurrentAccountingType().getId()).isEqualTo(fixture.fifo.getId());
        assertThat(line.getAccountingType().getId()).isEqualTo(fixture.lifo.getId());
    }

    @Test void installationChecksFutureHistoryAndWorkshopIsAnIndependentDimension() {
        install(fixture.a, fixture.n, fixture.fifo, DATE.plusDays(20));
        assertThatThrownBy(() -> install(fixture.a, fixture.n, fixture.lifo, DATE))
            .isInstanceOf(ValidationException.class).hasMessageContaining("истории");
        install(fixture.b, fixture.n, fixture.lifo, DATE);
        assertThat(reader.latest(fixture.a, DATE, PageRequest.of(0, 50))).isEmpty();
        assertThat(reader.latest(fixture.b, DATE, PageRequest.of(0, 50)).getContent()).hasSize(1);
    }

    @Test void repeatInstallationAfterCorrectionKeepsOneOwnSetAndAcceptsItsExistingPair() {
        var installation = install(fixture.a, fixture.n, fixture.fifo, DATE);
        correct(fixture.a, fixture.n, fixture.lifo, DATE.plusDays(1));
        var fresh = access.detail(AccountingObjectInstallation.class, installation.getId()).orElseThrow();
        var changed = installationLine(fixture.n, fixture.ske);
        save(fresh, List.of(changed));
        assertThat(history()).hasSize(2);
        assertThat(typeOn(DATE)).isEqualTo(fixture.ske.getId());
        assertThat(typeOn(DATE.plusDays(1))).isEqualTo(fixture.lifo.getId());
    }

    @Test void equalPeriodsUseLastRecordedSetAcrossTwoRealDocumentTypes() {
        var installation = install(fixture.a, fixture.n, fixture.fifo, DATE);
        var correction = correct(fixture.a, fixture.n, fixture.lifo, DATE);
        assertThat(typeOn(DATE)).isEqualTo(fixture.lifo.getId());
        var fresh = access.detail(AccountingObjectInstallation.class, installation.getId()).orElseThrow();
        save(fresh, List.of(installationLine(fixture.n, fixture.ske)));
        assertThat(typeOn(DATE)).isEqualTo(fixture.ske.getId());
        assertThat(reader.currentType(correction, fixture.n.getId()).orElseThrow().getId()).isEqualTo(fixture.ske.getId());
    }

    @Test void correctionCannotCreateAMissingObjectOrUseAFutureInitialValue() {
        assertThatThrownBy(() -> correct(fixture.a, fixture.n, fixture.lifo, DATE))
            .isInstanceOf(ValidationException.class).hasMessageContaining("срезе");
        install(fixture.a, fixture.n, fixture.fifo, DATE.plusDays(1));
        assertThatThrownBy(() -> correct(fixture.a, fixture.n, fixture.lifo, DATE))
            .isInstanceOf(ValidationException.class).hasMessageContaining("срезе");
    }

    @Test void deletingDocumentsRevealsPreviousWinnerAndEnforcesRemainingHistory() {
        var installation = install(fixture.a, fixture.n, fixture.fifo, DATE);
        var correction = correct(fixture.a, fixture.n, fixture.lifo, DATE.plusDays(1));
        access.delete(AccountingObjectInstallation.class, installation.getId());
        assertThat(history()).hasSize(1);
        assertThatThrownBy(() -> install(fixture.a, fixture.n, fixture.ske, DATE))
            .isInstanceOf(ValidationException.class).hasMessageContaining("истории");
        access.delete(AccountingObjectCorrection.class, correction.getId());
        assertThat(history()).isEmpty();
        install(fixture.a, fixture.n, fixture.ske, DATE);
        assertThat(typeOn(DATE)).isEqualTo(fixture.ske.getId());
    }

    @Test void deletingCorrectionRestoresInstallationAndAChangedDateRefreshesItsCurrentValue() {
        var installation = install(fixture.a, fixture.n, fixture.fifo, DATE);
        var correction = correct(fixture.a, fixture.n, fixture.lifo, DATE.plusDays(1));
        correction.setDate(DATE.minusDays(1));
        assertThat(reader.currentType(correction, fixture.n.getId())).isEmpty();
        correction.setDate(DATE.plusDays(1));
        correction.setWorkshop(fixture.b);
        assertThat(reader.selection(correction)).isEmpty();
        access.delete(AccountingObjectCorrection.class, correction.getId());
        assertThat(typeOn(DATE.plusDays(2))).isEqualTo(fixture.fifo.getId());
        assertThat(history()).singleElement().satisfies(row -> assertThat(row.getRecorder().getId()).isEqualTo(installation.getId()));
    }

    @Test void invalidAddedPairRollsBackHeaderSectionsAndExistingSet() {
        var own = install(fixture.a, fixture.n, fixture.fifo, DATE);
        install(fixture.a, fixture.n2, fixture.lifo, DATE);
        var fresh = access.detail(AccountingObjectInstallation.class, own.getId()).orElseThrow();
        String originalNumber = fresh.getNumber();
        fresh.setNumber("edited-" + UUID.randomUUID().toString().substring(0, 6));
        assertThatThrownBy(() -> save(fresh, List.of(installationLine(fixture.n, fixture.ske),
            installationLine(fixture.n2, fixture.ske)))).isInstanceOf(ValidationException.class);
        var stored = access.detail(AccountingObjectInstallation.class, own.getId()).orElseThrow();
        assertThat(stored.getNumber()).isEqualTo(originalNumber);
        assertThat(typeOn(DATE)).isEqualTo(fixture.fifo.getId());
        var count = new TransactionTemplate(transactionManager).execute(status ->
            entityManager.createQuery("select count(i) from AccountingObjectInstallationItem i where i.document.id = :id",
                Long.class).setParameter("id", own.getId()).getSingleResult());
        assertThat(count).isEqualTo(1L);
    }

    @Test void duplicateRowsAreRejectedAndDocumentDatesUseMilliseconds() {
        var document = installation(fixture.a, DATE);
        assertThatThrownBy(() -> save(document, List.of(installationLine(fixture.n, fixture.fifo),
            installationLine(fixture.n, fixture.lifo)))).isInstanceOf(ValidationException.class).hasMessageContaining("повторяется");
        assertThat(document.getId()).isNull();
        document.setDate(DATE.withNano(123456789));
        save(document, List.of(installationLine(fixture.n, fixture.fifo)));
        assertThat(document.getDate()).isEqualTo(DATE.withNano(123000000));
    }

    @Test void genericHeaderAndRegisterWritesAreClosed() {
        assertThatThrownBy(() -> access.create(AccountingObjectInstallation.class, installation(fixture.a, DATE)))
            .isInstanceOf(org.ipro.data.CanonicalWriteDeniedException.class);
        assertThatThrownBy(() -> aggregateSave.save(installation(fixture.a, DATE),
            List.of(SectionInput.attached(AccountingObjectInstallationItem.class,
                List.of(installationLine(fixture.n, fixture.fifo)))), EventSource.UI))
            .isInstanceOf(ValidationException.class).hasMessageContaining("команду документа");
        var row = new NomenclatureAccountingObject();
        assertThatThrownBy(() -> access.create(NomenclatureAccountingObject.class, row))
            .isInstanceOf(RuntimeException.class);
        assertThat(history()).isEmpty();
    }

    @Test void staleDocumentPayloadCannotReplaceTheNewerSet() {
        var installation = install(fixture.a, fixture.n, fixture.fifo, DATE);
        var first = access.detail(AccountingObjectInstallation.class, installation.getId()).orElseThrow();
        var stale = access.detail(AccountingObjectInstallation.class, installation.getId()).orElseThrow();
        save(first, List.of(installationLine(fixture.n, fixture.lifo)));
        assertThatThrownBy(() -> save(stale, List.of(installationLine(fixture.n, fixture.ske))))
            .isInstanceOf(RuntimeException.class);
        assertThat(typeOn(DATE)).isEqualTo(fixture.lifo.getId());
        assertThat(history()).hasSize(1);
    }

    @Test void registerRemainsReadableWhenItsDocumentIsDeniedAndCaptionIsMasked() {
        install(fixture.a, fixture.n, fixture.fifo, DATE);
        String limited = "register-reader-" + UUID.randomUUID();
        authenticate(limited);
        grant(limited, org.ipro.rls.c5.C5PermissionKeys.entity(NomenclatureAccountingObject.class), true, false, false);
        RlsTestFixture.grantC5EntityRead(grants, limited, NomenclatureAccountingObject.class, Workshop.class,
            Nomenclature.class, AccountingType.class);
        var rows = history();
        assertThat(rows).hasSize(1);
        assertThat(typeOn(DATE)).isEqualTo(fixture.fifo.getId());
        assertThat(captions.resolve(rows.stream().map(NomenclatureAccountingObject::getRecorder).toList()))
            .singleElement().satisfies(value -> {
                assertThat(value.label()).isEqualTo(RecorderRefPresentation.UNAVAILABLE_LABEL);
                assertThat(value.href()).isEmpty();
            });
    }

    @Test void documentWriteGrantDoesNotGrantRegisterReplacement() {
        String limited = "document-writer-" + UUID.randomUUID();
        authenticate(limited);
        grant(limited, org.ipro.rls.c5.C5PermissionKeys.entity(AccountingObjectInstallation.class), true, true, true);
        var document = installation(fixture.a, DATE);
        assertThatThrownBy(() -> save(document, List.of(installationLine(fixture.n, fixture.fifo))))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(document.getId()).isNull();
    }

    @Test void documentDeleteGrantDoesNotGrantRegisterCleanupAndFailureKeepsTheAggregate() {
        var installation = install(fixture.a, fixture.n, fixture.fifo, DATE);
        String limited = "document-delete-" + UUID.randomUUID();
        authenticate(limited);
        grant(limited, org.ipro.rls.c5.C5PermissionKeys.entity(AccountingObjectInstallation.class), true, false, true);
        grant(limited, org.ipro.rls.c5.C5PermissionKeys.entity(NomenclatureAccountingObject.class), true, false, false);
        RlsTestFixture.grantC5EntityRead(grants, limited, AccountingObjectInstallation.class,
            NomenclatureAccountingObject.class, Workshop.class, Nomenclature.class, AccountingType.class);
        assertThatThrownBy(() -> access.delete(AccountingObjectInstallation.class, installation.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(access.detail(AccountingObjectInstallation.class, installation.getId())).isPresent();
        assertThat(history()).hasSize(1);
        Long remainingItems = new TransactionTemplate(transactionManager).execute(status -> {
            filters.ensureRlsEnabled(entityManager);
            return entityManager.createQuery("select count(i) from AccountingObjectInstallationItem i where i.document.id = :id",
                Long.class).setParameter("id", installation.getId()).getSingleResult();
        });
        assertThat(remainingItems).isEqualTo(1L);
    }

    @Test void twoConcurrentFirstInstallationsHaveExactlyOneWinner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<Throwable> attempt = () -> {
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));
                authenticate(username); ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                try { install(fixture.a, fixture.n, fixture.fifo, DATE); return null; }
                catch (Throwable failure) { return failure; }
                finally { SecurityContextHolder.clearContext();
                    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
            };
            Future<Throwable> first = workers.submit(attempt), second = workers.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<Throwable> failures = new ArrayList<>();
            Throwable a = first.get(15, TimeUnit.SECONDS), b = second.get(15, TimeUnit.SECONDS);
            if (a != null) failures.add(a); if (b != null) failures.add(b);
            assertThat(failures).singleElement().isInstanceOf(ValidationException.class);
            assertThat(history()).hasSize(1);
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    private List<NomenclatureAccountingObject> history() { return reader.history(fixture.a, PageRequest.of(0, 50)).getContent(); }

    @Test void keyChangeKeepsRecorderAndTechnicalFieldsAndDoesNotNeedDocumentRights() {
        var document = install(fixture.a, fixture.n, fixture.fifo, DATE);
        var row = history().getFirst();
        Long ownerVersion = document.getVersion();
        Long clock = clockOrder();
        String limited = "key-editor-" + UUID.randomUUID();
        authenticate(limited);
        grant(limited, org.ipro.rls.c5.C5PermissionKeys.entity(NomenclatureAccountingObject.class), true, true, false);
        RlsTestFixture.grantC5EntityRead(grants, limited, NomenclatureAccountingObject.class,
            Workshop.class, Nomenclature.class, AccountingType.class);
        RlsTestFixture.grantC5Action(grants, limited,
            RegisterKeyChangePermissions.permissionId(NomenclatureAccountingObject.class));
        assertThat(keyPermissions.canChangeKey(NomenclatureAccountingObject.class, row)).isTrue();
        LocalDateTime period = DATE.minusDays(30).withNano(123456789);
        var changed = keyChanges.change(NomenclatureAccountingObject.class, row.getId(), row.getVersion(),
            Map.of("workshop", fixture.b.getId(), "nomenclature", fixture.n2.getId()), period);
        assertThat(changed.getId()).isEqualTo(row.getId());
        assertThat(changed.getRecorder()).isEqualTo(row.getRecorder());
        assertThat(changed.getLineNumber()).isEqualTo(row.getLineNumber());
        assertThat(changed.getRecordingOrder()).isEqualTo(row.getRecordingOrder());
        assertThat(changed.getAccountingType().getId()).isEqualTo(fixture.fifo.getId());
        assertThat(changed.getPeriod()).isEqualTo(period.withNano(123000000));
        assertThat(changed.getVersion()).isEqualTo(row.getVersion() + 1);
        assertThat(jdbc.queryForObject("select payload from entity_change_log where entity = ? and entity_id = ?",
            String.class, "NomenclatureAccountingObject", row.getId().toString()))
            .contains("change-register-key:NomenclatureAccountingObject", "workshop", "nomenclature", "period");
        assertThat(history()).isEmpty();
        assertThat(clockOrder()).isEqualTo(clock);
        // Owner is read only after restoring the subject that can read it.
        authenticate(username);
        var owner = access.detail(AccountingObjectInstallation.class, document.getId()).orElseThrow();
        assertThat(owner.getVersion()).isEqualTo(ownerVersion);
        assertThat(owner.getDate()).isEqualTo(DATE);
        assertThat(owner.getWorkshop().getId()).isEqualTo(fixture.a.getId());
        var persisted = access.detail(NomenclatureAccountingObject.class, row.getId()).orElseThrow();
        assertThat(persisted.getWorkshop().getId()).isEqualTo(fixture.b.getId());
        assertThat(persisted.getNomenclature().getId()).isEqualTo(fixture.n2.getId());
    }

    @Test void subordinateKeyChangeAllowsCoincidentDimensionGroupsWithoutUpsert() {
        var document = installation(fixture.a, DATE);
        save(document, List.of(installationLine(fixture.n, fixture.fifo), installationLine(fixture.n2, fixture.lifo)));
        var rows = history();
        var target = rows.stream().filter(row -> row.getNomenclature().getId().equals(fixture.n2.getId())).findFirst().orElseThrow();
        keyChanges.change(NomenclatureAccountingObject.class, target.getId(), target.getVersion(),
            Map.of("workshop", fixture.a.getId(), "nomenclature", fixture.n.getId()), DATE);
        assertThat(history()).hasSize(2).extracting(NomenclatureAccountingObject::getId)
            .containsExactlyInAnyOrderElementsOf(rows.stream().map(NomenclatureAccountingObject::getId).toList());
    }

    @Test void actionAloneDoesNotGrantRegisterDataModification() {
        install(fixture.a, fixture.n, fixture.fifo, DATE);
        var row = history().getFirst();
        String limited = "key-only-" + UUID.randomUUID(); authenticate(limited);
        grant(limited, org.ipro.rls.c5.C5PermissionKeys.entity(NomenclatureAccountingObject.class), true, false, false);
        RlsTestFixture.grantC5EntityRead(grants, limited, NomenclatureAccountingObject.class,
            Workshop.class, Nomenclature.class, AccountingType.class);
        RlsTestFixture.grantC5Action(grants, limited,
            RegisterKeyChangePermissions.permissionId(NomenclatureAccountingObject.class));
        assertThat(keyPermissions.canChangeKey(NomenclatureAccountingObject.class, row)).isFalse();
        assertThatThrownBy(() -> keyChanges.change(NomenclatureAccountingObject.class, row.getId(), row.getVersion(),
            Map.of("workshop", fixture.a.getId(), "nomenclature", fixture.n.getId()), DATE.plusDays(1)))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(history().getFirst().getPeriod()).isEqualTo(DATE);
    }

    @Test void registerWriterWithoutNamedActionCannotChangeKey() {
        install(fixture.a, fixture.n, fixture.fifo, DATE); var row = history().getFirst();
        String limited = "register-writer-" + UUID.randomUUID(); authenticate(limited);
        grant(limited, org.ipro.rls.c5.C5PermissionKeys.entity(NomenclatureAccountingObject.class), true, true, false);
        RlsTestFixture.grantC5EntityRead(grants, limited, NomenclatureAccountingObject.class,
            Workshop.class, Nomenclature.class, AccountingType.class);
        assertThatThrownBy(() -> keyChanges.change(NomenclatureAccountingObject.class, row.getId(), row.getVersion(),
            Map.of("workshop", fixture.a.getId(), "nomenclature", fixture.n.getId()), DATE.plusDays(1)))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(history().getFirst().getPeriod()).isEqualTo(DATE);
    }

    @Test void staleRegisterVersionIsRejectedAndKeyCommandFailureRollsBackOuterChanges() {
        var document = install(fixture.a, fixture.n, fixture.fifo, DATE);
        var row = history().getFirst();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var owner = access.detail(AccountingObjectInstallation.class, document.getId()).orElseThrow();
            permissions.requireEntity(SecurityContextHolder.getContext().getAuthentication().getName(),
                AccountingObjectInstallation.class, org.ipro.rls.c5.C5Operation.UPDATE);
            policies.requireUpdate(owner);
            // Explicit fixture authorization isolates transaction rollback from the flush guard.
            policies.recordCanonicalWrite(owner);
            owner.setNumber("must-rollback");
            try {
                keyChanges.change(NomenclatureAccountingObject.class, row.getId(), row.getVersion() + 1,
                    Map.of("workshop", fixture.a.getId(), "nomenclature", fixture.n.getId()), DATE.plusDays(1));
            } catch (jakarta.persistence.OptimisticLockException expected) { }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(access.detail(AccountingObjectInstallation.class, document.getId()).orElseThrow().getNumber())
            .isEqualTo(document.getNumber());
        assertThat(history().getFirst().getPeriod()).isEqualTo(DATE);
    }

    @Test void genericManagedRecorderRowCannotUseTheNamedActionGrantAtFlush() {
        install(fixture.a, fixture.n, fixture.fifo, DATE); var row = history().getFirst();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var managed = entityManager.find(NomenclatureAccountingObject.class, row.getId());
            managed.setPeriod(DATE.plusDays(1)); entityManager.flush();
        })).hasMessageContaining("RECORDER_SET_WRITE_REQUIRED");
        assertThat(history().getFirst().getPeriod()).isEqualTo(DATE);
    }

    @Test void outerRollbackRemovesBothKeyChangeAndDurableAudit() {
        var document = install(fixture.a, fixture.n, fixture.fifo, DATE); var row = history().getFirst();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            keyChanges.change(NomenclatureAccountingObject.class, row.getId(), row.getVersion(),
                Map.of("workshop", fixture.a.getId(), "nomenclature", fixture.n.getId()), DATE.plusDays(1));
            throw new ValidationException("rollback-after-key");
        })).hasMessageContaining("rollback-after-key");
        assertThat(history().getFirst().getPeriod()).isEqualTo(DATE);
        assertThat(history().getFirst().getVersion()).isEqualTo(row.getVersion());
        assertThat(access.detail(AccountingObjectInstallation.class, document.getId()).orElseThrow().getVersion())
            .isEqualTo(document.getVersion());
        assertThat(jdbc.queryForObject("select count(*) from entity_change_log where entity = ? and entity_id = ?",
            Long.class, "NomenclatureAccountingObject", row.getId().toString())).isZero();
    }

    @Test void keyCommandDoesNotAuthorizeAnImmutableNonRegisterType() {
        assertThatThrownBy(() -> keyChanges.change(AttributeValue.class, 1L, 0L, Map.of(), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("не является managed-регистром");
    }

    /**
     * Р8, приёмка v1.2: решение UI о смене ключа и решение команды совпадают у одного субъекта на
     * одной строке — иначе действие обещает то, что сервис отклонит, или скрывает доступное.
     * Oracle — сам вызов команды: разрешение проверяется применением правила, а не его копией.
     */
    @Test void uiKeyChangeDecisionMatchesTheCommandDecisionForTheSameSubject() {
        install(fixture.a, fixture.n, fixture.fifo, DATE);
        var row = history().getFirst();
        List<Subject> subjects = List.of(
            new Subject("нет прав", false, false, false),
            new Subject("только чтение", true, false, false),
            new Subject("чтение и запись без операции", true, true, false),
            new Subject("чтение, запись и операция", true, true, true),
            new Subject("операция без данных", false, false, true));
        for (Subject subject : subjects) {
            String actor = "parity-" + UUID.randomUUID();
            authenticate(actor);
            if (subject.read() || subject.update()) {
                grant(actor, org.ipro.rls.c5.C5PermissionKeys.entity(NomenclatureAccountingObject.class), subject.read(), subject.update(), false);
            }
            if (subject.read()) {
                RlsTestFixture.grantC5EntityRead(grants, actor, NomenclatureAccountingObject.class,
                    Workshop.class, Nomenclature.class, AccountingType.class);
            }
            if (subject.action()) {
                RlsTestFixture.grantC5Action(grants, actor,
                    RegisterKeyChangePermissions.permissionId(NomenclatureAccountingObject.class));
            }

            boolean ui = keyPermissions.canChangeKey(NomenclatureAccountingObject.class, row);

            assertThat(ui).as("UI и команда расходятся у субъекта «%s»", subject.name())
                .isEqualTo(commandAllowsKeyChange(row));
            assertThat(ui).as("без полного набора прав действие не предлагается: «%s»", subject.name())
                .isEqualTo(subject.read() && subject.update() && subject.action());
        }
    }

    /** Намеренно нормализованный no-op: проверяется решение, а не запись. */
    private boolean commandAllowsKeyChange(NomenclatureAccountingObject row) {
        try {
            keyChanges.change(NomenclatureAccountingObject.class, row.getId(), row.getVersion(),
                Map.of("workshop", row.getWorkshop().getId(), "nomenclature", row.getNomenclature().getId()),
                row.getPeriod());
            return true;
        } catch (RuntimeException refused) {
            return false;
        }
    }

    private record Subject(String name, boolean read, boolean update, boolean action) { }

    private Long clockOrder() {
        return new TransactionTemplate(transactionManager).execute(status -> entityManager.find(
            org.ipro.register.persistence.RecorderOrderClock.class,
            org.ipro.register.persistence.RecorderOrderClock.keyFor(NomenclatureAccountingObject.class)).getLastOrder());
    }
    private Long typeOn(LocalDateTime date) {
        return reader.latest(fixture.a, date, PageRequest.of(0, 50)).stream()
            .filter(row -> row.getNomenclature().getId().equals(fixture.n.getId())).findFirst().orElseThrow()
            .getAccountingType().getId();
    }
    private AccountingObjectInstallation installation(Workshop workshop, LocalDateTime date) {
        var document = new AccountingObjectInstallation();
        document.setNumber("I-" + UUID.randomUUID().toString().substring(0, 12));
        document.setWorkshop(workshop); document.setDate(date); return document;
    }
    private AccountingObjectInstallationItem installationLine(Nomenclature nomenclature, AccountingType type) {
        var line = new AccountingObjectInstallationItem(); line.setNomenclature(nomenclature); line.setAccountingType(type); return line;
    }
    private AccountingObjectInstallation install(Workshop workshop, Nomenclature nomenclature, AccountingType type, LocalDateTime date) {
        return save(installation(workshop, date), List.of(installationLine(nomenclature, type)));
    }
    private AccountingObjectInstallation save(AccountingObjectInstallation document, List<AccountingObjectInstallationItem> rows) {
        return commands.save(document, List.of(SectionInput.attached(AccountingObjectInstallationItem.class, rows)),
            EventSource.SYSTEM).aggregate();
    }
    private AccountingObjectCorrection correct(Workshop workshop, Nomenclature nomenclature, AccountingType type, LocalDateTime date) {
        var document = new AccountingObjectCorrection(); document.setNumber("C-" + UUID.randomUUID().toString().substring(0, 12));
        document.setWorkshop(workshop); document.setDate(date);
        var line = new AccountingObjectCorrectionItem(); line.setNomenclature(nomenclature); line.setAccountingType(type);
        return commands.save(document, List.of(SectionInput.attached(AccountingObjectCorrectionItem.class, List.of(line))),
            EventSource.SYSTEM).aggregate();
    }
    private List<AccountingObjectCorrectionItem> correctionRows(AccountingObjectCorrection document) {
        return new TransactionTemplate(transactionManager).execute(status ->
            entityManager.createQuery("select i from AccountingObjectCorrectionItem i join fetch i.currentAccountingType join fetch i.accountingType where i.document.id = :id",
                AccountingObjectCorrectionItem.class).setParameter("id", document.getId()).getResultList());
    }
    private void grant(String actor, String dimension, boolean read, boolean update, boolean delete) {
        AccessGrant value = grants.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, actor, dimension).stream().findFirst().orElseGet(AccessGrant::new);
        value.setSubjectType(AccessGrant.SubjectType.USER);
        value.setSubjectKey(actor); value.setDimension(dimension);
        value.setCanRead(read); value.setCanUpdate(update); value.setCanDelete(delete); grants.saveAndFlush(value);
    }
    private static void authenticate(String actor) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(actor, "n/a", List.of()));
        SecurityContextHolder.setContext(context);
    }
    private record Fixture(Workshop a, Workshop b, Nomenclature n, Nomenclature n2,
        AccountingType fifo, AccountingType lifo, AccountingType ske) {}
}


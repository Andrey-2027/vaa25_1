package org.ip.application.register;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.application.form.ItemFormSaveDispatcher;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ip.model.UnitOfMeasurement;
import org.ipro.data.EntityDataAccess;
import org.ipro.form.FormSaveResult;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.registry.FormResolver;
import org.ipro.rls.AccessGrant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Настоящие generic Vaadin bindings, default dispatcher, транзакции и H2; без внешней test-транзакции. */
@SpringBootTest(classes = Application.class)
class NomenclaturePriceFormTest {
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 1, 0, 0);
    @MockitoBean private DataInitializer dataInitializer;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FormResolver resolver;
    @Autowired private ItemFormSaveDispatcher dispatcher;
    @Autowired private EntityDataAccess access;

    private String username;
    private UnitOfMeasurement unit;
    private Nomenclature nomenclature;
    private Nomenclature otherNomenclature;

    @BeforeEach
    void setUp() {
        username = "rf" + UUID.randomUUID().toString().substring(0, 8);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (Class<?> type : List.of(NomenclaturePrice.class, Nomenclature.class, UnitOfMeasurement.class)) {
                AccessGrant grant = new AccessGrant();
                grant.setSubjectType(AccessGrant.SubjectType.USER);
                grant.setSubjectKey(username);
                grant.setDimension("ENTITY:" + type.getName());
                grant.setCanRead(true);
                entityManager.persist(grant);
                // ADR-0017: право на операцию — отдельная глобальная строка; запись цены
                // идёт через canonical/register path и без неё отказала бы.
                AccessGrant operations = new AccessGrant();
                operations.setSubjectType(AccessGrant.SubjectType.USER);
                operations.setSubjectKey(username);
                operations.setDimension(org.ipro.rls.c5.C5PermissionKeys.entityOperations(type));
                operations.setCanUpdate(true);
                operations.setCanDelete(true);
                entityManager.persist(operations);
            }
            unit = new UnitOfMeasurement(username, "Тестовая единица", username);
            entityManager.persist(unit);
            nomenclature = new Nomenclature(username, "Тестовая номенклатура", unit);
            otherNomenclature = new Nomenclature(username + "-2", "Другая номенклатура", unit);
            entityManager.persist(nomenclature);
            entityManager.persist(otherNomenclature);
        });
    }

    @AfterEach
    void cleanUp() {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                if (nomenclature != null && nomenclature.getId() != null) {
                    entityManager.createQuery("delete from NomenclaturePrice p where p.nomenclature.id in :ids")
                        .setParameter("ids", List.of(nomenclature.getId(), otherNomenclature.getId())).executeUpdate();
                    entityManager.remove(entityManager.find(Nomenclature.class, nomenclature.getId()));
                    entityManager.remove(entityManager.find(Nomenclature.class, otherNomenclature.getId()));
                    entityManager.remove(entityManager.find(UnitOfMeasurement.class, unit.getId()));
                }
                entityManager.createQuery("delete from AccessGrant g where g.subjectKey = :user")
                    .setParameter("user", username).executeUpdate();
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void genericFormCreatesHistoryAndUpdatesTheResourceAfterReopening() {
        NomenclaturePrice first = saved(newForm(DAY, "100"));
        saved(newForm(DAY.plusDays(1), "150"));

        var history = access.list(NomenclaturePrice.class,
            (root, query, cb) -> cb.equal(root.get("nomenclature"), nomenclature),
            PageRequest.of(0, 10, Sort.by("period")));
        assertThat(history.getTotalElements()).isEqualTo(2);
        assertThat(history.getContent()).extracting(NomenclaturePrice::getPeriod)
            .containsExactly(DAY, DAY.plusDays(1));

        ItemForm<NomenclaturePrice> reopened = editForm(first.getId());
        assertThat(reopened.getEntityField("period", LocalDateTime.class).getValue()).isEqualTo(DAY);
        reopened.getEntityField("price", BigDecimal.class).setValue(new BigDecimal("90.1234"));
        saved(reopened);

        NomenclaturePrice stored = access.detail(NomenclaturePrice.class, first.getId()).orElseThrow();
        assertThat(stored.getPrice()).isEqualByComparingTo("90.1234");
        assertThat(stored.getPeriod()).isEqualTo(DAY);
        assertThat(stored.getNomenclature().getId()).isEqualTo(nomenclature.getId());
    }

    @Test
    void duplicateKeyReturnsAFriendlyFailureAndDoesNotCommitAnotherRow() {
        saved(newForm(DAY, "100"));
        FormSaveResult<NomenclaturePrice> result = newForm(DAY, "80").save();

        assertThat(result).isInstanceOf(FormSaveResult.Failure.class);
        assertThat(((FormSaveResult.Failure<?>) result).messages()).singleElement().asString()
            .contains("уже существует").doesNotContain("insert into", "23505");
        var history = access.list(NomenclaturePrice.class,
            (root, query, cb) -> cb.equal(root.get("nomenclature"), nomenclature), PageRequest.of(0, 10));
        assertThat(history.getTotalElements()).isEqualTo(1);
        assertThat(history.getContent().getFirst().getPrice()).isEqualByComparingTo("100");
    }

    @Test
    void periodIsLockedAndProgrammaticInputDoesNotChangeTheStoredKey() {
        NomenclaturePrice first = saved(newForm(DAY, "100"));
        ItemForm<NomenclaturePrice> form = editForm(first.getId());
        assertThat(form.getEntityField("period", LocalDateTime.class).isReadOnly()).isTrue();
        form.setReadOnly(false);
        assertThat(form.getEntityField("period", LocalDateTime.class).isReadOnly()).isTrue();
        form.getEntityField("period", LocalDateTime.class).setValue(DAY.plusDays(1));

        FormSaveResult<NomenclaturePrice> result = form.save();

        assertThat(result).isInstanceOf(FormSaveResult.Success.class);
        assertThat(access.detail(NomenclaturePrice.class, first.getId()).orElseThrow().getPeriod()).isEqualTo(DAY);
    }

    @Test
    void nomenclatureIsLockedAndProgrammaticInputDoesNotChangeTheStoredKey() {
        NomenclaturePrice first = saved(newForm(DAY, "100"));
        ItemForm<NomenclaturePrice> form = editForm(first.getId());
        assertThat(form.<Nomenclature>entityField("nomenclature").isReadOnly()).isTrue();
        form.<Nomenclature>entityField("nomenclature").setValue(otherNomenclature);

        FormSaveResult<NomenclaturePrice> result = form.save();

        assertThat(result).isInstanceOf(FormSaveResult.Success.class);
        assertThat(access.detail(NomenclaturePrice.class, first.getId()).orElseThrow().getNomenclature().getId())
            .isEqualTo(nomenclature.getId());
    }

    private ItemForm<NomenclaturePrice> newForm(LocalDateTime day, String price) {
        ItemForm<NomenclaturePrice> form = form();
        form.initializeNewEntity(Map.of("nomenclature", nomenclature, "period", day,
            "price", new BigDecimal(price)));
        return form;
    }

    private ItemForm<NomenclaturePrice> editForm(Long id) {
        ItemForm<NomenclaturePrice> form = form();
        form.setEntity(access.detail(NomenclaturePrice.class, id).orElseThrow());
        return form;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ItemForm<NomenclaturePrice> form() {
        ItemForm<NomenclaturePrice> form = resolver.resolveItemForm(NomenclaturePrice.class, null, null, null);
        form.setSaveHandler(value -> (FormSaveResult) dispatcher.save((ItemForm) value));
        return form;
    }

    private static NomenclaturePrice saved(ItemForm<NomenclaturePrice> form) {
        FormSaveResult<NomenclaturePrice> result = form.save();
        assertThat(result).isInstanceOf(FormSaveResult.Success.class);
        return ((FormSaveResult.Success<NomenclaturePrice>) result).saved();
    }

    @Test void standardListUsesWinnerThenResourceAndPeriodFiltersAndRestoresState() {
        saved(newForm(DAY, "100"));
        NomenclaturePrice latest = saved(newForm(DAY.plusDays(1), "50"));
        var form = resolver.<NomenclaturePrice, Long>resolveListForm(NomenclaturePrice.class, null, null);
        form.setOpeningContextFilters(Map.of("nomenclature", nomenclature));
        assertThat(count(form)).isEqualTo(2);
        var controls = descendants(form.getFilterPanel()).toList();
        var mode = (com.vaadin.flow.component.combobox.ComboBox<String>) controls.stream()
            .filter(component -> component instanceof com.vaadin.flow.component.combobox.ComboBox).findFirst().orElseThrow();
        var date = (com.vaadin.flow.component.datetimepicker.DateTimePicker) controls.stream()
            .filter(component -> component instanceof com.vaadin.flow.component.datetimepicker.DateTimePicker).findFirst().orElseThrow();
        var period = (org.ipro.filtergrid.PeriodField<LocalDateTime>) controls.stream()
            .filter(component -> component instanceof org.ipro.filtergrid.PeriodField).findFirst().orElseThrow();
        period.getFromField().setValue(DAY); period.getToField().setValue(DAY);
        assertThat(count(form)).isEqualTo(2); // unapplied draft
        assertThat(period.applySelection()).isTrue();
        assertThat(count(form)).isEqualTo(1);
        date.setValue(DAY.plusDays(3)); mode.setValue("Срез последних");
        assertThat(period.getParent().orElseThrow().isVisible()).isFalse();
        assertThat(date.getParent().orElseThrow().isVisible()).isTrue();
        assertThat(count(form)).isEqualTo(1);
        assertThat(rows(form)).extracting(NomenclaturePrice::getId).containsExactly(latest.getId());
        form.setUserFilter(org.ipro.filtergrid.filter.FilterConditionNode.of(new org.ipro.filtergrid.filter.FilterCondition(
            "price", org.ipro.filtergrid.filter.FilterOperator.GE, "90", null, org.ipro.filtergrid.filter.FilterDataType.NUMBER)));
        assertThat(count(form)).isZero();
        form.setUserFilter(org.ipro.filtergrid.filter.FilterConditionNode.of(new org.ipro.filtergrid.filter.FilterCondition(
            "period", org.ipro.filtergrid.filter.FilterOperator.LE, DAY.toString(), null, org.ipro.filtergrid.filter.FilterDataType.DATE)));
        assertThat(count(form)).isZero();
        form.clearVisualFilter();
        String state = form.captureViewState().toJson();
        var reopened = resolver.<NomenclaturePrice, Long>resolveListForm(NomenclaturePrice.class, null, Map.of("listState", state));
        reopened.applyOpeningViewState();
        assertThat(reopened.captureViewState().options()).containsEntry("register.mode", "Срез последних")
            .containsEntry("register.context.nomenclature", nomenclature.getId().toString());
        assertThat(count(reopened)).isEqualTo(1);
        assertThat(rows(reopened)).extracting(NomenclaturePrice::getId).containsExactly(latest.getId());
        assertThat(descendants(reopened.getFilterPanel()).filter(org.ipro.filtergrid.PeriodField.class::isInstance)
            .map(component -> (org.ipro.filtergrid.PeriodField<?>) component).findFirst().orElseThrow().getValue().isEmpty()).isTrue();
    }

    @Test void keyLocksAfterFirstSaveWhileResourceRemainsEditable() {
        ItemForm<NomenclaturePrice> form = newForm(DAY, "100");
        assertThat(form.getEntityField("period", LocalDateTime.class).isReadOnly()).isFalse();
        NomenclaturePrice row = saved(form);
        form.applyPersistedEntity(row);
        assertThat(form.getEntityField("period", LocalDateTime.class).isReadOnly()).isTrue();
        assertThat(form.<Nomenclature>entityField("nomenclature").isReadOnly()).isTrue();
        assertThat(form.getEntityField("price", BigDecimal.class).isReadOnly()).isFalse();
    }

    private static int count(org.ipro.form.builtin.ListForm<?, ?> form) {
        return form.getGrid().getDataProvider().size(new com.vaadin.flow.data.provider.Query<>());
    }
    private static java.util.stream.Stream<com.vaadin.flow.component.Component> descendants(com.vaadin.flow.component.Component parent) {
        return parent.getChildren().flatMap(child -> java.util.stream.Stream.concat(java.util.stream.Stream.of(child), descendants(child)));
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<NomenclaturePrice> rows(org.ipro.form.builtin.ListForm<NomenclaturePrice, ?> form) {
        return (List) form.getGrid().getDataProvider().fetch(new com.vaadin.flow.data.provider.Query(0, 50, List.of(), null, null)).toList();
    }
}

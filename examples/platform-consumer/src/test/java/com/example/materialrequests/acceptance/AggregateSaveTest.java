package com.example.materialrequests.acceptance;

import com.example.materialrequests.application.RequestCommitted;
import com.example.materialrequests.model.*;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.server.VaadinSession;
import org.ipro.data.EntityDataAccess;
import org.ipro.form.FormSaveResult;
import org.ipro.form.MetadataDrivenItemFormSaveAdapter;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.lifecycle.EntitySaveContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"consumer.demo-password=bootstrap-test-password",
        "spring.datasource.url=${CONSUMER_TEST_AGGREGATE_JDBC_URL:jdbc:h2:mem:platform_consumer_p3;DB_CLOSE_DELAY=-1}"})
@ActiveProfiles(resolver = ConsumerTestProfiles.class)
@Import(AggregateSaveTest.ProbeConfiguration.class)
class AggregateSaveTest {
    @Autowired ApplicationContext context;
    @Autowired EntityDataAccess access;
    @Autowired MetadataDrivenItemFormSaveAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CommitProbe commits;
    @Autowired LateFailure fault;
    private UI ui;
    private FormsTest.TestSession session;

    @BeforeEach void setup() {
        session = new FormsTest.TestSession(); session.getLockInstance().lock();
        VaadinSession.setCurrent(session);
        ui = new UI(); ui.getInternals().setSession(session); UI.setCurrent(ui);
        ProtectedReadTest.login("alice"); commits.facts.clear(); fault.enabled = false;
        fault.observedHeaders = 0; fault.observedRows = 0;
    }
    @AfterEach void cleanup() {
        fault.enabled = false;
        UI.setCurrent(null); VaadinSession.setCurrent(null);
        try { session.getService().destroy(); }
        finally { session.getLockInstance().unlock(); }
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test void materialCrudValidationAndSharedUnicodeLookup() {
        var form = open(Material.class, null);
        text(form, "code", "P3-MAT"); text(form, "name", "Кабель ёж — Ø25");
        assertThat(form.save()).isInstanceOf(FormSaveResult.Success.class);
        Long id = form.getEntity().getId();
        text(form, "name", "Кабель обновлён");
        assertThat(form.save().success()).isTrue();
        var duplicate = open(Material.class, null);
        text(duplicate, "code", "P3-MAT"); text(duplicate, "name", "Дубликат");
        var outcome = duplicate.save();
        assertThat(outcome).isInstanceOf(FormSaveResult.Failure.class);
        assertThat(((FormSaveResult.Failure<?>) outcome).messages().toString()).contains("уже существует");
        text(duplicate, "code", "P3-INVALID"); text(duplicate, "name", "");
        assertThat(duplicate.save().success()).isFalse();
        assertThatThrownBy(() -> access.create(Material.class, new Material("P3-INVALID", "")))
                .hasMessageContaining("name");
        assertThat(observe(() -> jdbc.queryForObject(
                "select count(*) from consumer_material where code in ('P3-MAT','P3-INVALID')", Long.class)))
                .isEqualTo(1L);
        ProtectedReadTest.login("bob");
        assertThat(access.lookup(Material.class, "обновлён", 10)).singleElement()
                .extracting(Material::getName).isEqualTo("Кабель обновлён");
        ProtectedReadTest.login("alice"); access.delete(Material.class, id);
    }

    @Test void filledAggregateReopensWithNumbersLinksAndSingleAfterCommit() {
        var form = draft("P3-create");
        addRow(form, "MAT-001", "2.500"); addRow(form, "MAT-002", "1.000");
        assertThat(form.getEntity().getNumber()).isNullOrEmpty();
        assertThat(form.getEntity().getDepartmentId()).isEqualTo(1L);
        assertThat(form.save()).isInstanceOf(FormSaveResult.Success.class);
        var saved = form.getEntity();
        assertThat(saved.getNumber()).matches("ЗМ-\\d{6}");
        assertThat(saved.getVersion()).isNotNull();
        assertThat(commits.facts).containsExactly(new RequestCommitted(saved.getId(), saved.getNumber(), RequestStatus.DRAFT));
        assertThat(rowCount(saved.getId())).isEqualTo(2);
        assertThat(observe(() -> jdbc.queryForList(
                "select line_number from consumer_material_request_line where request_id=? order by line_number",
                Integer.class, saved.getId()))).containsExactly(1, 2);
        var reopened = open(MaterialRequest.class, saved.getId());
        var rows = reopened.tableSection(MaterialRequestLine.class).getRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.getMaterial().getCode()).containsExactly("MAT-001", "MAT-002");
        assertThat(rows).extracting(MaterialRequestLine::getQuantity)
                .containsExactly(new BigDecimal("2.500"), new BigDecimal("1.000"));
        assertThat(reopened.getEntity().getNumber()).isEqualTo(saved.getNumber());
    }

    @Test void emptyAndInvalidRowsAreRejectedOnServerAndSameFormCanRetry() {
        var form = draft("P3-validation");
        assertThat(form.save()).isInstanceOf(FormSaveResult.Failure.class);
        assertThatThrownBy(() -> adapter.save(form)).hasMessageContaining("1");
        addRow(form, "MAT-001", "3");
        var row = form.tableSection(MaterialRequestLine.class).getRows().getFirst();
        row.setQuantity(BigDecimal.ZERO);
        assertThatThrownBy(() -> adapter.save(form)).hasMessageContaining("больше 0");
        assertThat(countByComment("P3-validation")).isZero();
        assertThat(commits.facts).isEmpty();
        assertThat(form.getEntity().getId()).isNull();
        row.setQuantity(new BigDecimal("3"));
        assertThat(form.save().success()).isTrue();
        assertThat(countByComment("P3-validation")).isEqualTo(1);
        assertThat(rowCount(form.getEntity().getId())).isEqualTo(1);
        assertThat(commits.facts).hasSize(1);
    }

    @Test void duplicateMaterialVetoPreservesDraftAndRetryUsesSameForm() {
        var form = draft("P3-duplicate");
        addRow(form, "MAT-001", "1"); addRow(form, "MAT-001", "2");
        var outcome = form.save();
        assertThat(outcome).isInstanceOf(FormSaveResult.Failure.class);
        assertThat(((FormSaveResult.Failure<?>) outcome).messages().toString()).contains("не должен повторяться");
        assertThatThrownBy(() -> adapter.save(form)).hasMessageContaining("не должен повторяться");
        assertThat(countByComment("P3-duplicate")).isZero();
        assertThat(commits.facts).isEmpty();
        assertThat(form.tableSection(MaterialRequestLine.class).getRows()).hasSize(2);
        form.tableSection(MaterialRequestLine.class).getRows().getLast()
                .setMaterial(access.lookup(Material.class, "MAT-002", 10).getFirst());
        assertThat(form.save().success()).isTrue();
        assertThat(rowCount(form.getEntity().getId())).isEqualTo(2);
        assertThat(commits.facts).hasSize(1);
    }

    @Test void lateFailureRollsBackExecutedSqlAndDraftCanBeSavedAgain() {
        var form = draft("P3-late-failure"); addRow(form, "MAT-001", "4");
        fault.enabled = true;
        var outcome = form.save();
        assertThat(outcome).isInstanceOf(FormSaveResult.Failure.class);
        assertThat(((FormSaveResult.Failure<?>) outcome).messages().toString()).contains("P3 late failure");
        assertThat(fault.observedHeaders).isEqualTo(1);
        assertThat(fault.observedRows).isEqualTo(1);
        assertThat(countByComment("P3-late-failure")).isZero();
        assertThat(observe(() -> jdbc.queryForObject(
                "select count(*) from consumer_material_request_line l left join consumer_material_request r on r.id=l.request_id where r.id is null",
                Long.class))).isZero();
        assertThat(commits.facts).isEmpty();
        assertThat(form.getEntity().getId()).isNull();
        assertThat(form.tableSection(MaterialRequestLine.class).getRows().getFirst().getId()).isNull();
        assertThat(form.getEntity().getComment()).isEqualTo("P3-late-failure");
        fault.enabled = false;
        assertThat(form.save().success()).isTrue();
        assertThat(countByComment("P3-late-failure")).isEqualTo(1);
        assertThat(commits.facts).hasSize(1);
    }

    @Test void staleEditorGetsConflictWithoutLosingInputOrOverwritingWinner() {
        var seed = draft("P3-conflict"); addRow(seed, "MAT-001", "1");
        assertThat(seed.save().success()).isTrue();
        Long id = seed.getEntity().getId();
        var first = open(MaterialRequest.class, id); var second = open(MaterialRequest.class, id);
        assertThat(first.getEntity().getVersion()).isEqualTo(second.getEntity().getVersion());
        text(first, "comment", "P3-winner"); text(second, "comment", "P3-loser-input");
        second.tableSection(MaterialRequestLine.class).getRows().getFirst().setQuantity(new BigDecimal("99"));
        commits.facts.clear();
        assertThat(first.save().success()).isTrue();
        assertThat(second.save()).isInstanceOf(FormSaveResult.Conflict.class);
        assertThat(second.getEntity().getComment()).isEqualTo("P3-loser-input");
        assertThat(second.tableSection(MaterialRequestLine.class).getRows().getFirst().getQuantity())
                .isEqualByComparingTo("99");
        assertThat(access.detail(MaterialRequest.class, id).orElseThrow().getComment()).isEqualTo("P3-winner");
        assertThat(observe(() -> jdbc.queryForObject(
                "select quantity from consumer_material_request_line where request_id=?", BigDecimal.class, id)))
                .isEqualByComparingTo("1");
        assertThat(commits.facts).hasSize(1);
    }

    @Test void repeatedSaveKeepsNumberReplacesRowsAndNewDocumentGetsAnotherNumber() {
        var form = draft("P3-repeat"); addRow(form, "MAT-001", "1"); addRow(form, "MAT-002", "2");
        assertThat(form.save().success()).isTrue();
        Long id = form.getEntity().getId(); String number = form.getEntity().getNumber();
        var table = form.tableSection(MaterialRequestLine.class);
        var removed = table.getRows().getLast();
        FormsTest.descendants(table).filter(com.vaadin.flow.component.grid.Grid.class::isInstance)
                .map(component -> (com.vaadin.flow.component.grid.Grid<MaterialRequestLine>) component)
                .findFirst().orElseThrow().select(removed);
        FormsTest.descendants(table).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getText().equals("Удалить")).findFirst().orElseThrow().click();
        form.tableSection(MaterialRequestLine.class).getRows().getFirst().setQuantity(new BigDecimal("5"));
        assertThat(form.save().success()).isTrue();
        assertThat(form.getEntity().getNumber()).isEqualTo(number);
        assertThat(rowCount(id)).isEqualTo(1);
        var another = draft("P3-next"); addRow(another, "MAT-001", "1");
        assertThat(another.save().success()).isTrue();
        assertThat(another.getEntity().getNumber()).isNotEqualTo(number);
        assertThat(commits.facts).hasSize(3);
    }

    @Test void aggregateServerRejectsReaderAnonymousAndForeignFinalDepartment() {
        var form = draft("P3-denied"); addRow(form, "MAT-001", "1");
        ProtectedReadTest.login("bob");
        assertThatThrownBy(() -> adapter.save(form)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        ProtectedReadTest.login("alice");
        form.getEntityField("departmentId", BigDecimal.class).setValue(new BigDecimal("2"));
        assertThatThrownBy(() -> adapter.save(form)).isInstanceOf(org.ipro.rls.RlsAccessDeniedException.class);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> adapter.save(form)).isInstanceOf(RuntimeException.class);
        assertThat(countByComment("P3-denied")).isZero();
        assertThat(commits.facts).isEmpty();
        ProtectedReadTest.login("bob");
        Long own = access.list(MaterialRequest.class, null, org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent().getFirst().getId();
        var visible = open(MaterialRequest.class, own);
        assertThatThrownBy(() -> adapter.save(visible)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> access.delete(MaterialRequest.class, own))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void deletionRemovesOwnedRowsAndReferencedMaterialIsProtected() {
        Material material = access.create(Material.class, new Material("P3-REFERENCED", "Используемый материал"));
        var form = draft("P3-delete"); addRow(form, "P3-REFERENCED", "2");
        assertThat(form.save().success()).isTrue(); Long id = form.getEntity().getId();
        assertThatThrownBy(() -> access.delete(Material.class, material.getId()))
                .hasMessageContaining("на запись есть ссылки");
        assertThat(access.detail(Material.class, material.getId())).isPresent();
        assertThat(rowCount(id)).isEqualTo(1);
        access.delete(MaterialRequest.class, id);
        assertThat(access.detail(MaterialRequest.class, id)).isEmpty();
        assertThat(rowCount(id)).isZero();
        access.delete(Material.class, material.getId());
        assertThat(access.detail(Material.class, material.getId())).isEmpty();
    }

    private ItemForm<MaterialRequest> draft(String comment) {
        var form = open(MaterialRequest.class, null); text(form, "comment", comment); return form;
    }
    @SuppressWarnings("unchecked")
    private <T extends org.ipro.crud.BaseEntity> ItemForm<T> open(Class<T> type, Long id) {
        context.getBean(FormNavigator.class).openItemForm(type, null, id, null, null);
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return (ItemForm<T>) FormsTest.descendants(ui).filter(ItemForm.class::isInstance)
                .map(ItemForm.class::cast).filter(form -> form.getEntityClass().equals(type)).toList().getLast();
    }
    private void text(ItemForm<?> form, String field, String value) {
        form.getEntityField(field, String.class).setValue(value);
    }
    private void addRow(ItemForm<MaterialRequest> form, String code, String quantity) {
        FormsTest.descendants(form.tableSection(MaterialRequestLine.class)).filter(Button.class::isInstance)
                .map(Button.class::cast).filter(button -> button.getText().equals("Добавить"))
                .findFirst().orElseThrow().click();
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        @SuppressWarnings("unchecked")
        ItemForm<MaterialRequestLine> row = (ItemForm<MaterialRequestLine>) FormsTest.descendants(ui)
                .filter(ItemForm.class::isInstance).map(ItemForm.class::cast)
                .filter(candidate -> candidate.getEntityClass().equals(MaterialRequestLine.class)).toList().getLast();
        row.<Material>entityField("material").setValue(access.lookup(Material.class, code, 10).getFirst());
        row.getEntityField("quantity", BigDecimal.class).setValue(new BigDecimal(quantity));
        int before = form.tableSection(MaterialRequestLine.class).getRows().size();
        row.doSave();
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        assertThat(form.tableSection(MaterialRequestLine.class).getRows()).hasSize(before + 1);
    }
    private long countByComment(String comment) {
        return observe(() -> jdbc.queryForObject("select count(*) from consumer_material_request where comment=?", Long.class, comment));
    }
    private long rowCount(Long id) {
        return observe(() -> jdbc.queryForObject("select count(*) from consumer_material_request_line where request_id=?", Long.class, id));
    }
    private <T> T observe(Supplier<T> query) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(true);
        return transaction.execute(ignored -> query.get());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean static org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor replaceLifecycle() {
            return registry -> registry.removeBeanDefinition("materialRequestLifecycle");
        }
        @Bean CommitProbe commitProbe() { return new CommitProbe(); }
        @Bean LateFailure lateFailure(JdbcTemplate jdbc, org.springframework.context.ApplicationEventPublisher events) {
            return new LateFailure(jdbc, events);
        }
    }
    static class CommitProbe {
        final List<RequestCommitted> facts = new ArrayList<>();
        @EventListener public void committed(RequestCommitted fact) { facts.add(fact); }
    }
    /** Test-only failure at the public typed callback; JDBC only observes executed SQL. */
    static class LateFailure extends com.example.materialrequests.application.MaterialRequestLifecycle {
        final JdbcTemplate jdbc;
        boolean enabled;
        long observedHeaders, observedRows;
        LateFailure(JdbcTemplate jdbc, org.springframework.context.ApplicationEventPublisher events) {
            super(events); this.jdbc = jdbc;
        }
        @Override public void onSave(EntitySaveContext<MaterialRequest> context) {
            if (!enabled) return;
            Long id = context.entity().getId();
            observedHeaders = jdbc.queryForObject("select count(*) from consumer_material_request where id=?", Long.class, id);
            observedRows = jdbc.queryForObject("select count(*) from consumer_material_request_line where request_id=?", Long.class, id);
            throw new IllegalStateException("P3 late failure after SQL");
        }
    }
}

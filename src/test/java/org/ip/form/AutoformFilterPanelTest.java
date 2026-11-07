package org.ip.form;

import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.model.Workshop;
import org.ipro.filtergrid.PeriodField;
import org.ipro.filtergrid.PeriodFilter;
import org.ipro.form.EntityField;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.registry.Filter;
import org.ipro.form.registry.FormResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.LocalDate;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** Real configuration/registry/resolver; no database records are changed. */
@SpringBootTest(classes = Application.class)
class AutoformFilterPanelTest {
    @MockitoBean DataInitializer initializer;
    @Autowired FormResolver resolver;

    @Test void receivingPanelGatesOnlyCreatePrefillsEqualityAndKeepsPeriodEphemeral() {
        ListForm<ReceivingDocument, Long> form = resolver.resolveListForm(ReceivingDocument.class, null, null);
        var panel = panel(form);
        assertThat(panel.getFields()).extracting(field -> field.path()).containsExactly("journal", "date", "receivingWorkshop");
        assertThat(form.getAddButton().isEnabled()).isFalse();
        assertThat(form.getContextFilterValues()).isEmpty();
        assertThat(panel.getControl("journal")).isInstanceOf(EntityField.class);
        Journal journal = new Journal(); journal.setCode("AF38"); journal.setName("Проверка");
        Workshop workshop = new Workshop("AF38", "Проверка");
        form.setContextFilter("journal", journal); form.setContextFilter("receivingWorkshop", workshop);
        assertThat(form.getAddButton().isEnabled()).isTrue();
        assertThat(form.getRequiredContextValues()).containsEntry("journal", journal).containsEntry("receivingWorkshop", workshop);
        assertThat(((EntityField<?>) panel.getControl("journal")).getValue()).isSameAs(journal);
        @SuppressWarnings("unchecked") var period = (PeriodField<LocalDate>) panel.getControl("date");
        period.getFromField().setValue(LocalDate.of(2026, 11, 1));
        assertThat(form.getContextFilterValues()).doesNotContainKey("date");
        assertThat(period.applySelection()).isTrue();
        assertThat(form.getRequiredContextValues()).doesNotContainKey("date");
        assertThat((Object) form.getFilterGrid().getColumnFilter("date")).isInstanceOf(PeriodFilter.class);
        var reopened = resolver.resolveListForm(ReceivingDocument.class, null, null);
        assertThat(reopened.getContextFilterValues()).isEmpty();
    }
    @Test void specificationJournalUsesNewDeclarationAndTypedOpeningIsVisibleAndFixed() {
        Journal journal = new Journal(); journal.setCode("AF38"); journal.setName("Контекст");
        ListForm<PrdSpec, Long> form = resolver.resolveListForm(PrdSpec.class, null, null);
        form.setOpeningFilter(Filter.eq("journal", journal));
        var locked = (com.vaadin.flow.component.textfield.TextField) panel(form).getControl("journal");
        assertThat(locked.isReadOnly()).isTrue(); assertThat(locked.getHelperText()).isEqualTo("Задан при открытии");
        form.clearContextFilter();
        assertThat(form.getRequiredContextValues()).containsExactlyEntriesOf(Map.of("journal", journal));
        assertThat(Filter.values(PrdSpec.class, Filter.eq("journal", journal))).containsEntry("journal", journal);
        assertThatThrownBy(() -> Filter.values(PrdSpec.class, Filter.eq("journal", "bad"))).hasMessageContaining("journal");
    }
    private static org.ipro.form.builder.ContextFilterPanel panel(ListForm<?, ?> form) {
        return (org.ipro.form.builder.ContextFilterPanel) form.getFilterPanel().getChildren()
            .filter(org.ipro.form.builder.ContextFilterPanel.class::isInstance).findFirst().orElseThrow();
    }
}

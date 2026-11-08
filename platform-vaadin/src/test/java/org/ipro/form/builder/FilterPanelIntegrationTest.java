package org.ipro.form.builder;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.crud.BaseEntity;
import org.ipro.filtergrid.PeriodField;
import org.ipro.filtergrid.PeriodFilter;
import org.ipro.filtergrid.PeriodRange;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FilterPanelIntegrationTest {
    @EntityMetadata static class Row extends BaseEntity {
        @FieldMetadata(label = "Текст") String name;
        @FieldMetadata(label = "Дата") LocalDate date;
        @FieldMetadata(label = "Активен") Boolean active;
        @FieldMetadata(label = "Состояние") State state;
        @FieldMetadata(label = "Количество") Integer quantity;
    }
    enum State { OPEN, CLOSED }
    private final MetadataResolver resolver = new MetadataResolver();
    private ListForm<Row, Long> form() {
        var meta = resolver.resolve(Row.class);
        return new ListForm<>(meta, new org.ipro.filtergrid.jpa.JpaFilterGrid<>(Row.class,
            (spec, pageable) -> org.springframework.data.domain.Page.empty(pageable)));
    }
    @Test void declarationDerivesLabelsEditorsAndSelectionPanelAndRejectsInvalidPathsAtRegistration() {
        var registry = new FormRegistry();
        ListFormCustomization declaration = new ListFormCustomization() {
            public Class<?> entityClass() { return Row.class; }
            public void configure(ListFormVariants l) {
                l.filterPanel(l.filter("name"), l.horizontal("dates", l.filter("date").setPeriod(), l.filter("active")), l.filter("state"));
            }
        };
        new ListFormCustomizationRegistrar(registry, List.of(declaration)).afterPropertiesSet();
        var fields = registry.getContextFilters(Row.class);
        assertThat(fields).extracting(f -> f.labelFor(Row.class)).containsExactly("Текст", "Дата", "Активен", "Состояние");
        assertThat(SelectionContextFilters.resolveRow(registry, Row.class, null)).isEqualTo(fields);
        var panel = new ContextFilterPanel(Row.class, fields, resolver, null, null, (path, value) -> {});
        assertThat(panel.getControl("date")).isInstanceOf(PeriodField.class);
        assertThat(panel.getControl("active")).isInstanceOf(ComboBox.class);
        assertThat(panel.getControl("state")).isInstanceOf(ComboBox.class);
        assertThat(panel.getChildren().count()).isEqualTo(3);
        ListFormCustomization invalid = new ListFormCustomization() {
            public Class<?> entityClass() { return Row.class; }
            public void configure(ListFormVariants l) { l.filterPanel(l.filter("missing")); }
        };
        assertThatThrownBy(() -> new ListFormCustomizationRegistrar(new FormRegistry(), List.of(invalid)).afterPropertiesSet())
            .hasMessageContaining("missing").hasMessageContaining(invalid.getClass().getName());
    }
    @Test void periodCommitsOnlyOnApplyAndFixedOpeningWinsAndCannotBeEdited() {
        var form = form();
        form.setContextFilters(List.of(ContextFilterField.filter("date").setPeriod(), ContextFilterField.filter("active")));
        var panel = (ContextFilterPanel) form.getFilterPanel().getChildren().filter(ContextFilterPanel.class::isInstance).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") var period = (PeriodField<LocalDate>) panel.getControl("date");
        period.getFromField().setValue(LocalDate.of(2026, 11, 1));
        period.getToField().setValue(LocalDate.of(2026, 11, 30));
        assertThat(form.getContextFilterValues()).isEmpty();
        assertThat(period.applySelection()).isTrue();
        assertThat(form.getContextFilterValue("date")).isEqualTo(new PeriodRange<>(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)));
        assertThat(form.getRequiredContextValues()).isEmpty();
        form.setContextFilter("active", false);
        assertThat(form.getRequiredContextValues()).containsEntry("active", false);
        form.setOpeningContextFilter("active", true);
        var locked = (TextField) panel.getControl("active");
        assertThat(locked.isReadOnly()).isTrue(); assertThat(locked.getHelperText()).isEqualTo("Задан при открытии");
        form.clearContextFilter();
        assertThat(form.getContextFilterValue("active")).isEqualTo(true);
        assertThat(form.getRequiredContextValues()).containsEntry("active", true);
        assertThat(((PeriodField<?>) panel.getControl("date")).getValue().isEmpty()).isTrue();
        assertThat(form().getContextFilterValues()).isEmpty();
        assertThat(form.getFilterPanel().isVisible()).isTrue();
        assertThat((Object) form.getFilterGrid().getColumnFilter("date")).isInstanceOf(PeriodFilter.class);
    }
    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    void conditionsUseInclusiveBoundsEscapedContainsAndCustomCondition() {
        var root = mock(jakarta.persistence.criteria.Root.class);
        var cb = mock(jakarta.persistence.criteria.CriteriaBuilder.class);
        var date = mock(jakarta.persistence.criteria.Path.class); when(root.get("date")).thenReturn(date);
        LocalDate from = LocalDate.of(2026, 11, 1), to = LocalDate.of(2026, 11, 30);
        var fields = List.of(ContextFilterField.filter("date").setPeriod());
        ContextFilterConditions.specificationFor(Row.class, fields, Map.of("date", new PeriodRange<>(from, to))).toPredicate(root, null, cb);
        verify(cb).between(date, from, to);
        ContextFilterConditions.specificationFor(Row.class, fields, Map.of("date", new PeriodRange<LocalDate>(null, to))).toPredicate(root, null, cb);
        verify(cb).lessThanOrEqualTo(date, to);
        var text = mock(jakarta.persistence.criteria.Path.class); when(root.get("name")).thenReturn(text);
        when(cb.lower(text)).thenReturn(text);
        ContextFilterConditions.specificationFor(Row.class, List.of(ContextFilterField.filter("name")), Map.of("name", "A%_")).toPredicate(root, null, cb);
        verify(cb).like(text, "%a\\%\\_%", '\\');
        var condition = mock(ContextFilterOptions.Condition.class);
        when(condition.create(false)).thenReturn((root1, query, builder) -> builder.conjunction());
        ContextFilterConditions.specificationFor(Row.class, List.of(ContextFilterField.filter("active").setCondition(condition)), Map.of("active", false)).toPredicate(root, null, cb);
        verify(condition).create(false);
        assertThat(ContextFilterConditions.specificationFor(Row.class, fields, Map.of("date", PeriodRange.empty()))).isNull();
    }
    @Test void oldCompactSettingDoesNotEnableTheRemovedMode() {
        var form = form();
        FormSettingsStore settings = mock(FormSettingsStore.class);
        when(settings.get(anyString())).thenReturn(Optional.of("true"));

        form.setViewSupport(null, settings, "row");

        assertThat(form.getFilterGrid().isCompact()).isFalse();
    }

    @Test void compactModeIsNotPartOfTheListFormToolbarAnymore() {
        var form = form();
        form.setContextFilters(List.of(ContextFilterField.filter("date").setPeriod(), ContextFilterField.filter("active")));
        assertThat(form.getToolbar().getChildren()
            .filter(com.vaadin.flow.component.button.Button.class::isInstance)
            .map(com.vaadin.flow.component.button.Button.class::cast)
            .map(button -> button.getElement().getAttribute("aria-label")))
            .doesNotContain("Компактный вид");
        assertThat(form.getFilterGrid().isCompact()).isFalse();
        var panel = (ContextFilterPanel) form.getFilterPanel().getChildren().filter(ContextFilterPanel.class::isInstance).findFirst().orElseThrow();
        assertThat(panel.getControl("date").getElement().getThemeList()).doesNotContain("small");
    }
    @Test void numericEqualityKeepsTheEntityPropertyTypeForPrefillAndRejectsMalformedInput() {
        var form = form(); form.setContextFilters(List.of(ContextFilterField.filter("quantity")));
        var panel = (ContextFilterPanel) form.getFilterPanel().getChildren().findFirst().orElseThrow();
        var input = (TextField) panel.getControl("quantity"); input.setValue("12");
        assertThat(form.getRequiredContextValues().get("quantity")).isEqualTo(Integer.valueOf(12));
        input.setValue("abc"); assertThat(input.isInvalid()).isTrue();
        assertThat(form.getContextFilterValue("quantity")).isEqualTo(12);
        input.clear(); assertThat(input.isInvalid()).isFalse();
        assertThat(form.getContextFilterValues()).doesNotContainKey("quantity");
    }
}

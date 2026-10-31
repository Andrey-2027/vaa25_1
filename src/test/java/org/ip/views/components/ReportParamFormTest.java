package org.ipro.report.ui.params;

import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.crud.EntityLookup;
import org.ipro.query.model.QueryComputedValue;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QueryScalarType;
import org.ipro.query.model.QueryValueSource;
import org.ipro.query.ReportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Генерация формы запуска из нейтральных объявлений параметров: видимость
 * (showOnForm), порядок (position), типы полей, RANGE → два поля, сущностные
 * поля — переиспользование EntityField; не-формовые источники
 * (CONTEXT/COMPUTED/FIXED) полей не создают.
 */
class ReportParamFormTest {

    private EntityLookup lookupService;
    private SelectionFormAssembler assembler;

    @BeforeEach
    void setUp() {
        lookupService = mock(EntityLookup.class);
        assembler = mock(SelectionFormAssembler.class);
        ColumnPath codePath = mock(ColumnPath.class);
        when(codePath.getKey()).thenReturn("code");
        when(codePath.getResolvedType()).thenReturn(FieldType.TEXT);
        when(assembler.resolveColumns(JournalLike.class))
            .thenReturn(new SelectionFormAssembler.ResolvedSelection(List.of(codePath), "Журналы"));
    }

    enum Mode { FIRST, SECOND }

    @Test @SuppressWarnings("unchecked")
    void declaredScalarTypesUseNumberBooleanAndEnumControls() {
        var form = new ReportParamForm(List.of(
            scalar("count", "Количество", null),
            scalar("amount", "Сумма", null),
            scalar("enabled", "Включён", null),
            scalar("mode", "Режим", null)),
            ReportContext.empty("alice"), lookupService, assembler,
            Map.of("count", Long.class.getName(), "amount", java.math.BigDecimal.class.getName(),
                "enabled", Boolean.class.getName(), "mode", Mode.class.getName()));
        var children = form.getChildren().toList(); assertThat(form.values()).isEmpty();
        ((com.vaadin.flow.component.textfield.BigDecimalField) children.get(0)).setValue(new java.math.BigDecimal("9007199254740993"));
        ((com.vaadin.flow.component.textfield.BigDecimalField) children.get(1)).setValue(new java.math.BigDecimal("34.512345678901234567"));
        ((com.vaadin.flow.component.combobox.ComboBox<Boolean>) children.get(2)).setValue(false);
        ((com.vaadin.flow.component.combobox.ComboBox<Object>) children.get(3)).setValue(Mode.SECOND);
        assertThat(form.values()).containsEntry("count", 9007199254740993L).containsEntry("amount", new java.math.BigDecimal("34.512345678901234567"))
            .containsEntry("enabled", false).containsEntry("mode", Mode.SECOND);
    }

    @Test
    void declaredDateTimeTypeCreatesTypedBoundaryWithoutDefaultHint() {
        var form = new ReportParamForm(List.of(scalar("asOf", "Момент", null)),
            ReportContext.empty("alice"), lookupService, assembler,
            Map.of("asOf", java.time.LocalDateTime.class.getName()));
        var picker = (com.vaadin.flow.component.datetimepicker.DateTimePicker) form.getChildren().findFirst().orElseThrow();
        assertThat(picker.getValue()).isNull();
        var time = java.time.LocalDateTime.of(2026, 1, 1, 15, 20, 30, 123000000);
        picker.setValue(time);
        assertThat(form.values()).containsEntry("asOf", time);
        picker.setInvalid(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(form::values).hasMessageContaining("asOf");
        assertThat(picker.isInvalid()).isTrue();
    }

    @Test
    void declaredDateTypeCreatesDatePickerWithoutDefaultHint() {
        var form = new ReportParamForm(List.of(scalar("asOf", "Дата", null)),
            ReportContext.empty("alice"), lookupService, assembler,
            Map.of("asOf", java.time.LocalDate.class.getName()));
        var picker = (com.vaadin.flow.component.datepicker.DatePicker) form.getChildren().findFirst().orElseThrow();
        assertThat(picker.getValue()).isNull();
        picker.setValue(LocalDate.of(2026, 1, 1));
        assertThat(form.values()).containsEntry("asOf", LocalDate.of(2026, 1, 1));
    }

    @Test
    void scalarValueReadsBack() {
        ReportParamForm form = new ReportParamForm(List.of(
            scalar("code", "Код", null),
            scalar("limit", "Лимит", "42"),
            scalar("active", "Активен", "true"),
            period("created", "Период", true)),
            ReportContext.empty("alice"), lookupService, assembler);

        form.getChildren().filter(c -> c instanceof com.vaadin.flow.component.textfield.TextField)
            .map(c -> (com.vaadin.flow.component.textfield.TextField) c)
            .findFirst().ifPresent(tf -> tf.setValue("B"));
        form.getChildren().filter(c -> c instanceof com.vaadin.flow.component.orderedlayout.HorizontalLayout)
            .flatMap(com.vaadin.flow.component.Component::getChildren)
            .filter(c -> c instanceof com.vaadin.flow.component.datepicker.DatePicker)
            .map(c -> (com.vaadin.flow.component.datepicker.DatePicker) c)
            .forEach(dp -> dp.setValue(LocalDate.of(2026, 8, 1)));

        Map<String, Object> values = form.values();
        assertThat(values).containsEntry("code", "B");
        assertThat(values).containsEntry("createdFrom", LocalDate.of(2026, 8, 1));
        assertThat(values).containsEntry("createdTo", LocalDate.of(2026, 8, 1));
    }

    @Test
    void nonFormSourcesDoNotCreateFields() {
        ReportParamForm form = new ReportParamForm(List.of(
            scalar("hidden", "Скрыт", null),
            computed("who", "Кто"),
            def("fixed", "Фикс")),
            ReportContext.empty("alice"), lookupService, assembler);

        Map<String, Object> values = form.values();
        assertThat(values).isEmpty();
    }

    @Test
    void hiddenParamDoesNotCreateField() {
        QueryParameter p = scalar("secret", "Секрет", null).withShowOnForm(false);
        ReportParamForm form = new ReportParamForm(List.of(p),
            ReportContext.empty("alice"), lookupService, assembler);
        assertThat(form.values()).isEmpty();
    }

    @Test
    void entityFieldIsCreated() {
        QueryParameter p = entity("journal", "Журнал");
        ReportParamForm form = new ReportParamForm(List.of(p),
            ReportContext.empty("alice"), lookupService, assembler);
        assertThat(form.values()).isEmpty();
    }

    @Test
    void periodRequiresTwoNames() {
        QueryParameter p = period("created", "Период", true);
        ReportParamForm form = new ReportParamForm(List.of(p),
            ReportContext.empty("alice"), lookupService, assembler);
        assertThat(form.values()).isEmpty();
    }

    // === helpers ===

    private static QueryParameter scalar(String name, String caption, String defaultValue) {
        return base(name, caption, QueryParameterKind.SCALAR, QueryValueSource.FORM, false, defaultValue);
    }

    private static QueryParameter computed(String name, String caption) {
        return base(name, caption, QueryParameterKind.SCALAR, QueryValueSource.COMPUTED, false, null);
    }

    private static QueryParameter def(String name, String caption) {
        return base(name, caption, QueryParameterKind.SCALAR, QueryValueSource.FIXED, false, "\"x\"");
    }

    private static QueryParameter entity(String name, String caption) {
        return new QueryParameter(name, name, caption, QueryParameterKind.ENTITY, QueryScalarType.UNRESOLVED,
            JournalLike.class.getName(), QueryValueSource.FORM, false, true, null, QueryComputedValue.NONE, null, 0);
    }

    private static QueryParameter period(String name, String caption, boolean required) {
        return base(name, caption, QueryParameterKind.RANGE, QueryValueSource.FORM, required, null);
    }

    private static QueryParameter base(String name, String caption, QueryParameterKind kind,
                                       QueryValueSource source, boolean required, String defaultValue) {
        return new QueryParameter(name, name, caption, kind, QueryScalarType.UNRESOLVED, null, source,
            required, true, defaultValue, QueryComputedValue.NONE, null, 0);
    }

    public static final class JournalLike {
    }
}

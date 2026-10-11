package org.ip.views.components;

import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.crud.EntityLookup;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.param.ReportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Генерация формы запуска из схемы параметров (Фаза 3): видимость (showOnForm),
 * порядок (position), типы полей, PERIOD → два поля, сущностные поля —
 * переиспользование EntityField; не-формовые источники (CONTEXT/COMPUTED/DEFAULT)
 * полей не создают.
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
    void visualScalarTypesUseNumberBooleanAndEnumControls() {
        var template = new org.ipro.reportstudio.dom.ReportTemplate(); template.setQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.VISUAL);
        var declarations = List.of(new org.ipro.reportstudio.query.VisualQueryDefinition.Parameter("count", Long.class.getName(), true, null),
            new org.ipro.reportstudio.query.VisualQueryDefinition.Parameter("amount", java.math.BigDecimal.class.getName(), true, null),
            new org.ipro.reportstudio.query.VisualQueryDefinition.Parameter("enabled", Boolean.class.getName(), true, null),
            new org.ipro.reportstudio.query.VisualQueryDefinition.Parameter("mode", Mode.class.getName(), true, null));
        var definition = new org.ipro.reportstudio.query.VisualQueryDefinition(3, "Product", "p", List.of(new org.ipro.reportstudio.query.VisualQueryDefinition.SelectField("id", "id")),
            List.of(), List.of(), List.of(), List.of(), null, null, declarations);
        template.setVisualQueryJson(new org.ipro.reportstudio.query.VisualQueryPackageJsonCodec().write(new org.ipro.reportstudio.query.VisualQueryPackage(List.of(), definition)));
        for (var declaration : declarations) template.addParam(scalar(declaration.name(), declaration.name(), null));
        var form = new ReportParamForm(template, ReportContext.empty("alice"), lookupService, assembler);
        var children = form.getChildren().toList(); assertThat(form.values()).isEmpty();
        ((com.vaadin.flow.component.textfield.NumberField) children.get(0)).setValue(12.0);
        ((com.vaadin.flow.component.textfield.NumberField) children.get(1)).setValue(34.5);
        ((com.vaadin.flow.component.combobox.ComboBox<Boolean>) children.get(2)).setValue(false);
        ((com.vaadin.flow.component.combobox.ComboBox<Object>) children.get(3)).setValue(Mode.SECOND);
        assertThat(form.values()).containsEntry("count", 12L).containsEntry("amount", new java.math.BigDecimal("34.5"))
            .containsEntry("enabled", false).containsEntry("mode", Mode.SECOND);
    }

    @Test
    void visualDateTimeDeclarationCreatesTypedBoundaryWithoutDefaultHint() {
        var template = new org.ipro.reportstudio.dom.ReportTemplate();
        template.setQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.VISUAL);
        var definition = new org.ipro.reportstudio.query.VisualQueryDefinition(3, "Product", "p",
            List.of(new org.ipro.reportstudio.query.VisualQueryDefinition.SelectField("id", "id")),
            List.of(), List.of(), List.of(), List.of(), null, null,
            List.of(new org.ipro.reportstudio.query.VisualQueryDefinition.Parameter("asOf", "DATETIME", true, null)));
        template.setVisualQueryJson(new org.ipro.reportstudio.query.VisualQueryPackageJsonCodec().write(
            new org.ipro.reportstudio.query.VisualQueryPackage(List.of(), definition)));
        template.addParam(scalar("asOf", "Момент", null));
        var form = new ReportParamForm(template, ReportContext.empty("alice"), lookupService, assembler);
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
    void visualDateDeclarationCreatesDatePickerWithoutDefaultHint() {
        var template = new org.ipro.reportstudio.dom.ReportTemplate();
        template.setQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.VISUAL);
        var definition = new org.ipro.reportstudio.query.VisualQueryDefinition(3, "Product", "p",
            List.of(new org.ipro.reportstudio.query.VisualQueryDefinition.SelectField("id", "id")),
            List.of(), List.of(), List.of(), List.of(), null, null,
            List.of(new org.ipro.reportstudio.query.VisualQueryDefinition.Parameter("asOf", "DATE", true, null)));
        template.setVisualQueryJson(new org.ipro.reportstudio.query.VisualQueryPackageJsonCodec().write(
            new org.ipro.reportstudio.query.VisualQueryPackage(List.of(), definition)));
        template.addParam(scalar("asOf", "Дата", null));
        var form = new ReportParamForm(template, ReportContext.empty("alice"), lookupService, assembler);
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
        ReportParam p = scalar("secret", "Секрет", null);
        p.setShowOnForm(false);
        ReportParamForm form = new ReportParamForm(List.of(p),
            ReportContext.empty("alice"), lookupService, assembler);
        assertThat(form.values()).isEmpty();
    }

    @Test
    void entityFieldIsCreated() {
        ReportParam p = entity("journal", "Журнал");
        ReportParamForm form = new ReportParamForm(List.of(p),
            ReportContext.empty("alice"), lookupService, assembler);
        assertThat(form.values()).isEmpty();
    }

    @Test
    void periodRequiresTwoNames() {
        ReportParam p = period("created", "Период", true);
        ReportParamForm form = new ReportParamForm(List.of(p),
            ReportContext.empty("alice"), lookupService, assembler);
        assertThat(form.values()).isEmpty();
    }

    // === helpers ===

    private static ReportParam scalar(String name, String caption, String defaultValue) {
        ReportParam p = base(name, caption, ReportParamKind.SCALAR, ReportParamSource.FORM, false);
        p.setDefaultValue(defaultValue);
        return p;
    }

    private static ReportParam computed(String name, String caption) {
        ReportParam p = base(name, caption, ReportParamKind.SCALAR, ReportParamSource.COMPUTED, false);
        p.setDefaultValue(null);
        return p;
    }

    private static ReportParam def(String name, String caption) {
        ReportParam p = base(name, caption, ReportParamKind.SCALAR, ReportParamSource.DEFAULT, false);
        p.setDefaultValue("\"x\"");
        return p;
    }

    private static ReportParam entity(String name, String caption) {
        ReportParam p = base(name, caption, ReportParamKind.ENTITY, ReportParamSource.FORM, false);
        p.setEntityClass(JournalLike.class.getName());
        return p;
    }

    private static ReportParam period(String name, String caption, boolean required) {
        return base(name, caption, ReportParamKind.PERIOD, ReportParamSource.FORM, required);
    }

    private static ReportParam base(String name, String caption, ReportParamKind kind,
                                    ReportParamSource source, boolean required) {
        ReportParam p = new ReportParam();
        p.setName(name);
        p.setCaption(caption);
        p.setKind(kind);
        p.setValueSource(source);
        p.setRequired(required);
        return p;
    }

    public static final class JournalLike {
    }
}

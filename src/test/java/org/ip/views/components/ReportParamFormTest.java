package org.ip.views.components;

import org.ip.form.SelectionFormAssembler;
import org.ip.metadata.ColumnPath;
import org.ip.metadata.annotation.FieldType;
import org.ip.service.LookupService;
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

    private LookupService lookupService;
    private SelectionFormAssembler assembler;

    @BeforeEach
    void setUp() {
        lookupService = mock(LookupService.class);
        assembler = mock(SelectionFormAssembler.class);
        ColumnPath codePath = mock(ColumnPath.class);
        when(codePath.getKey()).thenReturn("code");
        when(codePath.getResolvedType()).thenReturn(FieldType.TEXT);
        when(assembler.resolveColumns(JournalLike.class))
            .thenReturn(new SelectionFormAssembler.ResolvedSelection(List.of(codePath), "Журналы"));
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
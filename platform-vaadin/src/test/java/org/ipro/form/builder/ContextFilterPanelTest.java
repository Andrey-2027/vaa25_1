package org.ipro.form.builder;

import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты общей панели контекст-фильтров (Этап 1б): построение контролов,
 * обязательные метки, передача значений наружу.
 */
class ContextFilterPanelTest {

    private final MetadataResolver metadataResolver = new MetadataResolver();
    private final Map<String, Object> pushed = new LinkedHashMap<>();

    private ContextFilterPanel panel(List<ContextFilterField> fields) {
        pushed.clear();
        return new ContextFilterPanel(Unit.class, fields, metadataResolver,
            null, null, pushed::put);
    }

    @Test
    void autoTextControlPushesValueAndClear() {
        ContextFilterPanel panel = panel(List.of(ContextFilterField.auto("code", "Код")));

        assertThat(panel.getChildren().toList()).hasSize(1);
        com.vaadin.flow.component.textfield.TextField input =
            (com.vaadin.flow.component.textfield.TextField) panel.getChildren().toList().get(0);
        assertThat(input.getLabel()).isEqualTo("Код");

        input.setValue("A");
        assertThat(pushed).containsEntry("code", "A");

        input.clear();
        assertThat(pushed).containsKey("code");
        assertThat(pushed.get("code")).isNull();
    }

    @Test
    void requiredFieldMarkedWithStar() {
        ContextFilterPanel panel = panel(List.of(ContextFilterField.requiredAuto("code", "Код")));

        com.vaadin.flow.component.textfield.TextField input =
            (com.vaadin.flow.component.textfield.TextField) panel.getChildren().toList().get(0);
        assertThat(input.getLabel()).isEqualTo("Код *");
    }

    @Test
    void selectWithoutProviderFallsBackToCombo() {
        ContextFilterPanel panel = panel(List.of(ContextFilterField.select("journal", "Журнал", Unit.class)));

        assertThat(panel.getChildren().toList()).hasSize(1);
        assertThat(panel.getChildren().toList().get(0))
            .isInstanceOf(com.vaadin.flow.component.combobox.ComboBox.class);
    }

    @Test
    void setFieldsAddsMissingOnly() {
        ContextFilterPanel panel = panel(List.of(ContextFilterField.auto("code", "Код")));
        panel.setFields(List.of(
            ContextFilterField.auto("code", "Код"),
            ContextFilterField.auto("name", "Имя")));

        assertThat(panel.getChildren().toList()).hasSize(2);
    }

    @EntityMetadata(listFormTitle = "ЕИ", selectionFormTitle = "Выбор ЕИ")
    static class Unit {
        @FieldMetadata(label = "Код", grid = @GridColumn(order = 1))
        String code;
        @FieldMetadata(label = "Имя", grid = @GridColumn(order = 2))
        String name;
    }
}

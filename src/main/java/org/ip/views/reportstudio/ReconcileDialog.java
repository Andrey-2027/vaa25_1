package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.combobox.ComboBox;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.query.ReconcileResult;

/**
 * Информационный диалог о расхождениях QueryField-сета запроса и layout отчёта.
 *
 * <p>Показывает появившиеся и исчезнувшие колонки, изменение типов и битые
 * ссылки. Кнопка «Убрать поля отсутствующих колонок» удаляет из layout поля,
 * ссылавшиеся на колонки, которых больше нет в запросе.</p>
 */
public class ReconcileDialog extends Dialog {

    public ReconcileDialog(ReconcileResult result, Runnable onRemoveMissing) {
        this(result, onRemoveMissing, null, null);
    }

    public ReconcileDialog(ReconcileResult result, Runnable onRemoveMissing,
                           java.util.List<QueryField> replacementOptions,
                           java.util.function.BiConsumer<String, QueryField> onReplace) {
        setHeaderTitle("Запрос изменился — проверьте структуру");
        setModal(true);

        VerticalLayout body = new VerticalLayout();
        body.setSpacing(true);
        body.setPadding(true);
        body.setWidthFull();

        if (!result.added().isEmpty()) {
            body.add(section("Появились новые колонки (доступны в палитре):",
                    result.added().stream().map(QueryField::name).toList()));
        }
        if (!result.removed().isEmpty()) {
            body.add(section("Поля layout, ссылки на которые исчезли из запроса:",
                    result.removed().stream().map(QueryField::name).toList()));
        }
        if (!result.changedTypes().isEmpty()) {
            body.add(section("Изменились типы колонок:",
                    result.changedTypes().stream().map(Object::toString).toList()));
        }
        if (!result.unknown().isEmpty()) {
            body.add(section("Битые ссылки (не было ни до, ни после):", result.unknown()));
        }
        if (onReplace != null && replacementOptions != null && !replacementOptions.isEmpty()
                && (!result.removed().isEmpty() || !result.unknown().isEmpty())) {
            ComboBox<QueryField> oldField = new ComboBox<>("Поле, которое заменить");
            oldField.setItems(result.removed());
            oldField.setItemLabelGenerator(QueryField::name);
            ComboBox<QueryField> replacement = new ComboBox<>("Новая колонка");
            replacement.setItems(replacementOptions);
            replacement.setItemLabelGenerator(QueryField::name);
            Button replace = new Button("Заменить ссылку", event -> {
                if (oldField.getValue() != null && replacement.getValue() != null) {
                    onReplace.accept(oldField.getValue().name(), replacement.getValue());
                    close();
                }
            });
            HorizontalLayout replacementRow = new HorizontalLayout(oldField, replacement, replace);
            replacementRow.setAlignItems(FlexComponent.Alignment.END);
            body.add(replacementRow);
        }

        Button remove = new Button("Убрать поля отсутствующих колонок", event -> {
            onRemoveMissing.run();
            close();
        });
        remove.addThemeVariants(ButtonVariant.LUMO_ERROR);
        Button keep = new Button("Оставить как есть", event -> close());
        HorizontalLayout actions = new HorizontalLayout(remove, keep);
        actions.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        actions.setWidthFull();

        if (result.removed().isEmpty() && result.unknown().isEmpty()) {
            remove.setEnabled(false);
        }

        body.add(actions);
        add(body);
    }

    private static HorizontalLayout section(String title, java.util.List<String> items) {
        VerticalLayout list = new VerticalLayout();
        list.setPadding(false);
        list.setSpacing(false);
        items.forEach(item -> list.add(new Paragraph(item)));
        HorizontalLayout row = new HorizontalLayout();
        row.getElement().getStyle().set("align-items", "flex-start");
        row.add(new H3(title));
        row.add(list);
        return row;
    }
}

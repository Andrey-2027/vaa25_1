package org.ip.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;

/**
 * Диалог "Сохранить как" для GridFormView: имя вида + признак "Общий".
 * Не знает ничего про ListForm/GridFormViewService — просто спрашивает 2 значения
 * и передаёт их в onConfirm.
 */
public class SaveViewDialog extends Dialog {

    @FunctionalInterface
    public interface OnConfirm {
        void accept(String name, boolean shared);
    }

    public SaveViewDialog(OnConfirm onConfirm) {
        setHeaderTitle("Сохранить вид");
        setModal(true);
        setDraggable(true);
        setWidth("400px");

        TextField nameField = new TextField("Название вида");
        nameField.setWidthFull();
        nameField.setRequiredIndicatorVisible(true);

        Checkbox sharedBox = new Checkbox("Общий (виден и редактируем всеми пользователями)");

        VerticalLayout content = new VerticalLayout(nameField, sharedBox);
        content.setPadding(false);
        content.setSpacing(true);
        add(content);

        Button save = new Button("Сохранить", e -> {
            String name = nameField.getValue();
            if (name == null || name.isBlank()) {
                Notification.show("Укажите название вида", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            onConfirm.accept(name.trim(), sharedBox.getValue());
            close();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancel = new Button("Отмена", e -> close());

        getFooter().add(cancel, save);
    }
}

package org.ip.views.forms;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.application.catalog.AttributeValuePermissions;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.service.AttributeValueService;
import org.ipro.form.FieldFactory;
import org.ipro.form.builtin.ItemForm;
import org.ipro.metadata.EntityMetadataInfo;

import java.util.List;

/**
 * Форма типа атрибута: generic-поля справочника + секция «Значения» — список значений
 * типа (код/значение). Для «Внутреннего справочника» — добавление новых значений
 * в процессе работы; переименование — обработкой (кнопка «Переименовать», id строки
 * не меняется, привязки следуют автоматически). Удаления нет: значения бессмертны.
 *
 * <p><b>Доступность кнопок — тот же ответ, что у серверного пути (ADR-0019 §5).</b> И создание
 * значения, и переименование требуют права C5, поэтому кнопка берёт ответ у владельца операции
 * ({@link AttributeValuePermissions}), а не из собственного условия формы: включённая кнопка,
 * которую сервер откажет, — обещание, которого никто не исполнит. Сужение по типу значения
 * (ENUM для добавления, «не Ссылка» для переименования) остаётся доменным правилом формы и
 * действует вместе с решением.</p>
 */
public class AttributeTypeForm extends ItemForm<AttributeType> {

    private final AttributeValueService valueService;

    /** Решение о доступности операций — тот же источник, что у серверного пути. */
    private final AttributeValuePermissions permissions;
    private final Grid<AttributeValue> valuesGrid = new Grid<>();
    private final Button addValueButton = new Button("Добавить значение", VaadinIcon.PLUS.create());
    private final Button renameValueButton = new Button("Переименовать", VaadinIcon.PENCIL.create());

    private AttributeType current;
    private AttributeValue selectedValue;
    private boolean readOnly;

    public AttributeTypeForm(EntityMetadataInfo meta, FieldFactory fieldFactory,
                             AttributeValueService valueService,
                             AttributeValuePermissions permissions) {
        super(meta, fieldFactory, (List<String>) null);
        this.valueService = valueService;
        this.permissions = permissions;

        H4 title = new H4("Значения");
        valuesGrid.addColumn(AttributeValue::getCode).setHeader("Код").setWidth("200px");
        valuesGrid.addColumn(AttributeValue::getName).setHeader("Значение").setFlexGrow(1);
        valuesGrid.setWidthFull();
        valuesGrid.setHeight("220px");

        addValueButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        addValueButton.addClickListener(e -> openAddValueDialog());

        renameValueButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        renameValueButton.addClickListener(e -> openRenameDialog());
        valuesGrid.asSingleSelect().addValueChangeListener(e -> {
            selectedValue = e.getValue();
            updateButtons();
        });

        HorizontalLayout toolbar = new HorizontalLayout(addValueButton, renameValueButton);
        VerticalLayout section = new VerticalLayout(title, toolbar, valuesGrid);
        section.setPadding(false);
        section.setSpacing(true);
        section.setWidthFull();

        // секция — перед footer'ом (footer всегда последний компонент формы)
        addComponentAtIndex(getComponentCount() - 1, section);
    }

    @Override
    public void setEntity(AttributeType entity) {
        super.setEntity(entity);
        this.current = entity;
        refreshValues();
    }

    @Override
    public void setReadOnly(boolean readOnly) {
        super.setReadOnly(readOnly);
        this.readOnly = readOnly;
        updateButtons();
    }

    private void refreshValues() {
        AttributeType type = current;
        if (type == null || type.getId() == null) {
            valuesGrid.setItems(List.of());
        } else {
            valuesGrid.setItems(valueService.findByAttrType(type));
        }
        updateButtons();
    }

    private void updateButtons() {
        AttributeType type = current;
        boolean editable = !readOnly && type != null && type.getId() != null;
        // Доменное сужение действует вместе с решением C5, а не вместо него: кнопка открыта
        // ровно там, где серверный путь исполнит операцию.
        addValueButton.setEnabled(editable && type.getValueType() == AttributeValueType.ENUM
            && permissions.canCreateValue());
        // «Ссылка» не переименовывается: имя — снапшот строки словаря
        renameValueButton.setEnabled(editable && selectedValue != null
            && type.getValueType() != AttributeValueType.REF && permissions.canRenameValue());
    }

    private void openAddValueDialog() {
        AttributeType type = current;
        if (type == null || type.getId() == null) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Новое значение — " + type.getDisplayName());

        TextField codeField = new TextField("Код (необязательно)");
        codeField.setWidthFull();
        TextField nameField = new TextField("Значение");
        nameField.setRequiredIndicatorVisible(true);
        nameField.setWidthFull();

        Button saveButton = new Button("Сохранить", e -> {
            try {
                valueService.createEnumValue(type, codeField.getValue(), nameField.getValue());
                dialog.close();
                refreshValues();
            } catch (RuntimeException ex) {
                Notification.show(ex.getMessage(), 4000, Notification.Position.TOP_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        Button cancelButton = new Button("Отмена", e -> dialog.close());

        dialog.add(new VerticalLayout(codeField, nameField));
        dialog.getFooter().add(cancelButton, saveButton);
        dialog.open();
    }

    /**
     * Обработка переименования кода значения: id строки не меняется, поэтому все привязки
     * (номенклатура, будущие наборы КСУ) следуют за строкой автоматически. Для ENUM
     * дополнительно можно поправить подпись; для «Ссылки» кнопка недоступна.
     */
    private void openRenameDialog() {
        AttributeValue value = selectedValue;
        if (value == null || current == null) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Переименовать значение — " + value.getDisplayName());

        TextField codeField = new TextField("Код");
        codeField.setValue(value.getCode());
        codeField.setWidthFull();

        TextField nameField = new TextField("Значение (наименование)");
        VerticalLayout fields = new VerticalLayout(codeField);
        if (current.getValueType() == AttributeValueType.ENUM) {
            nameField.setValue(value.getName());
            nameField.setWidthFull();
            fields.add(nameField);
        }

        Button saveButton = new Button("Переименовать", e -> {
            try {
                valueService.renameValue(value.getId(), codeField.getValue(), nameField.getValue());
                dialog.close();
                refreshValues();
            } catch (RuntimeException ex) {
                Notification.show(ex.getMessage(), 4000, Notification.Position.TOP_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        Button cancelButton = new Button("Отмена", e -> dialog.close());

        dialog.add(fields);
        dialog.getFooter().add(cancelButton, saveButton);
        dialog.open();
    }
}
package org.ip.views.forms;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.model.AttributeType;
import org.ip.model.NomSklAttribute;
import org.ip.model.Nomenclature;
import org.ip.service.NomSklAttributeService;
import org.ipro.form.FieldFactory;
import org.ipro.form.builtin.ItemForm;
import org.ipro.metadata.EntityMetadataInfo;

import java.util.List;

/**
 * Форма номенклатуры: generic-поля справочника + секция «Атрибуты КСУ» — привязки
 * «Номенклатура ↔ Тип атрибута» (+ признак «Обязателен»). Привязка — схема разреза
 * карточки: в документе для ввода значений будут предложены ровно привязанные типы
 * (и только они); введённая комбинация станет экземпляром набора SklNomOpa.
 *
 * <p>Режим write-through (как секция «Значения» в {@link AttributeTypeForm}): кнопки
 * «Привязать»/«Отвязать» пишут сразу через {@link NomSklAttributeService}, записи шапки
 * формы не касаются. Привязки — настройка, а не история: отвязка допустима; уже созданные
 * наборы не затрагиваются. Секция появляется после первого сохранения позиции (до этого
 * привязке некуда ссылаться).</p>
 *
 * <p>Список доступных типов атрибутов приходит готовым (canonical lookup): форма не
 * открывает собственный read-путь к справочнику типов.</p>
 */
public class NomenclatureItemForm extends ItemForm<Nomenclature> {

    private final NomSklAttributeService bindingService;
    private final List<AttributeType> availableTypes;

    private final Grid<NomSklAttribute> bindingsGrid = new Grid<>();
    private final Button bindButton = new Button("Привязать атрибут", VaadinIcon.PLUS.create());
    private final Button unbindButton = new Button("Отвязать", VaadinIcon.UNLINK.create());

    private Nomenclature current;
    private NomSklAttribute selectedBinding;
    private boolean readOnly;

    public NomenclatureItemForm(EntityMetadataInfo meta, FieldFactory fieldFactory,
                                NomSklAttributeService bindingService,
                                List<AttributeType> availableTypes) {
        super(meta, fieldFactory, (List<String>) null);
        this.bindingService = bindingService;
        this.availableTypes = availableTypes == null ? List.of() : List.copyOf(availableTypes);

        H4 title = new H4("Атрибуты КСУ");
        bindingsGrid.addColumn(b -> b.getAttrType() != null ? b.getAttrType().getCode() : "")
            .setHeader("Код").setWidth("140px");
        bindingsGrid.addColumn(b -> b.getAttrType() != null ? b.getAttrType().getDisplayName() : "")
            .setHeader("Атрибут КСУ").setFlexGrow(1);
        bindingsGrid.addComponentColumn(b -> {
            Checkbox box = new Checkbox("Обязателен");
            box.setValue(b.isRequired());
            box.setEnabled(false); // изменение признака — через отвязку и повторную привязку
            return box;
        }).setHeader("Обязателен").setWidth("140px");
        bindingsGrid.setWidthFull();
        bindingsGrid.setHeight("200px");

        bindButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        bindButton.addClickListener(e -> openBindDialog());

        unbindButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        unbindButton.addClickListener(e -> {
            try {
                bindingService.unbind(selectedBinding.getId());
                refreshBindings();
            } catch (RuntimeException ex) {
                showError(ex.getMessage());
            }
        });
        bindingsGrid.asSingleSelect().addValueChangeListener(e -> {
            selectedBinding = e.getValue();
            updateButtons();
        });

        HorizontalLayout toolbar = new HorizontalLayout(bindButton, unbindButton);
        VerticalLayout section = new VerticalLayout(title, toolbar, bindingsGrid);
        section.setPadding(false);
        section.setSpacing(true);
        section.setWidthFull();

        // секция — перед footer'ом (footer всегда последний компонент формы)
        addComponentAtIndex(getComponentCount() - 1, section);
    }

    @Override
    public void setEntity(Nomenclature entity) {
        super.setEntity(entity);
        this.current = entity;
        refreshBindings();
    }

    @Override
    public void setReadOnly(boolean readOnly) {
        super.setReadOnly(readOnly);
        this.readOnly = readOnly;
        updateButtons();
    }

    private void refreshBindings() {
        Nomenclature nom = current;
        if (nom == null || nom.getId() == null) {
            bindingsGrid.setItems(List.of());
            bindingsGrid.setVisible(false);
        } else {
            bindingsGrid.setItems(bindingService.findByNomenclature(nom));
            bindingsGrid.setVisible(true);
        }
        updateButtons();
    }

    private void updateButtons() {
        Nomenclature nom = current;
        boolean editable = !readOnly && nom != null && nom.getId() != null;
        bindButton.setEnabled(editable);
        unbindButton.setEnabled(editable && selectedBinding != null);
    }

    /** Диалог привязки: выбор активного типа + признак «обязателен»; запись сразу. */
    private void openBindDialog() {
        Nomenclature nom = current;
        if (nom == null || nom.getId() == null) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Привязать атрибут КСУ — " + nom.getDisplayName());

        Grid<AttributeType> typeGrid = new Grid<>(AttributeType.class, false);
        typeGrid.addColumn(AttributeType::getCode).setHeader("Код").setWidth("140px");
        typeGrid.addColumn(AttributeType::getDisplayName).setHeader("Атрибут").setFlexGrow(1);
        typeGrid.addColumn(t -> t.getValueType() != null ? t.getValueType().getLabel() : "")
            .setHeader("Тип значения").setWidth("160px");
        typeGrid.setItems(availableTypes);
        typeGrid.setHeight("240px");
        typeGrid.setSelectionMode(Grid.SelectionMode.SINGLE);

        Checkbox requiredBox = new Checkbox("Обязателен в документе");
        requiredBox.setValue(false);

        Button saveButton = new Button("Привязать", e -> {
            AttributeType selected = typeGrid.asSingleSelect().getValue();
            if (selected == null) {
                showError("Выберите атрибут.");
                return;
            }
            try {
                bindingService.bind(nom, selected, requiredBox.getValue());
                dialog.close();
                refreshBindings();
            } catch (RuntimeException ex) {
                showError(ex.getMessage());
            }
        });
        Button cancelButton = new Button("Отмена", e -> dialog.close());

        dialog.add(new VerticalLayout(typeGrid, requiredBox));
        dialog.getFooter().add(cancelButton, saveButton);
        dialog.open();
    }

    private void showError(String message) {
        Notification.show(message, 5000, Notification.Position.MIDDLE)
            .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}

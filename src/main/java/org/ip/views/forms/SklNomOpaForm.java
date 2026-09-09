package org.ip.views.forms;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ip.model.SklNomOpa;
import org.ip.model.SklNomOpaValue;
import org.ip.service.SklNomOpaService;
import org.ipro.form.FieldFactory;
import org.ipro.form.builtin.ItemForm;
import org.ipro.metadata.EntityMetadataInfo;

import java.util.List;

/**
 * Форма набора атрибутов КСУ — только просмотр. Набор immutable: создаётся обработкой
 * {@code findOrCreate} (аналог {@code spSklCreateFindGoodsCardAttr} Ис-про), правка и
 * удаление не предусмотрены ({@code save/create/update/delete} сервиса бросают исключение).
 *
 * <p>К generic-полям шапки (только чтение — {@code readOnly = true} в метаданных полей)
 * добавляется секция «Значения» — строки набора «тип → значение». Кнопки «Сохранить» нет:
 * {@link #withDefaultButtons()} переопределён и добавляет только «Отмена».</p>
 */
public class SklNomOpaForm extends ItemForm<SklNomOpa> {

    private final SklNomOpaService setService;

    private final Grid<SklNomOpaValue> valuesGrid = new Grid<>();

    public SklNomOpaForm(EntityMetadataInfo meta, FieldFactory fieldFactory,
                         SklNomOpaService setService) {
        super(meta, fieldFactory, (List<String>) null);
        this.setService = setService;

        H4 title = new H4("Значения набора");
        valuesGrid.addColumn(v -> v.getAttrType() != null ? v.getAttrType().getDisplayName() : "")
            .setHeader("Атрибут").setFlexGrow(1);
        valuesGrid.addColumn(v -> v.getValue() != null ? v.getValue().getDisplayName() : "")
            .setHeader("Значение").setFlexGrow(1);
        valuesGrid.setWidthFull();
        valuesGrid.setHeight("220px");

        VerticalLayout section = new VerticalLayout(title, valuesGrid);
        section.setPadding(false);
        section.setSpacing(true);
        section.setWidthFull();

        // секция — перед footer'ом (footer всегда последний компонент формы)
        addComponentAtIndex(getComponentCount() - 1, section);
    }

    @Override
    public void setEntity(SklNomOpa entity) {
        super.setEntity(entity);
        if (entity == null || entity.getId() == null) {
            valuesGrid.setItems(List.of());
        } else {
            valuesGrid.setItems(setService.getItems(entity));
        }
    }

    /** Набор неизменяем — сохранять нечего: только «Отмена» (закрытие). */
    @Override
    public ItemForm<SklNomOpa> withDefaultButtons() {
        addCancelButton();
        return this;
    }
}

package org.ip.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.notification.Notification;
import org.ip.model.GridFormView;

import java.util.List;
import java.util.function.Consumer;

/**
 * Список видов (GridFormView), доступных пользователю для конкретной формы
 * (уже отфильтрован вызывающим кодом — см. GridFormViewService.findVisibleViews):
 * общие + свои личные. Позволяет применить вид к текущему гриду и/или сделать его
 * видом по умолчанию для себя.
 *
 * "Вид по умолчанию" здесь — это НЕ поле на GridFormView, а отдельная запись в
 * UserFormSettings ("listform.defaultview.&lt;formKey&gt;") — см. ListForm.setViewSupport().
 * Поэтому текущий defaultViewId передаётся снаружи, а не читается отсюда напрямую.
 */
public class ViewSelectorDialog extends Dialog {

    private final Grid<GridFormView> grid = new Grid<>(GridFormView.class, false);

    public ViewSelectorDialog(List<GridFormView> views,
                              String currentDefaultViewId,
                              Consumer<GridFormView> onApply,
                              Consumer<GridFormView> onSetDefault,
                              Runnable onClearDefault) {
        setHeaderTitle("Виды");
        setModal(true);
        setDraggable(true);
        setResizable(true);
        setWidth("520px");
        setHeight("480px");

        grid.addColumn(GridFormView::getName).setHeader("Название").setFlexGrow(1);
        grid.addColumn(GridFormView::getCreatedBy).setHeader("Автор").setWidth("140px").setFlexGrow(0);
        grid.addColumn(v -> v.isShared() ? "Общий" : "Личный")
            .setHeader("Тип").setWidth("100px").setFlexGrow(0);
        grid.addColumn(v -> v.getId() != null && v.getId().toString().equals(currentDefaultViewId)
                ? "✓" : "")
            .setHeader("По умолчанию").setWidth("110px").setFlexGrow(0);
        grid.setItems(views);
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);
        grid.setSizeFull();
        add(grid);

        Button apply = new Button("Применить", e -> {
            GridFormView selected = grid.asSingleSelect().getValue();
            if (selected == null) {
                Notification.show("Выберите вид", 3000, Notification.Position.MIDDLE);
                return;
            }
            onApply.accept(selected);
            close();
        });
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button setDefault = new Button("Сделать по умолчанию", e -> {
            GridFormView selected = grid.asSingleSelect().getValue();
            if (selected == null) {
                Notification.show("Выберите вид", 3000, Notification.Position.MIDDLE);
                return;
            }
            onSetDefault.accept(selected);
            close();
        });

        Button clearDefault = new Button("Убрать умолчание", e -> {
            onClearDefault.run();
            close();
        });
        clearDefault.setTooltipText("Вернуться к составу колонок из метаданных при следующем открытии формы");

        Button close = new Button("Закрыть", e -> close());

        getFooter().add(clearDefault, setDefault, close, apply);
    }
}

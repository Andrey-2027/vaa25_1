package org.ipro.query.ui.listform;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;

import java.util.List;
import java.util.function.Consumer;

/**
 * Диалог «Виды» — как у ListForm: список видов (общие и свои) с колонкой «По умолчанию»,
 * «Стандартный вид», «Создать», «Копировать», «Изменить», «Удалить», «Загрузить».
 */
final class QueryViewsDialog extends Dialog {
    private final QueryViewsController controller;
    private final Grid<QueryListViews.View> grid = new Grid<>();
    private final Button copy = new Button("Копировать");
    private final Button edit = new Button("Изменить");
    private final Button delete = new Button("Удалить");
    private final Button load = new Button("Загрузить");

    QueryViewsDialog(String title, QueryViewsController controller) {
        this.controller = controller;
        setHeaderTitle("Виды: " + title);
        setWidth("460px");

        grid.addColumn(QueryListViews.View::name).setHeader("Название").setFlexGrow(1);
        grid.addComponentColumn(view -> view.shared() ? new Icon(VaadinIcon.CHECK) : new Span0())
                .setHeader("Общий").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(view -> {
            boolean current = view.id().equals(controller.defaultViewId().orElse(null));
            Button star = new Button(new Icon(current ? VaadinIcon.STAR : VaadinIcon.STAR_O));
            star.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            star.getElement().setProperty("title", current ? "Снять вид по умолчанию" : "Вид по умолчанию для меня");
            star.addClickListener(event -> run(() -> controller.toggleDefault(view.id())));
            return star;
        }).setHeader("По умолчанию").setAutoWidth(true).setFlexGrow(0);
        grid.setAllRowsVisible(false);
        grid.setHeight("240px");
        grid.asSingleSelect().addValueChangeListener(event -> updateButtons());

        Button standard = new Button("Стандартный вид", event -> run(() -> {
            controller.loadStandard();
            close();
        }));
        Button create = new Button("Создать", event -> nameDialog("Новый вид", "", false, (name, shared) ->
                run(() -> controller.create(name, shared))));
        copy.addClickListener(event -> selected(view -> run(() -> controller.copy(view.id()))));
        edit.addClickListener(event -> selected(view -> nameDialog("Изменить вид", view.name(), view.shared(),
                (name, shared) -> run(() -> controller.edit(view.id(), name, shared)))));
        delete.addClickListener(event -> selected(view -> {
            ConfirmDialog confirm = new ConfirmDialog();
            confirm.setHeader("Удалить вид «" + view.name() + "»?");
            confirm.setCancelable(true);
            confirm.setConfirmText("Удалить");
            confirm.addConfirmListener(done -> run(() -> controller.delete(view.id())));
            confirm.open();
        }));
        load.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        load.addClickListener(event -> selected(view -> run(() -> {
            controller.load(view.id());
            close();
        })));
        Button closeButton = new Button("Закрыть", event -> close());

        HorizontalLayout actions = new HorizontalLayout(standard, create, copy, edit, delete);
        actions.getStyle().set("flex-wrap", "wrap");
        VerticalLayout body = new VerticalLayout(grid, actions);
        body.setPadding(false);
        add(body);
        getFooter().add(new HorizontalLayout(closeButton, load));
        reload();
    }

    private void reload() {
        List<QueryListViews.View> rows = controller.rows();
        grid.setItems(rows);
        updateButtons();
    }

    private void updateButtons() {
        var view = grid.asSingleSelect().getValue();
        copy.setEnabled(view != null);
        load.setEnabled(view != null);
        edit.setEnabled(view != null && view.editable());
        delete.setEnabled(view != null && view.editable());
    }

    private void selected(Consumer<QueryListViews.View> action) {
        var view = grid.asSingleSelect().getValue();
        if (view != null) action.accept(view);
    }

    private void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            Notification.show(error.getMessage() == null ? "Не удалось выполнить действие" : error.getMessage());
        }
        if (isOpened()) reload();
    }

    private static void nameDialog(String title, String name, boolean shared, java.util.function.BiConsumer<String, Boolean> done) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        TextField field = new TextField("Название");
        field.setValue(name);
        field.setWidthFull();
        Checkbox common = new Checkbox("Общий", shared);
        Button ok = new Button("Сохранить", event -> {
            if (field.getValue().isBlank()) {
                field.setInvalid(true);
                field.setErrorMessage("Укажите название");
                return;
            }
            dialog.close();
            done.accept(field.getValue(), common.getValue());
        });
        ok.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        VerticalLayout body = new VerticalLayout(field, common);
        body.setPadding(false);
        body.setAlignItems(FlexComponent.Alignment.STRETCH);
        dialog.add(body);
        dialog.getFooter().add(new Button("Отмена", event -> dialog.close()), ok);
        dialog.open();
    }

    /** Пустая ячейка колонки «Общий». */
    private static final class Span0 extends com.vaadin.flow.component.html.Span { }
}

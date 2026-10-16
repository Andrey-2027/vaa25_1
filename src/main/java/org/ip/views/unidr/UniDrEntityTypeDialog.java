package org.ip.views.unidr;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Shared metadata chooser for report sources and entity parameter types. */
final class UniDrEntityTypeDialog extends Dialog {
    UniDrEntityTypeDialog(String title, List<QueryBuilderMetadataCatalog.Entity> entities,
                          String currentType, Consumer<QueryBuilderMetadataCatalog.Entity> selected) {
        setHeaderTitle(title);
        setWidth("min(900px, 96vw)");

        var grid = new Grid<QueryBuilderMetadataCatalog.Entity>();
        grid.setWidthFull();
        grid.setHeight("min(440px, 55vh)");
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);
        grid.getElement().setAttribute("aria-label", title);
        grid.addColumn(QueryBuilderMetadataCatalog.Entity::category).setHeader("Раздел").setFlexGrow(1);
        grid.addColumn(QueryBuilderMetadataCatalog.Entity::caption).setHeader("Сущность").setFlexGrow(2);
        grid.addColumn(QueryBuilderMetadataCatalog.Entity::entityName).setHeader("Имя в запросе").setFlexGrow(1);
        grid.setItems(entities);

        var search = new TextField("Поиск");
        search.setPlaceholder("Название, раздел или имя в запросе");
        search.setWidthFull();
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addValueChangeListener(event -> {
            String term = event.getValue().strip().toLowerCase(Locale.ROOT);
            grid.deselectAll();
            grid.setItems(entities.stream().filter(entity ->
                (entity.caption() + " " + entity.category() + " " + entity.entityName())
                    .toLowerCase(Locale.ROOT).contains(term)).toList());
        });

        var select = new Button("Выбрать");
        select.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        select.setEnabled(false);
        grid.addSelectionListener(event -> select.setEnabled(event.getFirstSelectedItem().isPresent()));
        Consumer<QueryBuilderMetadataCatalog.Entity> accept = entity -> {
            if (entity == null) return;
            selected.accept(entity);
            close();
        };
        select.addClickListener(event -> accept.accept(grid.asSingleSelect().getValue()));
        grid.addItemDoubleClickListener(event -> accept.accept(event.getItem()));
        entities.stream().filter(entity -> entity.javaType().getName().equals(currentType))
            .findFirst().ifPresent(grid::select);

        add(search, grid);
        getFooter().add(new Button("Отмена", event -> close()), select);
        addOpenedChangeListener(event -> { if (event.isOpened()) search.focus(); });
    }
}

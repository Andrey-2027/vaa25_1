package org.ipro.reportstudio.query.constructor;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.treegrid.TreeGrid;
import org.ipro.reportstudio.query.VisualQueryOrder;

import java.util.List;

/**
 * Вкладка «Порядок»: слева поля выбранных таблиц, справа список сортировок
 * «поле + направление (ASC/DESC)» с редактированием направления в строке.
 */
final class OrderingTab extends VerticalLayout {

    private final QueryConstructorDraft draft;
    private final Runnable onChange;

    private final TreeGrid<ConstructorTreeNode> fields = new TreeGrid<>();
    private final Grid<VisualQueryOrder> orders = new Grid<>();
    /** Корни дерева полей последнего refreshFromDraft (для тестов). */
    private List<ConstructorTreeNode> fieldRoots = List.of();

    OrderingTab(QueryConstructorDraft draft, Runnable onChange) {
        this.draft = draft;
        this.onChange = onChange == null ? () -> { } : onChange;

        setPadding(false);
        setSpacing(false);
        setWidthFull();
        setHeightFull();
        getStyle().set("min-height", "0");

        fields.addHierarchyColumn(ConstructorTreeNode::caption).setHeader("Сущности и поля")
                .setFlexGrow(1).setResizable(true);
        fields.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);

        orders.addColumn(order -> draft.displayPath(order.path())).setHeader("Поле").setFlexGrow(1);
        orders.addComponentColumn(this::directionCombo).setHeader("Направление").setAutoWidth(true).setFlexGrow(0);
        orders.addComponentColumn(this::removeButton).setAutoWidth(true).setFlexGrow(0);
        orders.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);

        HorizontalLayout buttons = new HorizontalLayout(
                small(">", this::addSelected, "Добавить сортировку"),
                small("<", this::removeSelected, "Убрать выбранную сортировку"),
                small("<<", draft::clearOrders, "Убрать все сортировки"));
        buttons.setPadding(false);
        buttons.setSpacing(false);
        buttons.setWidth("80px");
        buttons.getStyle().set("flex-direction", "column");
        buttons.setAlignItems(FlexComponent.Alignment.CENTER);

        VerticalLayout left = new VerticalLayout(new Span("Поля"), fields);
        left.setPadding(false);
        left.setSpacing(false);
        left.setSizeFull();
        left.getStyle().set("min-height", "0").set("min-width", "0").set("gap", "4px");
        left.setFlexGrow(1, fields);

        VerticalLayout right = new VerticalLayout(new Span("Порядок"), orders);
        right.setPadding(false);
        right.setSpacing(false);
        right.setSizeFull();
        right.getStyle().set("min-height", "0").set("gap", "4px");
        right.setFlexGrow(1, orders);

        HorizontalLayout content = new HorizontalLayout(left, buttons, right);
        content.setPadding(false);
        content.setSpacing(false);
        content.setWidthFull();
        content.setHeightFull();
        content.setFlexGrow(1, left, right);
        content.getStyle().set("min-height", "0");
        add(content);
    }

    void refreshFromDraft() {
        fieldRoots = CatalogTreeModel.tableRoots(draft.tables(), draft.roots());
        fields.setItems(fieldRoots, ConstructorTreeNode::children);
        fields.expand(fieldRoots);
        orders.setItems(draft.orders());
    }

    /** Доступ для тестов. */
    List<ConstructorTreeNode> fieldRoots() { return fieldRoots; }

    void addSelected() {
        var node = fields.asSingleSelect().getValue();
        if (node == null || node.kind() != ConstructorTreeNode.Kind.PROPERTY) return;
        var base = node.ownerEntity().entity();
        String path = draft.ensureChain(base, node.chainToOwner()) + "." + node.field().name();
        draft.addOrder(path, VisualQueryOrder.Direction.ASC);
        changed();
    }

    void removeSelected() {
        var order = orders.asSingleSelect().getValue();
        if (order == null) return;
        draft.removeOrder(order);
        changed();
    }

    private ComboBox<VisualQueryOrder.Direction> directionCombo(VisualQueryOrder order) {
        ComboBox<VisualQueryOrder.Direction> combo = new ComboBox<>();
        combo.setItems(VisualQueryOrder.Direction.values());
        combo.setItemLabelGenerator(direction -> direction == VisualQueryOrder.Direction.ASC ? "Возрастание" : "Убывание");
        combo.setWidth("140px");
        combo.setValue(order.direction());
        combo.addValueChangeListener(event -> {
            if (event.getValue() != null && event.getValue() != order.direction()) {
                draft.replaceOrderDirection(order, event.getValue());
                changed();
            }
        });
        return combo;
    }

    private Button removeButton(VisualQueryOrder order) {
        Button button = new Button("×");
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        button.getElement().setAttribute("title", "Убрать сортировку");
        button.addClickListener(event -> {
            draft.removeOrder(order);
            changed();
        });
        return button;
    }

    private Button small(String caption, Runnable action, String tooltip) {
        Button button = new Button(caption, event -> action.run());
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        button.getElement().setAttribute("title", tooltip);
        button.setWidthFull();
        return button;
    }

    private void changed() {
        onChange.run();
    }
}

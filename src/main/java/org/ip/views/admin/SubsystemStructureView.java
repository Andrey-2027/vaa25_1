package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.ipro.filtergrid.inmemory.InMemoryFilterGrid;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ipro.vaadin.explorer.SubsystemSummaryAssembler;
import org.ipro.vaadin.explorer.SubsystemSummaryAssembler.Catalog;
import org.ipro.vaadin.explorer.SubsystemSummaryAssembler.EntityFacet;
import org.ipro.vaadin.explorer.SubsystemSummaryAssembler.Group;
import org.ipro.vaadin.explorer.SubsystemSummaryAssembler.SubsystemRef;
import org.ip.views.workspace.Workspace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * «Структура подсистем» — read-only каталог (обратное направление Entity Explorer):
 * выбрал подсистему → видишь её сущности и грани (наименование, нумерация, кастомные
 * формы, контекст-фильтры, RLS-гейт). Поверхность чтения (роль 2), ничего не пишет;
 * данные — из {@link SubsystemSummaryAssembler} (готовый агрегат, без хождения по сырым
 * реестрам в UI).
 *
 * <p>Слева — дерево подсистем ({@link TreeGrid}): иерархия «Документы → Производство»
 * с числом сущностей поддерева; поиск оставляет только совпавшие узлы и их предков.
 * Пустые подсистемы показываются с пометкой «(пусто)»; сущности без подсистемы
 * ({@code NoSubsystem}) — отдельным корнем «Без подсистемы».</p>
 *
 * <p>Навигация наружу: «открыть список» по строке сущности (через
 * {@link FormCoordinator}) и «структура» — диалог со сводкой сущности
 * (общая панель {@link EntitySummaryPanel}).</p>
 */
@SpringComponent
@Scope("prototype")
public class SubsystemStructureView extends VerticalLayout {

    /** Узел дерева подсистем: маркер {@code @Subsystem} (null — «Без подсистемы»). */
    private static final class Item {
        private final Class<?> markerClass;
        private final String title;
        private final int entityCount;
        private final List<Item> children = new ArrayList<>();

        Item(Class<?> markerClass, String title, int entityCount) {
            this.markerClass = markerClass;
            this.title = title;
            this.entityCount = entityCount;
        }

        String label() {
            return entityCount == 0 ? title + "  (пусто)" : title + "  (" + entityCount + ")";
        }
    }

    private final SubsystemSummaryAssembler assembler;
    private final EntitySummaryAssembler entityAssembler;
    private final FormCoordinator coordinator;

    private final TextField searchField = new TextField();
    private final TreeGrid<Item> tree = new TreeGrid<>(Item.class);
    private final VerticalLayout detail = new VerticalLayout();

    private List<Item> allRoots = List.of();
    /** Текущий выбор (для восстановления при перестройке дерева поиском). */
    private Item selectedItem;


    public SubsystemStructureView(@Autowired SubsystemSummaryAssembler assembler,
                                  @Autowired EntitySummaryAssembler entityAssembler,
                                  @Autowired FormCoordinator coordinator) {
        this.assembler = assembler;
        this.entityAssembler = entityAssembler;
        this.coordinator = coordinator;
        setSizeFull();
        setPadding(true);
        setSpacing(true);
    }

    /** Вызывается из MainLayout сразу после создания (initializer в workspace.open). */
    public void init(Workspace workspace) {
        coordinator.setWorkspace(workspace);
        removeAll();
        if (!isAdmin()) {
            add(new H3("Доступно только администратору"));
            return;
        }
        buildUi();
    }

    private void buildUi() {
        add(new H3("Структура подсистем"));

        Span hint = new Span("Read-only каталог: подсистема → её сущности и грани. Ничего не " +
                "сохраняется — источник всех значений «код». Состав подсистем меняется в коде " +
                "(@Subsystem / @EntityMetadata.subsystem), администраторских редакторов нет.");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(hint);

        HorizontalLayout body = new HorizontalLayout(buildSubsystemPanel(), buildDetailPanel());
        body.setSizeFull();
        body.setSpacing(true);
        body.setFlexGrow(1, detail);
        add(body);
        setFlexGrow(1, body);
    }

    // ---------------------------------------------------------------- левая панель (дерево)

    private VerticalLayout buildSubsystemPanel() {
        searchField.setLabel("Подсистемы");
        searchField.setPlaceholder("Поиск по названию…");
        searchField.setClearButtonVisible(true);
        searchField.setWidthFull();
        searchField.addValueChangeListener(e -> applyFilter());

        tree.setWidthFull();
        tree.setHeightFull();
        tree.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_ROW_BORDERS);
        tree.addHierarchyColumn(Item::label).setHeader("Подсистемы").setResizable(true).setFlexGrow(1);
        tree.setSelectionMode(Grid.SelectionMode.SINGLE);
        tree.addSelectionListener(e -> e.getFirstSelectedItem().ifPresent(this::select));

        VerticalLayout panel = new VerticalLayout(searchField, tree);
        panel.setWidth("360px");
        panel.setHeightFull();
        panel.setPadding(false);
        panel.setSpacing(true);
        panel.setFlexGrow(1, tree);

        allRoots = buildItems();
        applyFilter();
        return panel;
    }

    /** Корни дерева из каталога (pre-order + depth восстанавливают иерархию). */
    private List<Item> buildItems() {
        Catalog catalog = assembler.catalog();
        List<Item> roots = new ArrayList<>();
        Deque<Item> stack = new ArrayDeque<>();
        for (SubsystemRef ref : catalog.subsystems()) {
            // Стек = предки; размер стека - 1 == глубина вершины. Pop, пока глубина не совпадёт.
            while (!stack.isEmpty() && stack.size() > ref.depth()) {
                stack.pop();
            }
            Item item = new Item(ref.markerClass(), ref.title(), ref.entityCount());
            if (stack.isEmpty()) {
                roots.add(item);
            } else {
                stack.peek().children.add(item);
            }
            stack.push(item);
        }
        int unassigned = catalog.unassigned().size();
        if (unassigned > 0) {
            roots.add(new Item(null, "Без подсистемы", unassigned));
        }
        return roots;
    }

    private void applyFilter() {
        String query = searchField.getValue() == null ? "" : searchField.getValue().trim().toLowerCase(Locale.ROOT);
        List<Item> visibleRoots = new ArrayList<>();
        for (Item root : allRoots) {
            collectMatching(root, query, visibleRoots);
        }
        TreeData<Item> data = new TreeData<>();
        data.addItems(visibleRoots, item -> item.children);
        tree.setDataProvider(new TreeDataProvider<>(data));
        tree.expand(visibleRoots);

        // Восстановить выбор по маркеру (узлы — копии); иначе — первый видимый корень.
        Item restore = findIn(visibleRoots, selectedItem == null ? null : selectedItem.markerClass);
        if (restore != null) {
            tree.select(restore);
        } else if (!visibleRoots.isEmpty()) {
            tree.select(visibleRoots.get(0));
        }
    }

    private static Item findIn(List<Item> items, Class<?> markerClass) {
        for (Item item : items) {
            if (item.markerClass == markerClass) {
                return item;
            }
            Item found = findIn(item.children, markerClass);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Оставляет совпавшие узлы и их предков (копии с отфильтрованными детьми). */
    private boolean collectMatching(Item item, String query, List<Item> out) {
        boolean self = query.isEmpty() || item.title.toLowerCase(Locale.ROOT).contains(query);
        List<Item> keptChildren = new ArrayList<>();
        boolean anyChild = false;
        for (Item child : item.children) {
            if (collectMatching(child, query, keptChildren)) {
                anyChild = true;
            }
        }
        if (self || anyChild) {
            Item copy = new Item(item.markerClass, item.title, item.entityCount);
            copy.children.addAll(keptChildren);
            out.add(copy);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- правая панель

    private VerticalLayout buildDetailPanel() {
        detail.setSizeFull();
        detail.setPadding(false);
        detail.setSpacing(true);
        showPlaceholder();
        return detail;
    }

    private void select(Item item) {
        selectedItem = item;
        detail.removeAll();
        if (item == null) {
            showPlaceholder();
            return;
        }
        List<Group> groups = assembler.groupsOf(item.markerClass);

        H3 title = new H3(item.label());
        title.getStyle().set("margin-bottom", "0");
        detail.add(title);

        if (groups.isEmpty()) {
            Span empty = new Span("В этой подсистеме нет сущностей.");
            empty.getStyle().set("color", "var(--lumo-secondary-text-color)");
            detail.add(empty);
            return;
        }
        for (Group group : groups) {
            addEntityGrid(group);
        }
    }

    private void showPlaceholder() {
        detail.removeAll();
        Span empty = new Span("Выберите подсистему слева — здесь появятся её сущности и грани.");
        empty.getStyle().set("color", "var(--lumo-secondary-text-color)");
        detail.add(empty);
    }

    private void addEntityGrid(Group group) {
        H4 header = new H4(group.path() + "  (" + group.entities().size() + ")");
        header.getStyle().set("margin", "0").set("margin-top", "0.5em");
        detail.add(header);

        InMemoryFilterGrid<EntityFacet> grid =
            new InMemoryFilterGrid<>(EntityFacet.class, group.entities());
        grid.addColumn("entity", "Сущность",
            r -> r.displayName().value() + "  (" + r.simpleName() + ")")
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("numbering", "Нумерация", r -> text(r.numbering()))
            .setResizable(true).setWidth("230px").setFlexGrow(0);
        grid.addColumn("customForms", "Формы (кастом.)", r -> yesNo(r.customForms()))
            .setResizable(true).setWidth("140px").setFlexGrow(0);
        grid.addColumn("contextFilters", "Фильтры", r -> yesNo(r.contextFilters()))
            .setResizable(true).setWidth("100px").setFlexGrow(0);
        grid.addColumn("rlsGate", "RLS-гейт", r -> yesNo(r.rlsGate()))
            .setResizable(true).setWidth("110px").setFlexGrow(0);
        grid.addComponentColumn("structure", "Структура", this::structureAction)
            .setResizable(true).setWidth("120px").setFlexGrow(0);
        grid.addComponentColumn("openList", "", this::openAction)
            .setResizable(true).setWidth("140px").setFlexGrow(0);
        grid.setWidthFull();
        grid.setHeight(null);
        grid.setCompact(true);
        grid.getGrid().setAllRowsVisible(true);
        detail.add(grid);
    }

    private Button structureAction(EntityFacet facet) {
        Button open = new Button("структура", new Icon(VaadinIcon.LIST_UL));
        open.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        open.addClickListener(e -> openStructureDialog(facet));
        return open;
    }

    private Button openAction(EntityFacet facet) {
        Button open = new Button("открыть список", new Icon(VaadinIcon.OPEN_BOOK));
        open.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        open.addClickListener(e -> navigateTo(facet.entityClass()));
        return open;
    }

    /** Диалог со сводкой сущности (общая панель EntitySummaryPanel — тот же рендер, что в Explorer). */
    private void openStructureDialog(EntityFacet facet) {
        showStructureInDialog(facet.entityClass(), null);
    }

    /**
     * Показывает сводку сущности в диалоге (или перерисовывает существующий при навигации
     * из Lookup/обратных ссылок внутри панели: одна панель сменяется другой в том же диалоге).
     */
    private void showStructureInDialog(Class<?> entityClass, Dialog dialog) {
        EntitySummary summary = entityAssembler.summarize(entityClass);
        EntitySummaryPanel panel = new EntitySummaryPanel(coordinator);
        Dialog finalDialog = dialog;
        panel.setStructureNavigator(target -> showStructureInDialog(target, finalDialog));
        panel.show(summary);

        if (dialog == null) {
            dialog = new Dialog();
            dialog.setWidth("1100px");
            dialog.setHeight("85%");
            dialog.setResizable(true);
        } else {
            dialog.removeAll();
        }
        dialog.setHeaderTitle("Структура сущности: " + summary.displayName().value()
            + " (" + summary.simpleName() + ")");
        dialog.add(panel);
        dialog.open();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void navigateTo(Class<?> entityClass) {
        coordinator.openListForm((Class) entityClass, null, null);
    }

    // ---------------------------------------------------------------- помощники

    private static String text(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    private static String yesNo(boolean value) {
        return value ? "да" : "—";
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }
}
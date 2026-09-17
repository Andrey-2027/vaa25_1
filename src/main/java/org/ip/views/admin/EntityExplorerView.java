package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ipro.vaadin.explorer.EntitySummaryAssembler.EntityRef;
import org.ip.views.workspace.Workspace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Entity Explorer — каталог сущностей (read-only «поверхность чтения»). Левая панель —
 * дерево: сущность → её табличные части (строки {@code @TableSectionMetadata}) как дети.
 * Клик по узлу открывает структуру сущности во <b>вкладке внутри этого вида</b>
 * ({@link TabSheet} справа): одна сущность — одна вкладка, повторный клик фокусирует
 * существующую, навигация из Lookup/обратных ссылок открывает новую вкладку здесь же.
 * Вкладки закрываемые — маленькая «×» справа (как вкладки приложения).
 *
 * <p>Принцип: объединять поверхность чтения, а не владение фактами. Сам каталог ничего
 * не хранит и не пишет; вся сводка — {@link EntitySummaryPanel} поверх {@link EntitySummary}.</p>
 */
@SpringComponent
@Scope("prototype")
public class EntityExplorerView extends VerticalLayout {

    /** Узел дерева: сущность (с детьми-табчастями) либо строка табличной части. */
    private static final class Item {
        private final EntityRef ref;
        private final boolean section;
        private final String label;
        private final List<Item> children = new ArrayList<>();

        Item(EntityRef ref, boolean section, String label) {
            this.ref = ref;
            this.section = section;
            this.label = label;
        }

        String label() {
            return label;
        }

        EntityRef entity() {
            return ref;
        }
    }

    private final EntitySummaryAssembler assembler;
    private final FormCoordinator coordinator;

    private final TextField searchField = new TextField();
    private final TreeGrid<Item> tree = new TreeGrid<>(Item.class);
    private final TabSheet tabSheet = new TabSheet();
    private final Map<Class<?>, Tab> openTabs = new LinkedHashMap<>();

    private List<Item> allRoots = List.of();

    public EntityExplorerView(@Autowired EntitySummaryAssembler assembler,
                              @Autowired FormCoordinator coordinator) {
        this.assembler = assembler;
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
        add(new H3("Entity Explorer — структура сущностей"));

        Span hint = new Span("Read-only каталог: выберите сущность — вкладка справа покажет её " +
                "метаданные (поля, колонки, формы, фильтры, обратные ссылки, нумерация). " +
                "Одна сущность — одна вкладка, вкладки закрываются «×». Ничего не сохраняется " +
                "и не переопределяется. Табличные части показаны детьми главной сущности.");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(hint);

        HorizontalLayout split = new HorizontalLayout(buildTreePanel(), tabSheet);
        split.setSizeFull();
        split.setPadding(false);
        split.setSpacing(true);
        split.setFlexGrow(0, split.getComponentAt(0));
        split.setFlexGrow(1, tabSheet);
        add(split);
        setFlexGrow(1, split);
    }

    private VerticalLayout buildTreePanel() {
        searchField.setLabel("Сущности");
        searchField.setPlaceholder("Поиск по имени…");
        searchField.setClearButtonVisible(true);
        searchField.setWidthFull();
        searchField.addValueChangeListener(e -> applyFilter());

        tree.setWidthFull();
        tree.setHeightFull();
        tree.addHierarchyColumn(Item::label).setHeader("Сущности").setResizable(true).setFlexGrow(1);
        tree.setSelectionMode(com.vaadin.flow.component.grid.Grid.SelectionMode.SINGLE);
        tree.addSelectionListener(e -> e.getFirstSelectedItem().ifPresent(this::openStructure));

        VerticalLayout panel = new VerticalLayout(searchField, tree);
        panel.setSizeFull();
        panel.setPadding(false);
        panel.setSpacing(true);
        panel.setFlexGrow(1, tree);
        panel.setWidth("360px");

        allRoots = buildItems();
        applyFilter();
        return panel;
    }

    private List<Item> buildItems() {
        List<Item> roots = new ArrayList<>();
        for (EntityRef ref : assembler.entities()) {
            String entityLabel = ref.displayName().value() + "  (" + ref.simpleName() + ")";
            Item entity = new Item(ref, false, entityLabel);
            for (String section : assembler.tableSectionRowNames(ref.entityClass())) {
                entity.children.add(new Item(ref, true, "  ⤷ " + section));
            }
            roots.add(entity);
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
    }

    /** Оставляет совпавшие узлы и их предков (копии с отфильтрованными детьми). */
    private boolean collectMatching(Item item, String query, List<Item> out) {
        boolean self = query.isEmpty() || item.label().toLowerCase(Locale.ROOT).contains(query);
        List<Item> keptChildren = new ArrayList<>();
        boolean anyChild = false;
        for (Item child : item.children) {
            if (collectMatching(child, query, keptChildren)) {
                anyChild = true;
            }
        }
        if (self || anyChild) {
            Item copy = new Item(item.entity(), item.section, item.label());
            copy.children.addAll(keptChildren);
            out.add(copy);
            return true;
        }
        return false;
    }

    private void openStructure(Item item) {
        openEntityTab(item.entity().entityClass());
    }

    /** Открыть (или сфокусировать) вкладку сущности внутри этого вида. */
    private void openEntityTab(Class<?> entityClass) {
        Tab existing = openTabs.get(entityClass);
        if (existing != null) {
            tabSheet.setSelectedTab(existing);
            return;
        }

        EntitySummary summary = assembler.summarize(entityClass);
        String tabTitle = summary.displayName().value() + "  (" + summary.simpleName() + ")";
        EntitySummaryPanel panel = new EntitySummaryPanel(coordinator);
        panel.setStructureNavigator(this::openEntityTab);
        panel.show(summary);

        Tab tab = createClosableTab(tabTitle, entityClass);
        tabSheet.add(tab, panel);
        openTabs.put(entityClass, tab);
        tabSheet.setSelectedTab(tab);
    }

    /** Вкладка «как в приложении»: заголовок + маленькая «×» для закрытия. */
    private Tab createClosableTab(String title, Class<?> entityClass) {
        Span label = new Span(title);
        label.getStyle().set("white-space", "nowrap");

        Button close = new Button(new Icon(VaadinIcon.CLOSE_SMALL));
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        close.getStyle().set("margin", "0").set("padding", "0");
        close.setWidth("16px");
        close.setHeight("16px");
        close.getElement().setAttribute("aria-label", "Закрыть вкладку: " + title);
        close.addClickListener(e -> closeTab(entityClass));

        HorizontalLayout layout = new HorizontalLayout(label, close);
        layout.setSpacing(false);
        layout.setPadding(false);
        layout.setAlignItems(FlexComponent.Alignment.CENTER);
        return new Tab(layout);
    }

    /** Закрыть вкладку сущности; при закрытии активной — активируется соседняя. */
    private void closeTab(Class<?> entityClass) {
        Tab tab = openTabs.remove(entityClass);
        if (tab == null) {
            return;
        }
        boolean wasSelected = tab == tabSheet.getSelectedTab();
        tabSheet.remove(tab);
        if (wasSelected && !openTabs.isEmpty()) {
            tabSheet.setSelectedTab(openTabs.values().iterator().next());
        }
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }
}
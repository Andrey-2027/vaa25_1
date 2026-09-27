package org.ip.views.admin;

import com.vaadin.flow.component.Component;
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
import com.vaadin.flow.spring.annotation.UIScope;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ipro.vaadin.explorer.EntitySummaryAssembler.EntityRef;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

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
 *
 * <p>E3.0: доступ спрашивается у {@link EntityExplorerAccess} — того же правила, что у route
 * и пункта меню: копия проверки в этом классе расходилась бы с ними молча.</p>
 *
 * <p>E3.0: владелец вида — host: вкладка открывается {@code openComponent}, а не создаётся
 * заново на каждый вход, поэтому повторный вход по адресу применяет ключ к уже открытой вкладке.
 * Скоуп — {@code @UIScope}: один вид на UI, иначе адрес менял бы одну вкладку, а показывалась бы
 * другая.</p>
 */
@SpringComponent
@UIScope
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
    private final FormNavigator navigator;
    private final EntityExplorerAccess access;
    private final FormRouteCatalog catalog;

    private final TextField searchField = new TextField();
    private final TreeGrid<Item> tree = new TreeGrid<>(Item.class);
    private final TabSheet tabSheet = new TabSheet();
    private final Map<Class<?>, Tab> openTabs = new LinkedHashMap<>();

    private List<Item> allRoots = List.of();

    /** Видимое дерево после фильтра: в данных провайдера лежат копии, а не {@link #allRoots}. */
    private List<Item> visibleRoots = List.of();

    /** Кто отвечает за адрес вкладки при выборе типа: host, а не вид (E3.0). */
    private Consumer<Class<?>> typeSelectionListener = type -> {
    };

    /** Идёт программный выбор узла при входе по адресу: это не выбор пользователя. */
    private boolean applyingEntry;

    public EntityExplorerView(@Autowired EntitySummaryAssembler assembler,
                              @Autowired FormNavigator navigator,
                              @Autowired EntityExplorerAccess access,
                              @Autowired FormRouteCatalog catalog) {
        this.assembler = assembler;
        this.navigator = navigator;
        this.access = access;
        this.catalog = catalog;
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        searchField.addValueChangeListener(e -> {
            if (!applyingEntry) {
                applyFilter();
            }
        });
        tree.addSelectionListener(e -> e.getFirstSelectedItem().ifPresent(this::openStructure));
        tabSheet.addSelectedChangeListener(e -> {
            if (!applyingEntry) {
                typeSelectionListener.accept(typeOfTab(e.getSelectedTab()));
            }
        });
    }

    /** Вход из меню: выбор сбрасывается, адрес вкладки до первого выбора остаётся безадресным. */
    public void init() {
        init(null);
    }

    /**
     * Вход по адресу Explorer (E3.0): тот же вид получает новый ключ, второй вид не открывается.
     *
     * <p>Программный выбор узла адрес не пишет: адрес уже стоит в окне (ADR-0010), и повторная
     * запись добавила бы шаг истории на вход по ссылке. За адрес при выборе пользователя отвечает
     * host — {@link #setTypeSelectionListener(Consumer)}.</p>
     */
    public void init(Class<?> type) {
        applyingEntry = true;
        try {
            removeAll();
            searchField.clear();
            tree.deselectAll();
            boolean allowed = access.allows();
            if (type == null || !allowed) {
                clearEntityTabs();
            }
            if (!allowed) {
                add(new H3("Доступно только администратору"));
                return;
            }
            buildUi();
            if (type != null) {
                selectType(type);
            }
        } finally {
            applyingEntry = false;
        }
    }

    /** Кто отвечает за адрес вкладки при выборе типа: host, а не вид (E3.0). */
    public void setTypeSelectionListener(Consumer<Class<?>> listener) {
        this.typeSelectionListener = listener == null ? type -> {
        } : listener;
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

        tree.setWidthFull();
        tree.setHeightFull();
        tree.addHierarchyColumn(Item::label).setHeader("Сущности").setResizable(true).setFlexGrow(1);
        tree.setSelectionMode(com.vaadin.flow.component.grid.Grid.SelectionMode.SINGLE);

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
        this.visibleRoots = visibleRoots;
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

    /**
     * Выбрать узел типа при входе по адресу. Ищем по <b>видимому</b> дереву, а не по исходному
     * списку: после фильтра в данных лежат копии, и выделение оригинала не нашло бы строку.
     * Тип вне дерева — не ошибка адреса: карточку откроет выбор пользователя.
     */
    private void selectType(Class<?> type) {
        Item match = findRoot(visibleRoots, type);
        if (match != null) {
            tree.select(match);
        }
        // Адрес уже разрешён каталогом. Карточка должна открыться и при отсутствии узла в
        // текущем дереве, иначе URL укажет на новый тип, а справа останется прежний.
        openEntityTab(type);
    }

    private Class<?> typeOfTab(Tab tab) {
        if (tab == null) {
            return null;
        }
        for (Map.Entry<Class<?>, Tab> entry : openTabs.entrySet()) {
            if (entry.getValue() == tab) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void clearEntityTabs() {
        for (Tab tab : List.copyOf(openTabs.values())) {
            tabSheet.remove(tab);
        }
        openTabs.clear();
    }

    private static Item findRoot(List<Item> roots, Class<?> type) {
        for (Item root : roots) {
            if (type.equals(root.entity().entityClass())) {
                return root;
            }
        }
        return null;
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
        EntitySummaryPanel panel = new EntitySummaryPanel(navigator);
        panel.setStructureNavigator(this::openEntityTab);
        panel.show(summary);

        Tab tab = createClosableTab(tabTitle, entityClass);
        openTabs.put(entityClass, tab);
        tabSheet.add(tab, addressedContent(panel, entityClass));
        tabSheet.setSelectedTab(tab);
    }

    /**
     * Содержимое вкладки: карточка плюс, при отсутствии опубликованного ключа, строка о причине
     * (E3.0). Без неё вкладка выглядела бы безадресной без объяснения, а на вопрос «почему у типа
     * нет ссылки» отвечает каталог, а не карточка: перестраивать её здесь незачем — это E3.2.
     */
    private Component addressedContent(EntitySummaryPanel panel, Class<?> entityClass) {
        if (catalog.find(entityClass).isPresent()) {
            return panel;
        }
        Span hint = new Span("У этого типа нет публичного адреса: ключ не опубликован каталогом "
            + "маршрутов, поэтому ссылка на него не выдаётся.");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)")
            .set("padding", "var(--lumo-space-s) var(--lumo-space-m)");
        VerticalLayout content = new VerticalLayout(hint, panel);
        content.setPadding(false);
        content.setSpacing(false);
        content.setSizeFull();
        return content;
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
        boolean previousApplyingEntry = applyingEntry;
        applyingEntry = true;
        try {
            tabSheet.remove(tab);
            if (wasSelected && !openTabs.isEmpty()) {
                tabSheet.setSelectedTab(openTabs.values().iterator().next());
            }
        } finally {
            applyingEntry = previousApplyingEntry;
        }
        if (wasSelected && !applyingEntry) {
            typeSelectionListener.accept(typeOfTab(tabSheet.getSelectedTab()));
        }
    }

}

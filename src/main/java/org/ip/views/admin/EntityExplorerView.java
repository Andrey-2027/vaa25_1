package org.ip.views.admin;

import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.ipro.filtergrid.inmemory.InMemoryFilterGrid;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Entity Explorer — каталог сущностей (read-only «поверхность чтения»). Слева дерево,
 * построенное {@link ExplorerTreeModel} по immutable снимку {@link ExplorerSnapshot}: группы
 * (по видам сущностей либо по подсистемам), типы, их аспекты, разделы, поля и owned-секции.
 * Справа — <b>одна</b> карточка выбранного типа ({@link EntitySummaryPanel}): вкладки сущностей
 * как отдельные вкладки не открываются, узел раздела фокусирует существующее место той же
 * карточки.
 *
 * <p>E3.2.2 §9.2: карточка создаётся по выбору из готовой сводки снимка и кешируется на UI; у
 * кешированной панели при подключении заново задаются актуальные {@code placeListener} и
 * {@code structureNavigator} — старые колбэки не накапливаются. Повторный выбор активного типа
 * сохраняет его аспект и раскрытые разделы. Прямой адрес с якорем всегда применяется к карточке
 * и имеет приоритет над сохранённым местом.</p>
 *
 * <p>E3.2.2 §9.3: поиск идёт по индексу снимка ({@link ExplorerSnapshot#searchTerms()}), а не по
 * подписи узла; фильтры (вид, подсистема, экспозиция, «только ошибки», служебные типы)
 * комбинируются пересечением и применяются к типам/root'ам до группировки; счётчики
 * ERROR/WARNING агрегируются снизу вверх. Раскрытия и выбор сохраняются по стабильным id узлов —
 * объекты-копии отфильтрованного дерева идентичностью не являются.</p>
 *
 * <p>E3.2.2 §4.4: {@link #applyMenuEntry()} — вход из меню. Вид хранит последний выбор текущего
 * UI ({@link ExplorerUiState}) и восстанавливает его тип, место, поиск, фильтры и раскрытия;
 * первый вход показывает пустую карточку. Прямой адрес и программный вход ({@link #init(Class,
 * String)}) сбрасывают мешающие фильтры и раскрывают путь к названному типу. Причина входа
 * различается host'ом явно: {@code type == null} больше не означает одновременно «меню» и
 * «сбросить карточку».</p>
 *
 * <p>E3.0: доступ спрашивается у {@link EntityExplorerAccess} — того же правила, что у route
 * и пункта меню; владелец вида — host: вкладка открывается {@code openComponent}, а не
 * создаётся заново на каждый вход. Скоуп — {@code @UIScope}: один вид на UI, иначе адрес менял
 * бы одну вкладку, а показывалась бы другая.</p>
 *
 * <p>Принцип: объединять поверхность чтения, а не владение фактами. Снимок каталога строится
 * один раз на контекст UI после проверки ADMIN. При входе проверяются пользователь, роли и
 * локаль: их смена очищает снимок, кеш панелей и состояние того же вида. Новый UI начинает
 * с пустым состоянием; изменённый инвентарь применяется после перезапуска приложения/нового UI.
 * Поиск, фильтры и повторный вход из меню
 * новых попыток {@code summarize()} не делают: они читают тот же снимок.</p>
 */
@SpringComponent
@UIScope
public class EntityExplorerView extends VerticalLayout {

    /** Каталог, собранный для этого UI: проекция дерева и общие диагностики без карточки. */
    private record Catalog(ExplorerTreeModel.CatalogView view,
                           List<EntitySummary.DiagnosticRow> diagnostics) {
    }

    private record CatalogContext(String principal, List<String> authorities, Locale locale) {
    }

    /** Восстановленный входом из меню выбор: тип и место карточки; адрес собирает host. */
    public record RestoredSelection(Class<?> type, String anchor) {
    }

    private final Supplier<Catalog> catalogSource;
    private final FormNavigator navigator;
    private final EntityExplorerAccess access;

    private final TextField searchField = new TextField();
    private final Select<ExplorerTreeModel.GroupMode> groupMode = new Select<>();
    private final Select<EntityKind> kindFilter = new Select<>();
    private final Select<ExplorerSnapshot.SubsystemRef> subsystemFilter = new Select<>();
    private final Select<String> exposureFilter = new Select<>();
    private final Checkbox onlyErrorsFilter = new Checkbox("Только ошибки");
    private final Checkbox serviceTypesFilter = new Checkbox("Служебные типы");
    private final Span emptyResult = new Span("Ничего не найдено");
    private final TreeGrid<ExplorerTreeModel.Node> tree =
        new TreeGrid<>(ExplorerTreeModel.Node.class);
    private final VerticalLayout cardHost = new VerticalLayout();
    private final Map<Class<?>, EntitySummaryPanel> panels = new LinkedHashMap<>();

    /** Последний выбор текущего UI — единственный владелец сохранённого типа и места. */
    private final ExplorerUiState state = new ExplorerUiState();

    private Catalog catalog;
    private CatalogContext catalogContext;
    private List<ExplorerTreeModel.Node> allRoots = List.of();
    private List<ExplorerTreeModel.Node> visibleRoots = List.of();

    /** Тип карточки, показанной сейчас; {@code null} — карточки нет. */
    private Class<?> selectedType;

    /**
     * Кто отвечает за адрес при выборе пользователя: host, а не вид (E3.0). Выбор несёт тип
     * и место карточки — якорь выбранного аспекта/раздела либо {@code null} — «место не названо».
     */
    private BiConsumer<Class<?>, String> selectionListener = (type, anchor) -> {
    };

    /** Идёт программное применение входа: это не выбор пользователя. */
    private boolean applyingEntry;

    /** Следующее построение дерева раскрывает всё заново: так прямой вход раскрывает путь к типу. */
    private boolean resetExpansions;
    private boolean filterOptionsInitialized;
    private String displayedQuery = "";

    public EntityExplorerView(@Autowired EntitySummaryAssembler assembler,
                              @Autowired FormNavigator navigator,
                              @Autowired EntityExplorerAccess access) {
        this(() -> {
            ExplorerSnapshot snapshot = assembler.explorerSnapshot();
            return new Catalog(ExplorerTreeModel.CatalogView.of(snapshot),
                snapshot.unassignedDiagnostics());
        }, navigator, access);
    }

    /** Пакетный шов для тестов: тот же вид поверх уже собранного каталога, без сборщика. */
    EntityExplorerView(ExplorerTreeModel.CatalogView view,
                       List<EntitySummary.DiagnosticRow> diagnostics,
                       FormNavigator navigator, EntityExplorerAccess access) {
        this(() -> new Catalog(view, diagnostics), navigator, access);
    }

    private EntityExplorerView(Supplier<Catalog> catalogSource, FormNavigator navigator,
                               EntityExplorerAccess access) {
        this.catalogSource = catalogSource;
        this.navigator = navigator;
        this.access = access;
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        configureOnce();
        searchField.addValueChangeListener(e -> {
            if (!applyingEntry) {
                applyFilter();
            }
        });
        groupMode.addValueChangeListener(e -> {
            if (!applyingEntry) {
                rebuildTree();
            }
        });
        kindFilter.addValueChangeListener(e -> {
            if (!applyingEntry) {
                rebuildTree();
            }
        });
        subsystemFilter.addValueChangeListener(e -> {
            if (!applyingEntry) {
                rebuildTree();
            }
        });
        exposureFilter.addValueChangeListener(e -> {
            if (!applyingEntry) {
                rebuildTree();
            }
        });
        onlyErrorsFilter.addValueChangeListener(e -> {
            if (!applyingEntry) {
                rebuildTree();
            }
        });
        serviceTypesFilter.addValueChangeListener(e -> {
            if (!applyingEntry) {
                rebuildTree();
            }
        });
        tree.addSelectionListener(e -> {
            if (applyingEntry) {
                return;
            }
            e.getFirstSelectedItem().ifPresent(this::onTreeSelection);
        });
    }

    /**
     * Настройка, принадлежащая виду, а не входу: колонка дерева, поиск, фильтры и режим выбора
     * создаются один раз на UI-скоуп, а входы только применяют состояние. Иначе повторный вход
     * добавлял бы к тому же дереву вторую колонку и вторую копию настройки (E3.2.2 §9.0).
     */
    private void configureOnce() {
        searchField.setLabel("Сущности");
        searchField.setPlaceholder("Поиск по подписи, ключу, полю…");
        searchField.setClearButtonVisible(true);
        searchField.setWidthFull();

        groupMode.setLabel("Группировка");
        groupMode.setItems(ExplorerTreeModel.GroupMode.values());
        groupMode.setItemLabelGenerator(mode -> mode == ExplorerTreeModel.GroupMode.KIND
            ? "По видам сущностей" : "По подсистемам");
        groupMode.setValue(ExplorerTreeModel.GroupMode.KIND);
        groupMode.setWidthFull();

        kindFilter.setLabel("Вид");
        kindFilter.setPlaceholder("Все виды");
        kindFilter.setEmptySelectionAllowed(true);
        kindFilter.setEmptySelectionCaption("Все виды");
        kindFilter.setItemLabelGenerator(kind -> kind == null ? "Все виды" : ExplorerTreeModel.kindFilterTitle(kind));
        kindFilter.setWidthFull();

        subsystemFilter.setLabel("Подсистема");
        subsystemFilter.setPlaceholder("Все подсистемы");
        subsystemFilter.setEmptySelectionAllowed(true);
        subsystemFilter.setEmptySelectionCaption("Все подсистемы");
        subsystemFilter.setItemLabelGenerator(ref -> ref == null ? "Все подсистемы" : ref.label().value());
        subsystemFilter.setWidthFull();

        exposureFilter.setLabel("Экспозиция");
        exposureFilter.setPlaceholder("Любая экспозиция");
        exposureFilter.setEmptySelectionAllowed(true);
        exposureFilter.setEmptySelectionCaption("Любая экспозиция");
        exposureFilter.setItemLabelGenerator(value -> value == null ? "Любая экспозиция"
            : ExplorerTreeModel.exposureLabel(value));
        exposureFilter.setWidthFull();

        onlyErrorsFilter.getStyle().set("margin-top", "0");
        serviceTypesFilter.getStyle().set("margin-top", "0");

        emptyResult.getStyle().set("color", "var(--lumo-secondary-text-color)");
        emptyResult.setVisible(false);

        tree.setWidthFull();
        tree.setHeightFull();
        // Узел — record: конструктор TreeGrid(Class) автогенерирует колонки по компонентам.
        // Дереву нужна одна колонка подписи, второй набор колонок не заводится.
        tree.removeAllColumns();
        tree.addHierarchyColumn(ExplorerTreeModel.Node::label)
            .setHeader("Сущности").setResizable(true).setFlexGrow(1);
        tree.setSelectionMode(Grid.SelectionMode.SINGLE);
    }

    /**
     * Вход из меню (E3.2.2 §4.4): восстановить последний выбор текущего UI либо показать
     * пустую карточку. Место, поиск, фильтры и раскрытия не сбрасываются — их выбирал
     * пользователь; адрес вкладки host регистрирует по возвращённому выбору до активации.
     */
    public Optional<RestoredSelection> applyMenuEntry() {
        applyingEntry = true;
        try {
            removeAll();
            if (!access.allows()) {
                clearCard();
                add(new H3("Доступно только администратору"));
                return Optional.empty();
            }
            ensureCatalogContext();
            Optional<ExplorerUiState.Selection> restored = state.restore(this::selectableType);
            buildUi();
            if (restored.isEmpty()) {
                clearCard();
                if (state.lastRestoreFailed()) {
                    showRestoreFailure();
                }
                return Optional.empty();
            }
            ExplorerUiState.Selection selection = restored.orElseThrow();
            selectType(selection.type(), selection.anchor());
            return Optional.of(new RestoredSelection(selection.type(), selection.anchor()));
        } finally {
            applyingEntry = false;
        }
    }

    /** Прежний вход из меню: тот же путь, что {@link #applyMenuEntry()}, результат не нужен. */
    public void init() {
        applyMenuEntry();
    }

    /** Вход по адресу Explorer без якоря: тот же вид получает новый ключ. */
    public void init(Class<?> type) {
        init(type, null);
    }

    /**
     * Прямой вход — по адресу Explorer (E3.0) с якорем места (E3.2.1 §8.2) либо программный
     * запрос из формы/подсистемы (E3.2.2 §4.4): тот же вид получает названный тип, второй вид не
     * открывается, а мешающие фильтры снимаются — прямой вход всегда раскрывает путь к типу.
     * Программный выбор узла адрес не пишет: адрес уже стоит в окне (ADR-0010), за адрес при
     * выборе пользователя отвечает host — {@link #setSelectionListener(BiConsumer)}.
     */
    public void init(Class<?> type, String anchor) {
        if (type == null) {
            // «Меню» и «сбросить карточку» — разные вещи: без типа вход идёт путём меню.
            applyMenuEntry();
            return;
        }
        applyingEntry = true;
        try {
            removeAll();
            if (!access.allows()) {
                clearCard();
                add(new H3("Доступно только администратору"));
                return;
            }
            ensureCatalogContext();
            resetFilters();
            clearCard();
            state.remember(type, anchor);
            if (ExplorerTreeModel.isServiceType(catalog().view, type)) {
                // Служебный тип — названный, а не «случайно показанный»: путь к нему обязан быть.
                serviceTypesFilter.setValue(true);
            }
            buildUi();
            selectType(type, anchor);
        } finally {
            applyingEntry = false;
        }
    }

    /**
     * Кто отвечает за адрес при выборе пользователя: host, а не вид (E3.0). Выбор несёт тип и
     * место карточки: якорь выбранного аспекта/раздела либо {@code null} — «место не названо».
     */
    public void setSelectionListener(BiConsumer<Class<?>, String> listener) {
        this.selectionListener = listener == null ? (type, anchor) -> {
        } : listener;
    }

    private void buildUi() {
        add(new H3("Entity Explorer — структура сущностей"));

        Span hint = new Span("Read-only каталог: слева дерево — группы, типы и их разделы; справа "
            + "одна карточка выбранного типа с вкладками аспектов. Клик по разделу дерева "
            + "открывает то же место карточки, второй карточки не появляется. Ничего не "
            + "сохраняется и не переопределяется.");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(hint);
        addUnassignedDiagnostics(catalog().diagnostics());

        cardHost.setSizeFull();
        cardHost.setPadding(false);
        cardHost.setSpacing(false);
        HorizontalLayout split = new HorizontalLayout(buildTreePanel(), cardHost);
        split.setSizeFull();
        split.setPadding(false);
        split.setSpacing(true);
        split.setFlexGrow(0, split.getComponentAt(0));
        split.setFlexGrow(1, cardHost);
        add(split);
        setFlexGrow(1, split);
    }

    /** Панель дерева: собранные один раз компоненты плюс данные, построенные для этого входа. */
    private VerticalLayout buildTreePanel() {
        VerticalLayout panel = new VerticalLayout(searchField, groupMode, kindFilter,
            subsystemFilter, exposureFilter, onlyErrorsFilter, serviceTypesFilter, tree, emptyResult);
        panel.setSizeFull();
        panel.setPadding(false);
        panel.setSpacing(true);
        panel.setFlexGrow(1, tree);
        panel.setWidth("360px");

        refreshFilterOptions();
        allRoots = ExplorerTreeModel.roots(catalog().view, groupMode.getValue(), currentFilter());
        applyFilter();
        return panel;
    }

    /**
     * Снимок строится лениво после проверки ADMIN, один раз на контекст пользователя/ролей/локали.
     * Повторное открытие Workspace-вкладки в том же контексте использует готовый снимок.
     */
    private Catalog catalog() {
        if (catalog == null) {
            catalog = catalogSource.get();
        }
        return catalog;
    }

    /** Общая нормализация формы/подсистемы читает тот же UI snapshot; второй сборки фактов нет. */
    ExplorerTreeModel.CatalogView structureCatalog() {
        ensureCatalogContext();
        return catalog().view;
    }

    void showStructureDetail(String detail) {
        EntitySummaryPanel panel = panels.get(selectedType);
        if (panel != null) {
            panel.showStructureDetail(detail);
        }
    }

    private void ensureCatalogContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        CatalogContext current = new CatalogContext(authentication == null ? "" : authentication.getName(),
            authentication == null ? List.of() : authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).sorted().toList(), getLocale());
        if (catalogContext != null && !catalogContext.equals(current)) {
            catalog = null;
            panels.clear();
            state.clear();
            clearCard();
            resetFilters();
            groupMode.setValue(ExplorerTreeModel.GroupMode.KIND);
            filterOptionsInitialized = false;
        }
        catalogContext = current;
    }

    // ------------------------------------------------------------------ фильтры и поиск

    /** Выбранный фильтр: {@code null} в поле — «не выбрано»; условия комбинируются пересечением. */
    private ExplorerTreeModel.Filter currentFilter() {
        ExplorerSnapshot.SubsystemRef subsystem = subsystemFilter.getValue();
        return new ExplorerTreeModel.Filter(kindFilter.getValue(),
            subsystem == null ? null : subsystem.id(), exposureFilter.getValue(),
            Boolean.TRUE.equals(onlyErrorsFilter.getValue()),
            Boolean.TRUE.equals(serviceTypesFilter.getValue()));
    }

    /** Варианты фильтров — из текущего снимка; переключатель служебных типов — по инвентарю. */
    private void refreshFilterOptions() {
        if (filterOptionsInitialized) {
            return;
        }
        ExplorerTreeModel.CatalogView view = catalog().view;
        kindFilter.setItems(ExplorerTreeModel.presentKinds(view));
        subsystemFilter.setItems(ExplorerTreeModel.presentSubsystems(view));
        exposureFilter.setItems(ExplorerTreeModel.presentExposures(view));
        boolean hasServiceTypes = ExplorerTreeModel.hasServiceTypes(view);
        serviceTypesFilter.setVisible(hasServiceTypes);
        if (!hasServiceTypes) {
            serviceTypesFilter.setValue(false);
        }
        filterOptionsInitialized = true;
    }

    /** Прямой вход снимает мешающие фильтры и возвращает раскрытия к состоянию по умолчанию. */
    private void resetFilters() {
        searchField.clear();
        kindFilter.clear();
        subsystemFilter.clear();
        exposureFilter.clear();
        onlyErrorsFilter.setValue(false);
        serviceTypesFilter.setValue(false);
        resetExpansions = true;
    }

    /** Смена фильтра — другое дерево того же снимка; выбранный тип сохраняется по stable id. */
    private void rebuildTree() {
        boolean previous = applyingEntry;
        applyingEntry = true;
        try {
            allRoots = ExplorerTreeModel.roots(catalog().view, groupMode.getValue(), currentFilter());
            applyFilter();
        } finally {
            applyingEntry = previous;
        }
    }

    /**
     * Применить поиск к текущему дереву: совпадения ищутся по индексу снимка, выбор
     * восстанавливается по stable id, раскрытия — по id, а не по объектам-копиям.
     */
    private void applyFilter() {
        if (resetExpansions) {
            state.clearExpansions();
        } else if (displayedQuery.isEmpty()) {
            // Автоматическое раскрытие выдачи поиска не заменяет выбор пользователя.
            // Скрытые фильтром узлы сохраняются в памяти до возвращения в дерево.
            state.rememberExpansions(snapshotExpansions());
        }
        Map<String, Boolean> saved = state.expansions();
        resetExpansions = false;
        String query = searchField.getValue() == null
            ? "" : searchField.getValue().trim();
        List<ExplorerTreeModel.Node> visible = ExplorerTreeModel.withQuery(allRoots, catalog().view, query);
        this.visibleRoots = visible;
        TreeData<ExplorerTreeModel.Node> data = new TreeData<>();
        data.addItems(visible, ExplorerTreeModel.Node::children);
        tree.setDataProvider(new TreeDataProvider<>(data));
        restoreExpansions(saved, query);
        displayedQuery = query;
        emptyResult.setVisible(visible.isEmpty());
        if (selectedType != null) {
            syncTreeSelection(selectedType);
        }
    }

    /** Раскрытия текущего дерева по id: снимок берётся до смены данных, а не после. */
    private Map<String, Boolean> snapshotExpansions() {
        TreeData<ExplorerTreeModel.Node> data = dataOf(tree);
        if (data == null) {
            return Map.of();
        }
        Map<String, Boolean> expanded = new LinkedHashMap<>();
        collectExpanded(data.getRootItems(), expanded);
        return expanded;
    }

    private void collectExpanded(List<ExplorerTreeModel.Node> nodes, Map<String, Boolean> out) {
        for (ExplorerTreeModel.Node node : nodes) {
            if (!node.children().isEmpty()) {
                out.put(node.id(), tree.isExpanded(node));
                collectExpanded(node.children(), out);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static TreeData<ExplorerTreeModel.Node> dataOf(
            TreeGrid<ExplorerTreeModel.Node> tree) {
        DataProvider<ExplorerTreeModel.Node, ?> provider = tree.getDataProvider();
        if (!(provider instanceof TreeDataProvider<?>)) {
            return null;
        }
        return ((TreeDataProvider<ExplorerTreeModel.Node>) provider).getTreeData();
    }

    /**
     * Раскрытия после смены данных. При поиске раскрываются предки найденного — иначе результат
     * был бы скрыт; без поиска восстанавливается выбор пользователя, а новые узлы по умолчанию
     * раскрыты (так же выглядит дерево при первом входе).
     */
    private void restoreExpansions(Map<String, Boolean> saved, String query) {
        List<ExplorerTreeModel.Node> parents = new ArrayList<>();
        collectParents(visibleRoots, parents);
        if (parents.isEmpty()) {
            return;
        }
        if (!query.isEmpty()) {
            tree.expand(parents);
            return;
        }
        tree.collapse(parents);
        List<ExplorerTreeModel.Node> expand = new ArrayList<>();
        for (ExplorerTreeModel.Node parent : parents) {
            if (saved.getOrDefault(parent.id(), true)) {
                expand.add(parent);
            }
        }
        if (!expand.isEmpty()) {
            tree.expand(expand);
        }
    }

    private static void collectParents(List<ExplorerTreeModel.Node> nodes,
                                       List<ExplorerTreeModel.Node> out) {
        for (ExplorerTreeModel.Node node : nodes) {
            if (!node.children().isEmpty()) {
                out.add(node);
                collectParents(node.children(), out);
            }
        }
    }

    // ------------------------------------------------------------------ выбор и места

    /**
     * Выбор узла дерева. Группа карточку не открывает и адрес не меняет. Узел типа открывает
     * карточку типа; при смене типа host получает сохранённое место этой карточки.
     * Узел аспекта/раздела/поля фокусирует место карточки: панель сообщает host'у
     * место целиком одним сообщением — {@code <вкладка>} или {@code <вкладка>/<раздел>}.
     */
    private void onTreeSelection(ExplorerTreeModel.Node node) {
        if (node.kind() == ExplorerTreeModel.NodeKind.GROUP || node.type() == null) {
            return;
        }
        Class<?> type = node.type();
        boolean switched = !type.equals(selectedType);
        EntitySummaryPanel panel = cardFor(type);
        if (switched) {
            cardHost.removeAll();
            cardHost.add(panel);
            selectedType = type;
            state.remember(type, state.placeOf(type));
        }
        if (node.kind() == ExplorerTreeModel.NodeKind.TYPE) {
            if (switched) {
                selectionListener.accept(type, state.placeOf(type));
            }
            return;
        }
        if (node.kind() == ExplorerTreeModel.NodeKind.DIAGNOSTIC) {
            if (switched) {
                selectionListener.accept(type, state.placeOf(type));
            }
            return;
        }
        focusNodePlace(panel, node);
    }

    /** Место узла дерева у словаря карточки: вкладка либо вкладка с разделом. */
    private static void focusNodePlace(EntitySummaryPanel panel, ExplorerTreeModel.Node node) {
        if (node.kind() == ExplorerTreeModel.NodeKind.OWNED_SECTION) {
            panel.focus(new CardSection.Location(CardTab.FIELDS,
                CardSection.section("table-sections"), true, node.label()));
            return;
        }
        if (node.tabId() == null) {
            return;
        }
        String anchor = node.sectionId() == null
            ? node.tabId() : node.tabId() + "/" + node.sectionId();
        CardAnchor.of(anchor).ifPresent(place -> panel.focus(place.location()));
    }

    /**
     * Выбрать узел типа при входе по адресу: ищем по <b>видимому</b> дереву, а не по исходному
     * списку — после фильтра в данных лежат копии. Тип вне дерева — не ошибка адреса: карточку
     * открывает выбор пользователя.
     */
    private void selectType(Class<?> type, String anchor) {
        EntitySummaryPanel panel = cardFor(type);
        if (!type.equals(selectedType)) {
            cardHost.removeAll();
            cardHost.add(panel);
            selectedType = type;
        }
        state.remember(type, anchor);
        panel.focusAnchor(anchor);
        syncTreeSelection(type);
    }

    /** Переход по ссылке из карточки: та же карточка Explorer, без цепочки новых диалогов. */
    private void openFromCard(Class<?> type) {
        boolean switched = !type.equals(selectedType);
        EntitySummaryPanel panel = cardFor(type);
        if (switched) {
            cardHost.removeAll();
            cardHost.add(panel);
            selectedType = type;
            state.remember(type, state.placeOf(type));
        }
        syncTreeSelection(type);
        if (switched) {
            selectionListener.accept(type, state.placeOf(type));
        }
    }

    /**
     * Карточка типа: панель создаётся один раз из готовой сводки снимка и кешируется на UI.
     * При каждом подключении панель получает актуальные колбэки — замена полей, а не
     * добавление слушателей: у кешированной панели слушателей не прибывает.
     */
    private EntitySummaryPanel cardFor(Class<?> type) {
        EntitySummaryPanel panel = panels.get(type);
        if (panel == null) {
            panel = new EntitySummaryPanel(navigator);
            Optional<EntitySummary> summary = catalog().view.summaryOf(type);
            if (summary.isPresent()) {
                panel.show(summary.orElseThrow());
            } else {
                String reason = catalog().view.roots().stream()
                    .filter(entry -> entry.type().equals(type))
                    .map(ExplorerSnapshot.Entry::failureReason).findFirst()
                    .filter(value -> !value.isBlank()).orElse("Тип отсутствует в каталоге");
                panel.showUnavailable(reason);
            }
            panels.put(type, panel);
        }
        panel.setStructureNavigator(this::openFromCard);
        panel.setPlaceListener(place -> {
            if (type.equals(selectedType) && !applyingEntry) {
                state.remember(type, place);
                selectionListener.accept(type, place);
            }
        });
        return panel;
    }

    /** Убрать видимую карточку; кеш панелей сохраняет их состояние для повторного выбора типа. */
    private void clearCard() {
        cardHost.removeAll();
        selectedType = null;
    }

    /** Тип есть в текущем снимке: сохранённый выбор восстанавливается только тогда. */
    private boolean selectableType(Class<?> type) {
        return catalog().view.roots().stream().anyMatch(entry -> entry.type().equals(type));
    }

    /** Сохранённый тип исчез из снимка: причина называется, дерево остаётся доступным. */
    private void showRestoreFailure() {
        Span message = new Span(
            "Сохранённый выбор недоступен: тип отсутствует в текущем каталоге.");
        message.getStyle().set("color", "var(--lumo-secondary-text-color)");
        cardHost.add(message);
    }

    /** Синхронизация выбора дерева с карточкой по стабильному id: выбираем узел типа. */
    private void syncTreeSelection(Class<?> type) {
        ExplorerTreeModel.Node match = findTypeNode(visibleRoots, type);
        boolean previous = applyingEntry;
        applyingEntry = true;
        try {
            if (match != null) {
                tree.select(match);
            } else {
                tree.deselectAll();
            }
        } finally {
            applyingEntry = previous;
        }
    }

    private static ExplorerTreeModel.Node findTypeNode(List<ExplorerTreeModel.Node> nodes,
                                                       Class<?> type) {
        for (ExplorerTreeModel.Node node : nodes) {
            if (node.kind() == ExplorerTreeModel.NodeKind.TYPE && type.equals(node.type())) {
                return node;
            }
            ExplorerTreeModel.Node child = findTypeNode(node.children(), type);
            if (child != null) {
                return child;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ диагностики

    /**
     * Общий список неадресованных диагностик (E3.1): записи стартового скана, которым не нашлось
     * карточки. Список не зависит от выбранного типа — иначе такая запись исчезала бы вместе с
     * очисткой карточки, а очистка карточки не меняет конфигурацию.
     *
     * <p>Пустой список раздел не рисует: «записей нет» и «раздел не открыт» — разные состояния,
     * и второе не выдаётся за первое. Свёрнут по умолчанию: это справка о каталоге, а не
     * содержимое карточки.</p>
     */
    private void addUnassignedDiagnostics(List<EntitySummary.DiagnosticRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        InMemoryFilterGrid<EntitySummary.DiagnosticRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.DiagnosticRow.class, rows);
        grid.addColumn("severity", "Уровень", r -> EntitySummaryPanel.severityLabel(r.severity()))
            .setResizable(true).setWidth("140px").setFlexGrow(0);
        grid.addColumn("code", "Код", EntitySummary.DiagnosticRow::code)
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        grid.addColumn("address", "Адрес", EntitySummaryPanel::diagnosticAddress)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("value", "Текст", r -> r.value().value())
            .setResizable(true).setFlexGrow(2);
        grid.setWidthFull();
        grid.setHeight(null);
        grid.setCompact(true);
        grid.getGrid().setAllRowsVisible(true);
        grid.build();

        H4 title = new H4("Неадресованные диагностики (" + rows.size() + ")");
        title.getStyle().set("margin", "0");
        Details details = new Details(title, grid);
        details.setOpened(false);
        details.setWidthFull();
        add(details);
    }
}

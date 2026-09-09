package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.inmemory.InMemoryFilterGrid;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.form.registry.FormType;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.explorer.EntitySummary;
import org.ipro.metadata.facet.FactSource;

import java.util.List;
import java.util.function.Consumer;

/**
 * Read-only панель сводки одной сущности — общий рендер {@link EntitySummary}: разделы
 * «Обзор», «Поля (форма/грид)», «Колонки», «Табличные части», «Формы и варианты»,
 * «Контекст-фильтры», «Selection», «Обратные ссылки», «Нумерация».
 *
 * <p>Каждый раздел — сворачиваемый {@link Details} (открыт по умолчанию). Гриды — встроенные
 * {@link InMemoryFilterGrid} (строки — in-memory records), все колонки изменяемые по размеру;
 * на текстовых колонках включены обычные фильтры — {@link org.ipro.filtergrid.TextFilter}
 * в строке фильтров под заголовками колонок (без popup-фильтров «⋮»).</p>
 *
 * <p>Используется в двух местах: вкладка одной сущности в {@link EntityExplorerView}
 * и диалог «Структура сущности» из {@link SubsystemStructureView}. Панель ничего не пишет
 * и не резолвит — потребляет готовый агрегат.</p>
 *
 * <p>Навигация наружу (роль «куда ведёт эта грань»): хост задаёт {@link #setStructureNavigator}
 * — колбэк «открыть структуру сущности». Когда он задан, в колонке Lookup и в разделе
 * «Обратные ссылки» появляются маленькие кнопки перехода. Хост решает, открыть новую вкладку
 * или перерисовать диалог.</p>
 */
public class EntitySummaryPanel extends VerticalLayout {

    private final FormCoordinator coordinator;
    private Consumer<Class<?>> structureNavigator;

    public EntitySummaryPanel(FormCoordinator coordinator) {
        this.coordinator = coordinator;
        setSizeFull();
        setPadding(false);
        setSpacing(true);
        showPlaceholder();
    }

    /** Колбэк «открыть структуру сущности» — включается навигация из Lookup/ссылок. */
    public void setStructureNavigator(Consumer<Class<?>> structureNavigator) {
        this.structureNavigator = structureNavigator;
    }

    public void showPlaceholder() {
        removeAll();
        Span empty = new Span("Выберите сущность слева — здесь появится сводка её конфигурации.");
        empty.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(empty);
    }

    /** Полная перерисовка сводки: заголовок не рисует (его показывает хост). */
    public void show(EntitySummary summary) {
        removeAll();
        addOverview(summary);
        addFieldSection("Поля — форма", summary.fieldsForm());
        addFieldSection("Поля — грид", summary.fieldsGrid());
        addColumnSection("Колонки списка по умолчанию", summary.listColumns());
        addColumnSection("Колонки выбора (selectColumns)", summary.selectColumns());
        addSectionSection(summary.tableSections());
        addFormSection(summary.forms());
        addFilterSection(summary.contextFilters());
        addSelectionSection(summary.selections());
        addReferenceSection(summary.references());
        addNumberingSection(summary.numbering());
    }

    // ---------------------------------------------------------------- разделы

    private void addOverview(EntitySummary summary) {
        if (summary.overview().isEmpty()) {
            return;
        }
        List<EntitySummary.OverviewRow> rows = summary.overview();
        InMemoryFilterGrid<EntitySummary.OverviewRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.OverviewRow.class, rows);
        grid.addColumn("caption", "Параметр", EntitySummary.OverviewRow::caption)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("value", "Значение", r -> text(r.value().value()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("source", "Источник", r -> sourceCell(r))
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        textColumnFilter(grid, "caption", "caption");
        textColumnFilter(grid, "value", "value.value");
        addSection(this, "Обзор", null, grid);
    }

    private void addFieldSection(String name, List<EntitySummary.FieldRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        InMemoryFilterGrid<EntitySummary.FieldRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.FieldRow.class, rows);
        grid.addColumn("name", "Поле", EntitySummary.FieldRow::name)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("value", "Заголовок", r -> text(r.value().value()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("required", "Обязат.", r -> yesNo(r.required()))
            .setResizable(true).setWidth("90px").setFlexGrow(0);
        grid.addColumn("readOnly", "Только чтение", r -> yesNo(r.readOnly()))
            .setResizable(true).setWidth("130px").setFlexGrow(0);
        grid.addComponentColumn("lookup", "Lookup", this::lookupCell)
            .setResizable(true).setWidth("220px").setFlexGrow(0);
        grid.addColumn("source", "Источник", r -> sourceCell(r))
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        textColumnFilter(grid, "name", "name");
        textColumnFilter(grid, "value", "value.value");
        addSection(this, name, null, grid);
    }

    private void addColumnSection(String name, List<EntitySummary.ColumnRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        InMemoryFilterGrid<EntitySummary.ColumnRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.ColumnRow.class, rows);
        grid.addColumn("path", "Путь", EntitySummary.ColumnRow::path)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("value", "Заголовок", r -> text(r.value().value()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("nested", "Через точку", r -> yesNo(r.nested()))
            .setResizable(true).setWidth("120px").setFlexGrow(0);
        grid.addColumn("source", "Источник", r -> sourceCell(r))
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        textColumnFilter(grid, "path", "path");
        textColumnFilter(grid, "value", "value.value");
        addSection(this, name, null, grid);
    }

    private void addSectionSection(List<EntitySummary.SectionRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        InMemoryFilterGrid<EntitySummary.SectionRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.SectionRow.class, rows);
        grid.addColumn("rowClass", "Строка", EntitySummary.SectionRow::rowClass)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("value", "Заголовок", r -> text(r.value().value()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("order", "Порядок", r -> String.valueOf(r.order()))
            .setResizable(true).setWidth("90px").setFlexGrow(0);
        grid.addColumn("minRows", "Мин. строк", r -> String.valueOf(r.minRows()))
            .setResizable(true).setWidth("100px").setFlexGrow(0);
        grid.addColumn("formFieldCount", "Поля формы", r -> String.valueOf(r.formFieldCount()))
            .setResizable(true).setWidth("110px").setFlexGrow(0);
        grid.addColumn("gridFieldCount", "Поля грида", r -> String.valueOf(r.gridFieldCount()))
            .setResizable(true).setWidth("110px").setFlexGrow(0);
        textColumnFilter(grid, "rowClass", "rowClass");
        addSection(this, "Табличные части",
            "структура — только код, не переопределяется", grid);
    }

    private void addFormSection(List<EntitySummary.FormRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        InMemoryFilterGrid<EntitySummary.FormRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.FormRow.class, rows);
        grid.addColumn("formType", "Тип формы", r -> formTypeLabel(r.formType()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("variant", "Вариант", r -> text(r.variant()))
            .setResizable(true).setWidth("150px").setFlexGrow(0);
        grid.addColumn("registration", "Регистрация", r -> text(r.registrationKind()))
            .setResizable(true).setWidth("200px").setFlexGrow(0);
        grid.addColumn("source", "Источник", r -> r.platformDefault()
            ? "авто (платформа)" : text(r.source()))
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        textColumnFilter(grid, "variant", "variant");
        textColumnFilter(grid, "registration", "registrationKind");
        addSection(this, "Формы и варианты", null, grid);
    }

    private void addFilterSection(List<EntitySummary.FilterRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        InMemoryFilterGrid<EntitySummary.FilterRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.FilterRow.class, rows);
        grid.addColumn("scope", "Область", EntitySummary.FilterRow::scope)
            .setResizable(true).setWidth("200px").setFlexGrow(0);
        grid.addColumn("path", "Поле", EntitySummary.FilterRow::path)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("value", "Подпись", r -> text(r.value().value()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("control", "Контрол", r -> text(r.control()))
            .setResizable(true).setWidth("100px").setFlexGrow(0);
        grid.addColumn("required", "Обязат.", r -> yesNo(r.required()))
            .setResizable(true).setWidth("90px").setFlexGrow(0);
        grid.addColumn("allListVariants", "Все списки", r -> yesNo(r.allListVariants()))
            .setResizable(true).setWidth("110px").setFlexGrow(0);
        grid.addColumn("source", "Источник",
            r -> r.source().isBlank() ? "код" : r.source())
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        textColumnFilter(grid, "scope", "scope");
        textColumnFilter(grid, "path", "path");
        textColumnFilter(grid, "control", "control");
        addSection(this, "Контекст-фильтры", null, grid);
    }

    private void addSelectionSection(List<EntitySummary.SelectionRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        InMemoryFilterGrid<EntitySummary.SelectionRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.SelectionRow.class, rows);
        grid.addColumn("variant", "Вариант", r -> text(r.variant()))
            .setResizable(true).setWidth("160px").setFlexGrow(0);
        grid.addColumn("value", "Заголовок", r -> text(r.value().value()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("columns", "Колонки", r -> String.join(", ", r.columns()))
            .setResizable(true).setFlexGrow(1);
        textColumnFilter(grid, "variant", "variant");
        addSection(this, "Наборы выбора (Selection)", null, grid);
    }

    private void addReferenceSection(List<EntitySummary.ReferenceRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        InMemoryFilterGrid<EntitySummary.ReferenceRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.ReferenceRow.class, rows);
        grid.addColumn("referencing", "Ссылающаяся сущность",
            r -> r.referencingClass().getSimpleName())
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("field", "Поле", EntitySummary.ReferenceRow::fieldName)
            .setResizable(true).setWidth("200px").setFlexGrow(0);
        grid.addColumn("columnRef", "Вид ссылки", r -> r.columnRef() ? "колонка" : "поле")
            .setResizable(true).setWidth("110px").setFlexGrow(0);
        grid.addComponentColumn("action", "Действия", this::referenceAction)
            .setResizable(true).setWidth("200px").setFlexGrow(0);
        textColumnFilter(grid, "field", "fieldName");
        addSection(this, "Обратные ссылки («где используется»)",
            "структура — только код, не переопределяется. Пути без счётчиков (RLS-контекст)",
            grid);
    }

    private void addNumberingSection(List<EntitySummary.NumberingRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        InMemoryFilterGrid<EntitySummary.NumberingRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.NumberingRow.class, rows);
        grid.addColumn("field", "Поле", EntitySummary.NumberingRow::fieldName)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("scope", "Scope", EntitySummary.NumberingRow::scope)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("period", "Период", EntitySummary.NumberingRow::period)
            .setResizable(true).setWidth("120px").setFlexGrow(0);
        grid.addColumn("manual", "Ручной ввод", r -> yesNo(r.manualAllowed()))
            .setResizable(true).setWidth("120px").setFlexGrow(0);
        textColumnFilter(grid, "field", "fieldName");
        textColumnFilter(grid, "scope", "scope");
        addSection(this, "Нумерация", "структура — только код, не переопределяется", grid);
    }

    // ---------------------------------------------------------------- ячейки с навигацией

    /** Lookup: текст + маленькая кнопка «открыть структуру цели», если цель известна. */
    private Component lookupCell(EntitySummary.FieldRow row) {
        if (row.lookupEntityClass() == null || structureNavigator == null) {
            return new Span(text(row.lookupTarget()));
        }
        HorizontalLayout layout = new HorizontalLayout(
            new Span(text(row.lookupTarget())), lookupAction(row.lookupEntityClass()));
        layout.setSpacing(true);
        layout.setAlignItems(FlexComponent.Alignment.CENTER);
        return layout;
    }

    private Button lookupAction(Class<?> entityClass) {
        Button open = new Button(new Icon(VaadinIcon.EXTERNAL_LINK));
        open.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        open.addClickListener(e -> structureNavigator.accept(entityClass));
        return open;
    }

    /** Обратная ссылка: «структура» (если задан навигатор) + «открыть список». */
    private Component referenceAction(EntitySummary.ReferenceRow row) {
        boolean entity = row.referencingClass().getAnnotation(EntityMetadata.class) != null;
        HorizontalLayout layout = new HorizontalLayout();
        layout.setSpacing(true);
        layout.setAlignItems(FlexComponent.Alignment.CENTER);
        if (!entity) {
            Span span = new Span("—");
            span.getStyle().set("color", "var(--lumo-tertiary-text-color)");
            layout.add(span);
            return layout;
        }
        if (structureNavigator != null) {
            Button structure = new Button("структура", new Icon(VaadinIcon.EXTERNAL_LINK));
            structure.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            structure.addClickListener(e -> structureNavigator.accept(row.referencingClass()));
            layout.add(structure);
        }
        Button open = new Button("открыть список", new Icon(VaadinIcon.OPEN_BOOK));
        open.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        open.addClickListener(e -> navigateTo(row.referencingClass()));
        layout.add(open);
        return layout;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void navigateTo(Class<?> entityClass) {
        coordinator.openListForm((Class) entityClass, null, null);
    }

    // ---------------------------------------------------------------- помощники

    /**
     * Секция — сворачиваемый {@link Details}: заголовок (имя + необязательная пометка),
     * содержимое — FilterGrid с авто-высотой. Открыта по умолчанию.
     */
    private void addSection(VerticalLayout parent, String name, String note, InMemoryFilterGrid<?> grid) {
        HorizontalLayout headerRow = new HorizontalLayout();
        headerRow.setAlignItems(FlexComponent.Alignment.CENTER);
        headerRow.setSpacing(true);
        H4 title = new H4(name);
        title.getStyle().set("margin", "0");
        headerRow.add(title);
        if (note != null && !note.isEmpty()) {
            Span noteSpan = new Span(note);
            noteSpan.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
            headerRow.add(noteSpan);
        }

        grid.setWidthFull();
        grid.setHeight(null); // FilterGrid-обёртка: ширина полная, высота — по строкам
        grid.setCompact(true);
        grid.getGrid().setAllRowsVisible(true);
        // build() обязателен: размещает компоненты обычных фильтров (TextFilter)
        // в строке фильтров под заголовками (см. FilterGrid.build()).
        grid.build();

        Details details = new Details(headerRow, grid);
        details.setOpened(true);
        details.setWidthFull();
        parent.add(details);
    }

    /** Обычный фильтр колонки: {@link TextFilter} в строке фильтров под заголовком. */
    private static <T> void textColumnFilter(InMemoryFilterGrid<T> grid, String columnKey, String fieldPath) {
        grid.addFilter(columnKey, fieldPath, new TextFilter<>());
    }

    /**
     * Источник FacetRow-факта: «переопределение» (роль 3) или путь к .java-файлу
     * сущности-декларанта (поля/колонки/обзор объявляются на классе сущности).
     * Возвращает строку, а не компонент: колонка текстовая, иначе грид рендерит
     * {@code toString()} компонента (например {@code Span@...}).
     */
    private static String sourceCell(EntitySummary.FacetRow row) {
        if (row.value().source() == FactSource.OVERRIDE) {
            return "переопределение";
        }
        return toSourcePath(row.key().entityClass());
    }

    /** FQN класса → путь к .java-файлу (src/main/java/org/ip/model/X.java → org/ip/model/X.java). */
    private static String toSourcePath(Class<?> cls) {
        return cls.getName().replace('.', '/') + ".java";
    }

    private static String text(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    private static String yesNo(boolean value) {
        return value ? "да" : "";
    }

    private static String formTypeLabel(FormType type) {
        return switch (type) {
            case LIST -> "Список";
            case ITEM -> "Элемент";
            case SELECTION -> "Выбор";
        };
    }
}
package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ScrollOptions;
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
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.inmemory.InMemoryFilterGrid;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.form.registry.FormType;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.MetadataDiagnostic;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.facet.FactSource;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.vaadin.explorer.EntitySummary;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Read-only панель сводки одной сущности — общий рендер {@link EntitySummary}: вкладки аспектов
 * (обзор, поля и колонки, формы и действия, чтение, доступ, связи, поведение), разделы — по
 * словарю {@link CardSection}. Словарь один решает состав, порядок и источник строк каждого
 * раздела; вкладка и раздел без строк не рисуются, а переключение вкладки только показывает уже
 * прочитанную сводку — панель ничего не перечитывает.
 *
 * <p>Каждый раздел — сворачиваемый {@link Details} (открыт по умолчанию). Гриды — встроенные
 * {@link InMemoryFilterGrid} (строки — in-memory records), все колонки изменяемые по размеру;
 * на текстовых колонках включены обычные фильтры — {@link org.ipro.filtergrid.TextFilter}
 * в строке фильтров под заголовками колонок (без popup-фильтров «⋮»).</p>
 *
 * <p><b>Колонка «Источник» — происхождение, а не путь к файлу (E3.1).</b> До E3.1 колонка
 * печатала {@code путь к .java-файлу класса сущности} для каждой строки обзора, полей и колонок —
 * то есть заявляла точность, которой у факта нет: «Экспозиция» приходит из каталога дескрипторов,
 * «Ссылка item» — из каталога маршрутов, а не из файла сущности. Теперь ячейка собирается из
 * трёх раздельных признаков {@link ResolvedValue}: слой действующего значения, происхождение
 * кодового значения и Java-символ места. Пустое место остаётся пустым: путь не выводится из
 * класса сущности и не подставляется вместо символа.</p>
 *
 * <p>Используется в двух местах: единственная карточка выбранного типа в
 * {@link EntityExplorerView} (кешируется на UI и переподключается к актуальным колбэкам,
 * E3.2.2 §9.2) и диалог «Структура сущности» из {@link SubsystemStructureView}. Панель ничего
 * не пишет и не резолвит — потребляет готовый агрегат.</p>
 *
 * <p>E3.2.1 §8.2: место карточки может быть названо якорем адреса — {@code ?view=<вкладка>} или
 * {@code ?view=<вкладка>/<раздел>}. Якорь применяется после отрисовки (вести можно только туда,
 * что на экране), а смену вкладки пользователем панель сообщает host'у тем же якорем: адрес
 * собирает host — у карточки типа своего адреса нет.</p>
 *
 * <p>Навигация наружу (роль «куда ведёт эта грань»): хост задаёт {@link #setStructureNavigator}
 * — колбэк «открыть структуру сущности». Когда он задан, в колонке Lookup, в разделе «Связи»
 * и в разделе «Обратные ссылки» появляются маленькие кнопки перехода. Хост решает, открыть новую
 * вкладку или перерисовать диалог.</p>
 */
public class EntitySummaryPanel extends VerticalLayout {

    private final FormNavigator navigator;
    private Consumer<Class<?>> structureNavigator;

    /** Нарисованная сводка: нарисованные вкладки и разделы — адреса для переходов из обзора. */
    private TabSheet tabs;
    private EntitySummary summary;
    private final Map<CardTab, Tab> drawnTabs = new EnumMap<>(CardTab.class);
    private final Map<CardSection<?>, Details> drawnSections = new IdentityHashMap<>();

    /** Кто сообщает host'у, что место карточки сменил пользователь (E3.2.1 §8.2). */
    private Consumer<String> placeListener = anchor -> {
    };

    /** Идёт программная установка места: пользователь место не выбирал, и сообщать host'у нечего. */
    private boolean applyingPlace;
    private Component placeStatus;

    public EntitySummaryPanel(FormNavigator navigator) {
        this.navigator = navigator;
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
        placeStatus = null;
        tabs = null;
        summary = null;
        drawnTabs.clear();
        drawnSections.clear();
        Span empty = new Span("Выберите сущность слева — здесь появится сводка её конфигурации.");
        empty.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(empty);
    }

    void showUnavailable(String reason) {
        showPlaceholder();
        removeAll();
        add(new Span("Сводка недоступна: " + reason));
    }

    /**
     * Полная перерисовка сводки: заголовок не рисует (его показывает хост). Вкладки и их состав
     * строятся по словарю {@link CardSection}: порядок и разбиение заданы там, а не порядком
     * вызовов, и пустая вкладка не рисуется — «раздела нет» и «раздел пуст» читаются одинаково.
     */
    public void show(EntitySummary summary) {
        show(summary, null);
    }

    /**
     * Та же перерисовка плюс якорь адреса (E3.2.1 §8.2): адрес называет место карточки, и после
     * отрисовки карточка его открывает. Якорь применяется до подписки на смену вкладки: выбор,
     * сделанный адресом, — не выбор пользователя, и адрес уже стоит в окне.
     */
    public void show(EntitySummary summary, String anchor) {
        removeAll();
        placeStatus = null;
        tabs = null;
        this.summary = summary;
        drawnTabs.clear();
        drawnSections.clear();
        CardSection.Context context =
            new CardSection.Context(summary, lookupByField(summary.lookupTargets()));
        TabSheet sheet = new TabSheet();
        sheet.setSizeFull();
        for (CardTab tab : CardTab.values()) {
            VerticalLayout content = tabContent();
            for (CardSection<?> section : CardSection.ALL) {
                if (section.tab() == tab) {
                    Details drawn = section.draw(this, content, summary, context);
                    if (drawn != null) {
                        drawnSections.put(section, drawn);
                    }
                }
            }
            if (content.getComponentCount() > 0) {
                drawnTabs.put(tab, sheet.add(tab.title(), content));
            }
        }
        if (sheet.getTabCount() > 0) {
            tabs = sheet;
            add(sheet);
        }
        focusAnchor(anchor);
        sheet.addSelectedChangeListener(event -> placeChosen(tabOf(event.getSelectedTab())));
    }

    /** Содержимое вкладки: прокручивает сама {@link TabSheet} — её область содержимого скроллер. */
    private static VerticalLayout tabContent() {
        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();
        return content;
    }

    // ---------------------------------------------------------------- разделы

    Details addOverview(VerticalLayout host, String title, List<EntitySummary.OverviewRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.OverviewRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.OverviewRow.class, rows);
        grid.addColumn("caption", "Параметр", EntitySummary.OverviewRow::caption)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("value", "Значение", r -> text(r.value().value()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("source", "Источник", r -> sourceText(r.value()))
            .setResizable(true).setWidth("300px").setFlexGrow(0);
        grid.addColumn("note", "Примечание", r -> r.detail())
            .setResizable(true).setWidth("220px").setFlexGrow(0);
        textColumnFilter(grid, "caption", "caption");
        textColumnFilter(grid, "value", "value.value");
        textColumnFilter(grid, "note", "detail");
        return addSection(host, title, null, grid);
    }

    /**
     * Раздел «Диагностика» — записи стартового скана, адресованные этой сущности (E3.1).
     * Адрес диагностики (`entityFqn#field`) показывается как есть: он пришёл из валидатора, а не
     * выведен из карточки. <b>Подпись {@code caption} не рисуется отдельной колонкой</b> — она
     * составлена из уровня и кода, которые в той же строке уже есть; второй вид тех же данных
     * читался бы как второй факт.
     */
    Details addDiagnosticSection(VerticalLayout host, String title, String note,
                                 List<EntitySummary.DiagnosticRow> rows,
                                 CardSection.Context context) {
        if (rows.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.DiagnosticRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.DiagnosticRow.class, rows);
        grid.addColumn("severity", "Уровень", r -> severityLabel(r.severity()))
            .setResizable(true).setWidth("140px").setFlexGrow(0);
        grid.addColumn("code", "Код", EntitySummary.DiagnosticRow::code)
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        grid.addColumn("address", "Адрес", EntitySummaryPanel::diagnosticAddress)
            .setResizable(true).setFlexGrow(1);
        grid.addComponentColumn("where", "Где",
                row -> diagnosticWhereCell(context.summary(), row))
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        grid.addColumn("value", "Текст", r -> text(r.value().value()))
            .setResizable(true).setFlexGrow(2);
        grid.addColumn("source", "Источник", r -> sourceText(r.value().source(),
            r.value().origin(), r.value().symbol().isBlank() ? r.source() : r.value().symbol()))
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        textColumnFilter(grid, "code", "code");
        textColumnFilter(grid, "address", "entityFqn");
        return addSection(host, title, note, grid);
    }

    /**
     * Строки аспекта связи по имени поля. Цель показывается <b>одним источником</b>: строка поля её
     * больше не несёт, поэтому карточка и реестр метаданных не могут разойтись (§E3.2.0 шаг 4).
     */
    private static Map<String, EntitySummary.LookupRow> lookupByField(
            List<EntitySummary.LookupRow> rows) {
        Map<String, EntitySummary.LookupRow> byField = new HashMap<>();
        for (EntitySummary.LookupRow row : rows) {
            byField.put(row.fieldName(), row);
        }
        return byField;
    }

    Details addFieldSection(VerticalLayout host, String title, List<EntitySummary.FieldRow> rows,
                            Map<String, EntitySummary.LookupRow> lookupByField) {
        if (rows.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.FieldRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.FieldRow.class, rows);
        grid.addColumn("name", "Поле", EntitySummary.FieldRow::name)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("type", "Тип", EntitySummaryPanel::fieldTypeCell)
            .setResizable(true).setWidth("180px").setFlexGrow(0);
        grid.addColumn("value", "Заголовок", r -> text(r.value().value()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("required", "Обязат.", EntitySummaryPanel::requiredCell)
            .setResizable(true).setWidth("130px").setFlexGrow(0);
        grid.addColumn("readOnly", "Только чтение", r -> yesNo(r.readOnly()))
            .setResizable(true).setWidth("130px").setFlexGrow(0);
        grid.addComponentColumn("lookup", "Lookup",
                row -> lookupCell(lookupByField.get(row.name())))
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        grid.addColumn("source", "Источник", r -> sourceText(r.value()))
            .setResizable(true).setWidth("300px").setFlexGrow(0);
        textColumnFilter(grid, "name", "name");
        textColumnFilter(grid, "value", "value.value");
        return addSection(host, title, null, grid);
    }

    Details addColumnSection(VerticalLayout host, String title,
                             List<EntitySummary.ColumnRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.ColumnRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.ColumnRow.class, rows);
        grid.addColumn("path", "Путь", EntitySummary.ColumnRow::path)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("value", "Заголовок", r -> text(r.value().value()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("nested", "Через точку", r -> yesNo(r.nested()))
            .setResizable(true).setWidth("120px").setFlexGrow(0);
        grid.addColumn("source", "Источник", r -> sourceText(r.value()))
            .setResizable(true).setWidth("300px").setFlexGrow(0);
        grid.addColumn("note", "Примечание", r -> r.note())
            .setResizable(true).setWidth("220px").setFlexGrow(0);
        textColumnFilter(grid, "path", "path");
        textColumnFilter(grid, "value", "value.value");
        textColumnFilter(grid, "note", "note");
        return addSection(host, title, null, grid);
    }

    Details addSectionSection(VerticalLayout host, String title, String note,
                              List<EntitySummary.SectionRow> rows) {
        if (rows.isEmpty()) {
            return null;
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
        return addSection(host, title, note, grid);
    }

    Details addFormSection(VerticalLayout host, String title, List<EntitySummary.FormRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.FormRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.FormRow.class, rows);
        grid.addColumn("formType", "Тип формы", r -> formTypeLabel(r.formType()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("variant", "Вариант", r -> text(r.variant()))
            .setResizable(true).setWidth("150px").setFlexGrow(0);
        grid.addColumn("registration", "Регистрация", r -> text(r.registrationKind()))
            .setResizable(true).setWidth("200px").setFlexGrow(0);
        grid.addColumn("source", "Источник", EntitySummaryPanel::formSourceText)
            .setResizable(true).setWidth("300px").setFlexGrow(0);
        textColumnFilter(grid, "variant", "variant");
        textColumnFilter(grid, "registration", "registrationKind");
        return addSection(host, title, null, grid);
    }

    // ---------------------------------------------------------------- действия (E3.2.0 шаг 5)

    /**
     * Раздел «Действия» — объявления действий по поверхностям и вариантам формы. Присутствие
     * строки и есть регистрация: подавленное действие отличается от отсутствующего, а причина —
     * в колонке «Примечание» текстом владельца факта (политика приложения, а не карточка).
     */
    Details addActionSection(VerticalLayout host, String title, String note,
                             List<EntitySummary.ActionRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.ActionRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.ActionRow.class, rows);
        grid.addColumn("surface", "Поверхность", EntitySummaryPanel::actionSurfaceLabel)
            .setResizable(true).setWidth("180px").setFlexGrow(0);
        grid.addColumn("variant", "Вариант", EntitySummaryPanel::actionVariantCell)
            .setResizable(true).setWidth("140px").setFlexGrow(0);
        grid.addColumn("actionId", "Действие", EntitySummary.ActionRow::actionId)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("title", "Заголовок", r -> text(r.title()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("order", "Порядок", r -> String.valueOf(r.order()))
            .setResizable(true).setWidth("100px").setFlexGrow(0);
        grid.addColumn("visible", "Предлагается", r -> yesNo(r.visible()))
            .setResizable(true).setWidth("130px").setFlexGrow(0);
        grid.addColumn("source", "Источник", r -> sourceText(r.value()))
            .setResizable(true).setWidth("320px").setFlexGrow(0);
        grid.addColumn("note", "Примечание", EntitySummary.ActionRow::note)
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        textColumnFilter(grid, "actionId", "actionId");
        textColumnFilter(grid, "title", "title");
        return addSection(host, title, note, grid);
    }

    /**
     * Раздел «Действия — исполнители» — найден ли handler для объявления. Факт строки — текст
     * владельца («найден» / «не найден»), а не пересказ карточки: определение без handler'а
     * отличается от действия без объявления (§6 п.1 плана среза).
     */
    Details addActionExecutorSection(VerticalLayout host, String title, String note,
                                     List<EntitySummary.ActionRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.ActionRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.ActionRow.class, rows);
        grid.addColumn("surface", "Поверхность", EntitySummaryPanel::actionSurfaceLabel)
            .setResizable(true).setWidth("180px").setFlexGrow(0);
        grid.addColumn("variant", "Вариант", EntitySummaryPanel::actionVariantCell)
            .setResizable(true).setWidth("140px").setFlexGrow(0);
        grid.addColumn("actionId", "Действие", EntitySummary.ActionRow::actionId)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("executor", "Исполнитель", EntitySummaryPanel::executorCell)
            .setResizable(true).setWidth("160px").setFlexGrow(0);
        grid.addColumn("source", "Источник", r -> sourceText(r.value()))
            .setResizable(true).setWidth("320px").setFlexGrow(0);
        grid.addColumn("note", "Примечание", EntitySummary.ActionRow::note)
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        textColumnFilter(grid, "actionId", "actionId");
        return addSection(host, title, note, grid);
    }

    // ------------------------------------------------------ сценарии чтения (E3.2.0 шаг 5)

    /**
     * Раздел «Сценарии чтения» — набор сценариев типа и допуск canonical path. Допущенный сценарий
     * с пустым планом читается счётчиком (0), а не пустой ячейкой: «путей нет» — факт плана, а
     * «сценарий не допущен» — другой факт, и он виден колонкой «Допущен».
     */
    Details addReadPlanSection(VerticalLayout host, String title, String note,
                               List<EntitySummary.ReadPlanRow> rows) {
        if (!summary.readPlanInspectionAvailable()) {
            return addSection(host, title, note,
                new Span("Инспекция планов чтения не подключена; число путей неизвестно."));
        }
        if (rows.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.ReadPlanRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.ReadPlanRow.class, rows);
        grid.addColumn("scenario", "Сценарий", EntitySummary.ReadPlanRow::scenario)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("allowed", "Допущен", EntitySummaryPanel::readPlanAdmissionCell)
            .setResizable(true).setWidth("160px").setFlexGrow(0);
        grid.addColumn("pathCount", "Путей", EntitySummaryPanel::readPlanPathCountCell)
            .setResizable(true).setWidth("90px").setFlexGrow(0);
        grid.addColumn("source", "Источник", r -> sourceText(r.value()))
            .setResizable(true).setWidth("320px").setFlexGrow(0);
        grid.addColumn("note", "Примечание", EntitySummary.ReadPlanRow::note)
            .setResizable(true).setWidth("360px").setFlexGrow(0);
        textColumnFilter(grid, "scenario", "scenario");
        textColumnFilter(grid, "note", "note");
        return addSection(host, title, note, grid);
    }

    /**
     * Раздел «Сценарии чтения — пути» — путь плана сценария и причина, по которой он в плане.
     * Причина показывается как её дал владелец ({@code metadata:<SCENARIO>},
     * {@code lookup:<Owner.field>}…): пересказ карточки стёр бы, кто именно добавил путь.
     */
    Details addReadPlanPathSection(VerticalLayout host, String title, String note,
                                   List<EntitySummary.PathRow> paths) {
        if (!summary.readPlanInspectionAvailable()) {
            return addSection(host, title, note,
                new Span("Инспекция планов чтения не подключена; число путей неизвестно."));
        }
        if (paths.isEmpty()) {
            VerticalLayout emptyPlans = new VerticalLayout();
            emptyPlans.setPadding(false);
            for (EntitySummary.ReadPlanRow plan : summary.readPlans()) {
                String state = plan.scenario() + ": 0 путей загрузки";
                if (!plan.note().isBlank()) {
                    state += " · " + plan.note();
                }
                emptyPlans.add(new Span(state));
            }
            return emptyPlans.getComponentCount() == 0 ? null
                : addSection(host, title, note, emptyPlans);
        }
        InMemoryFilterGrid<EntitySummary.PathRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.PathRow.class, paths);
        grid.addColumn("scenario", "Сценарий", EntitySummary.PathRow::scenario)
            .setResizable(true).setWidth("160px").setFlexGrow(0);
        grid.addColumn("path", "Путь", EntitySummary.PathRow::attributePath)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("reason", "Причина", EntitySummaryPanel::pathReasonCell)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("source", "Источник", r -> sourceText(r.value()))
            .setResizable(true).setWidth("320px").setFlexGrow(0);
        textColumnFilter(grid, "path", "attributePath");
        textColumnFilter(grid, "reason", "reason");
        return addSection(host, title, note, grid);
    }

    // ------------------------------------------------------------------- доступ (E3.2.0 шаг 5)

    /**
     * Раздел «Доступ» — измерения RLS типа: род измерения, служит ли оно каталогом грантов и где
     * объявлено. Значения грантов и решения по конкретным записям в раздел не попадают: это данные
     * пользователей, а не конфигурация типа, и карточка их не знает.
     */
    Details addAccessSection(VerticalLayout host, String title, String note,
                             List<EntitySummary.AccessRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.AccessRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.AccessRow.class, rows);
        grid.addColumn("dimension", "Измерение", EntitySummary.AccessRow::dimension)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("kind", "Род", EntitySummaryPanel::accessKindLabel)
            .setResizable(true).setWidth("150px").setFlexGrow(0);
        grid.addColumn("grants", "Гранты", EntitySummaryPanel::grantCatalogCell)
            .setResizable(true).setWidth("100px").setFlexGrow(0);
        grid.addColumn("source", "Источник", r -> sourceText(r.value()))
            .setResizable(true).setWidth("320px").setFlexGrow(0);
        grid.addColumn("note", "Примечание", EntitySummary.AccessRow::note)
            .setResizable(true).setWidth("420px").setFlexGrow(0);
        textColumnFilter(grid, "dimension", "dimension");
        textColumnFilter(grid, "note", "note");
        return addSection(host, title, note, grid);
    }

    /**
     * Раздел «Доступ — правила значений» — объявленный путь измерения и то, что null в этом пути
     * означает. У сложной политики строк нет: записанный в объявлении путь там не действует —
     * фильтрация идёт по read-предикату, а значения поставляет запись, и публикация дефолта выдала
     * бы недействующий атрибут за правило фильтрации.
     */
    Details addAccessRuleSection(VerticalLayout host, String title, String note,
                                 List<EntitySummary.AccessRuleRow> rules) {
        if (rules.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.AccessRuleRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.AccessRuleRow.class, rules);
        grid.addColumn("dimension", "Измерение", EntitySummary.AccessRuleRow::dimension)
            .setResizable(true).setWidth("200px").setFlexGrow(0);
        grid.addColumn("path", "Путь", EntitySummary.AccessRuleRow::path)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("nulls", "Null", EntitySummaryPanel::nullsNotApplicableCell)
            .setResizable(true).setWidth("180px").setFlexGrow(0);
        grid.addColumn("source", "Источник", r -> sourceText(r.value()))
            .setResizable(true).setWidth("320px").setFlexGrow(0);
        textColumnFilter(grid, "path", "path");
        return addSection(host, title, note, grid);
    }

    // -------------------------------------------------------------------- связи (E3.2.0 шаг 5)

    /**
     * Раздел «Связи» — цель выбора поля: куда ведёт ссылка и откуда это известно. Цель несёт
     * существующий {@link #lookupCell}: текст {@link #lookupCellText} (цель с вариантом и
     * происхождением) плюс кнопка перехода, когда хост задал {@link #setStructureNavigator}.
     * Отдельной колонки «Источник» здесь нет намеренно: происхождение уже внутри цели, и вторая
     * колонка повторяла бы тот же факт — как в «Обратных ссылках», где происхождение задано
     * смыслом раздела.
     */
    Details addLookupSection(VerticalLayout host, String title, String note,
                             List<EntitySummary.LookupRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.LookupRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.LookupRow.class, rows);
        grid.addColumn("field", "Поле", EntitySummary.LookupRow::fieldName)
            .setResizable(true).setWidth("220px").setFlexGrow(0);
        grid.addComponentColumn("target", "Цель", this::lookupCell)
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("note", "Примечание", EntitySummary.LookupRow::note)
            .setResizable(true).setWidth("380px").setFlexGrow(0);
        textColumnFilter(grid, "field", "fieldName");
        textColumnFilter(grid, "note", "note");
        return addSection(host, title, note, grid);
    }

    Details addFilterSection(VerticalLayout host, String title,
                             List<EntitySummary.FilterRow> rows) {
        if (rows.isEmpty()) {
            return null;
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
        // Эта колонка — путь к .java-файлу конфига-декларанта (см. EntitySummary.FilterRow.source),
        // а не вид регистрации: подпись говорит, что в ячейке, иначе путь читался бы как «регистрация
        // и есть файл». Вид регистрации несёт колонка «Источник» («регистрация · <FQN>»).
        grid.addColumn("source", "Файл декларанта",
            r -> r.source().isBlank() ? "—" : r.source())
            .setResizable(true).setWidth("260px").setFlexGrow(0);
        grid.addColumn("origin", "Источник", r -> sourceText(r.value()))
            .setResizable(true).setWidth("300px").setFlexGrow(0);
        textColumnFilter(grid, "scope", "scope");
        textColumnFilter(grid, "path", "path");
        textColumnFilter(grid, "control", "control");
        return addSection(host, title, null, grid);
    }

    Details addSelectionSection(VerticalLayout host, String title,
                                List<EntitySummary.SelectionRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.SelectionRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.SelectionRow.class, rows);
        grid.addColumn("variant", "Вариант", r -> text(r.variant()))
            .setResizable(true).setWidth("160px").setFlexGrow(0);
        grid.addColumn("value", "Заголовок", r -> text(r.value().value()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("columns", "Колонки", r -> String.join(", ", r.columns()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("origin", "Источник", r -> sourceText(r.value()))
            .setResizable(true).setWidth("300px").setFlexGrow(0);
        textColumnFilter(grid, "variant", "variant");
        return addSection(host, title, null, grid);
    }

    Details addReferenceSection(VerticalLayout host, String title, String note,
                                List<EntitySummary.ReferenceRow> rows) {
        if (rows.isEmpty()) {
            return null;
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
        return addSection(host, title, note, grid);
    }

    Details addNumberingSection(VerticalLayout host, String title, String note,
                                List<EntitySummary.NumberingRow> rows) {
        if (rows.isEmpty()) {
            return null;
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
        return addSection(host, title, note, grid);
    }

    /**
     * Раздел «Lifecycle» — строки handler'а и хуков. Строки хуков есть только там, где
     * переопределение доказуемо; в остальных состояниях раздел показывает одну строку-факт с
     * оговоркой в «Примечании», и ни одно состояние не читается как «правил нет».
     */
    Details addLifecycleSection(VerticalLayout host, String title, String note,
                                List<EntitySummary.LifecycleRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        InMemoryFilterGrid<EntitySummary.LifecycleRow> grid =
            new InMemoryFilterGrid<>(EntitySummary.LifecycleRow.class, rows);
        grid.addColumn("hook", "Хук", r -> r.hook().isBlank() ? "handler" : r.hook())
            .setResizable(true).setWidth("220px").setFlexGrow(0);
        grid.addColumn("value", "Значение", r -> text(r.value().value()))
            .setResizable(true).setFlexGrow(1);
        grid.addColumn("source", "Источник", r -> sourceText(r.value()))
            .setResizable(true).setWidth("340px").setFlexGrow(0);
        grid.addColumn("note", "Примечание", r -> r.note())
            .setResizable(true).setWidth("240px").setFlexGrow(0);
        textColumnFilter(grid, "hook", "hook");
        textColumnFilter(grid, "value", "value.value");
        return addSection(host, title, note, grid);
    }

    // -------------------------------------------------------- адрес диагностики (E3.2.1 шаг 6.2)

    /**
     * Куда ведёт факт: вкладка и раздел словаря. Пустая вкладка — не место: либо у записи нет
     * ключа владельца («уровень сущности»), либо вид грани ещё никем не заявлен. Раздел, чьё имя
     * повторяет имя вкладки, отдельно не называется: «Связи · Связи» не добавило бы места.
     */
    static String placeText(CardSection.Location location) {
        if (location.tab() == null) {
            return location.addressed() ? "вид не размещён" : "уровень сущности";
        }
        String tab = location.tab().title();
        String place = location.section() == null || location.section().title().equals(tab)
            ? tab : tab + " · " + location.section().title();
        return location.detail().isBlank() ? place : place + " · " + location.detail();
    }

    /**
     * Ячейка «Где»: текст места и кнопка перехода, когда это место действительно нарисовано.
     * Запись уровня сущности остаётся текстом: вести некуда, и кнопка обещала бы переход.
     */
    Component diagnosticWhereCell(EntitySummary summary, EntitySummary.DiagnosticRow row) {
        List<CardSection.Location> locations = CardSection.locateAll(summary, row);
        if (locations.size() == 1) {
            return diagnosticPlaceCell(locations.get(0));
        }
        VerticalLayout places = new VerticalLayout();
        places.setPadding(false);
        places.setSpacing(false);
        locations.forEach(location -> places.add(diagnosticPlaceCell(location)));
        return places;
    }

    private Component diagnosticPlaceCell(CardSection.Location location) {
        String place = placeText(location);
        if (!canFocus(location)) {
            return new Span(place);
        }
        Button open = new Button(new Icon(VaadinIcon.ARROW_RIGHT));
        open.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        open.getElement().setAttribute("aria-label", "Открыть " + place);
        open.addClickListener(event -> focus(location));
        HorizontalLayout layout = new HorizontalLayout(new Span(place), open);
        layout.setSpacing(true);
        layout.setAlignItems(FlexComponent.Alignment.CENTER);
        return layout;
    }

    /** Переход возможен, только когда вкладка (и раздел) нарисованы на этой сводке. */
    boolean canFocus(CardSection.Location location) {
        if (location.tab() == null || !drawnTabs.containsKey(location.tab())) {
            return false;
        }
        return location.section() == null || drawnSections.containsKey(location.section());
    }

    /**
     * Фокус места, названного пользователем: выбрать вкладку, раскрыть раздел, прокрутить к нему и
     * сообщить host'у место целиком — {@code <вкладка>} или {@code <вкладка>/<раздел>}. Одним путём
     * ходят два вида факта: кнопка «Где» у диагностики и узел секции дерева — место в обоих случаях
     * одно и то же (вкладка и раздел словаря), и второй механики перехода не заводится. Якорь адреса
     * приходит этим же путём, но помеченный {@link #applyingPlace}: адрес уже стоит в окне.
     *
     * <p>Смена вкладки внутри фокуса не сообщается отдельно: пользователь назвал место, и оно
     * называется один раз — с разделом, а не двумя записями «вкладка» и «вкладка · раздел».</p>
     */
    void focus(CardSection.Location location) {
        if (!canFocus(location)) {
            return;
        }
        clearPlaceStatus();
        if (!location.detail().isBlank()) {
            placeStatus = new Span(location.detail());
            addComponentAsFirst(placeStatus);
        }
        boolean chosenByUser = !applyingPlace;
        boolean previousApplying = applyingPlace;
        applyingPlace = true;
        try {
            if (tabs != null) {
                tabs.setSelectedTab(drawnTabs.get(location.tab()));
            }
        } finally {
            applyingPlace = previousApplying;
        }
        Details details = location.section() == null ? null : drawnSections.get(location.section());
        if (details != null) {
            details.setOpened(true);
            details.getElement().scrollIntoView(new ScrollOptions(ScrollOptions.Behavior.AUTO,
                ScrollOptions.Alignment.START, ScrollOptions.Alignment.NEAREST));
        }
        if (chosenByUser) {
            placeListener.accept(placeAnchorOf(location));
        }
    }

    /** Якорь открытого этим фокусом места: раздел называется, когда он действительно нарисован. */
    private static String placeAnchorOf(CardSection.Location location) {
        CardAnchor anchor = location.section() == null
            ? CardAnchor.of(location.tab())
            : new CardAnchor(location.tab(), location.section());
        return anchor.value();
    }

    /**
     * Фокус табличной части по имени класса строки (E3.2.1 шаг 7.1): узел дерева ведёт в ту же
     * карточку типа, а место берётся у словаря. Сводка не перечитывается — показывается прочитанная.
     */
    void focusTableSection(String rowClass) {
        if (summary == null) {
            return;
        }
        focus(CardSection.locateTableSection(summary, rowClass));
    }

    // ------------------------------------------------------------- якорь адреса (E3.2.1 8.2)

    /**
     * Кто сообщает host'у, что место карточки сменил пользователь: якорь выбранной вкладки или
     * открытого раздела. Панель называет место, а адрес собирает host — карточка типа своего адреса
     * не знает.
     */
    void setPlaceListener(Consumer<String> listener) {
        this.placeListener = listener == null ? anchor -> {
        } : listener;
    }

    /**
     * Применить якорь адреса к уже нарисованной карточке. Тот же путь, что при первом показе,
     * ходит и на повторном входе (Back/Forward, повторная ссылка): второй механики нет. Форму
     * якоря панель не перепроверяет, а неизвестное место до неё не доходит — отказ выдаёт host по
     * словарю. Пустой якорь возвращает карточку к первому доступному аспекту; известное
     * отсутствующее место показывается отдельным статусом, без сообщения об изменении адреса.
     */
    void focusAnchor(String anchor) {
        if (anchor == null) {
            clearPlaceStatus();
            drawnTabs.keySet().stream().findFirst().ifPresent(tab -> focusPlace(CardAnchor.of(tab)));
            return;
        }
        CardAnchor.of(anchor).ifPresent(this::focusPlace);
    }

    /**
     * Якорь вкладки называет её первую секцию (§2.2), и открывается та, что действительно
     * нарисована. Для отсутствующего места показываются статус и действие «Открыть обзор»;
     * запрошенный адрес сохраняется до явного нового выбора пользователя.
     */
    private void focusPlace(CardAnchor anchor) {
        if (!canFocus(anchor.location())) {
            clearPlaceStatus();
            String name = anchor.section() == null ? anchor.tab().title() : anchor.section().title();
            Button overview = new Button("Открыть обзор", event ->
                focus(new CardSection.Location(CardTab.OVERVIEW, CardSection.section("summary"), true)));
            overview.setEnabled(canFocus(new CardSection.Location(CardTab.OVERVIEW,
                CardSection.section("summary"), true)));
            placeStatus = new HorizontalLayout(
                new Span("Раздел «" + name + "» отсутствует в карточке этого типа"), overview);
            addComponentAsFirst(placeStatus);
            return;
        }
        applyingPlace = true;
        try {
            focus(anchor.section() == null
                ? new CardSection.Location(anchor.tab(), firstDrawnSection(anchor.tab()), true)
                : anchor.location());
        } finally {
            applyingPlace = false;
        }
    }

    /** Первая нарисованная секция вкладки — в порядке словаря. */
    private CardSection<?> firstDrawnSection(CardTab tab) {
        for (CardSection<?> section : CardSection.ALL) {
            if (section.tab() == tab && drawnSections.containsKey(section)) {
                return section;
            }
        }
        return null;
    }

    /** Пользователь выбрал вкладку карточки: место — эта вкладка целиком, без раздела. */
    private void placeChosen(CardTab tab) {
        if (applyingPlace || tab == null) {
            return;
        }
        clearPlaceStatus();
        placeListener.accept(CardAnchor.of(tab).value());
    }

    private void clearPlaceStatus() {
        if (placeStatus != null) {
            remove(placeStatus);
            placeStatus = null;
        }
    }

    /** Уточнение owned-секции после программного входа не создаёт нового выбора/шага истории. */
    void showStructureDetail(String detail) {
        // Статус отсутствующего раздела с действием «Открыть обзор» имеет приоритет.
        if (placeStatus != null) {
            return;
        }
        if (detail != null && !detail.isBlank()) {
            placeStatus = new Span(detail);
            addComponentAsFirst(placeStatus);
        }
    }

    private CardTab tabOf(Tab selected) {
        for (Map.Entry<CardTab, Tab> entry : drawnTabs.entrySet()) {
            if (entry.getValue() == selected) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- ячейки с навигацией

    /**
     * Lookup: цель с происхождением и кнопка «открыть структуру цели». {@code lookup == null} —
     * у поля цели нет: ячейка остаётся пустой, а не заполняется догадкой.
     */
    private Component lookupCell(EntitySummary.LookupRow lookup) {
        if (lookup == null) {
            return new Span(text(""));
        }
        if (lookup.targetType() == null || structureNavigator == null) {
            return new Span(text(lookupCellText(lookup)));
        }
        HorizontalLayout layout = new HorizontalLayout(
            new Span(text(lookupCellText(lookup))), lookupAction(lookup.targetType()));
        layout.setSpacing(true);
        layout.setAlignItems(FlexComponent.Alignment.CENTER);
        return layout;
    }

    /**
     * Цель выбора: имя цели, вариант формы выбора и происхождение («явно», «JPA-маппинг», «тип
     * Java»). Происхождение — обязательная часть ячейки: без него объявленная цель неотличима от
     * выведенной из типа ассоциации.
     *
     * <p>Пакетный доступ — для теста текста: ячейка текстовая, и её содержание проверяется без
     * поднятия UI.</p>
     */
    static String lookupCellText(EntitySummary.LookupRow lookup) {
        String target = lookup.value().value() == null ? "" : lookup.value().value();
        if (!lookup.variant().isBlank()) {
            target = target + " [" + lookup.variant() + "]";
        }
        String origin = originLabel(lookup.value().origin());
        return target.isBlank() ? origin : target + " · " + origin;
    }

    private Button lookupAction(Class<?> entityClass) {
        Button open = new Button(new Icon(VaadinIcon.EXTERNAL_LINK));
        open.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        // Кнопка без текста: цель перехода называет текстовая подпись иконки (E3.2.2 §9.3).
        open.getElement().setAttribute("aria-label",
            "Открыть структуру " + entityClass.getSimpleName());
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
        navigator.openListForm((Class) entityClass, null, null);
    }

    // ---------------------------------------------------------------- помощники

    /**
     * Секция — сворачиваемый {@link Details}: заголовок (имя + необязательная пометка),
     * содержимое — FilterGrid с авто-высотой. Открыта по умолчанию.
     */
    private Details addSection(VerticalLayout parent, String name, String note,
                               InMemoryFilterGrid<?> grid) {
        grid.setWidthFull();
        grid.setHeight(null);
        grid.setCompact(true);
        grid.getGrid().setAllRowsVisible(true);
        grid.build();
        return addSection(parent, name, note, (Component) grid);
    }

    private Details addSection(VerticalLayout parent, String name, String note, Component content) {
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

        Details details = new Details(headerRow, content);
        details.setOpened(true);
        details.setWidthFull();
        parent.add(details);
        return details;
    }

    /** Обычный фильтр колонки: {@link TextFilter} в строке фильтров под заголовком. */
    private static <T> void textColumnFilter(InMemoryFilterGrid<T> grid, String columnKey, String fieldPath) {
        grid.addFilter(columnKey, fieldPath, new TextFilter<>());
    }

    /**
     * Источник факта — три раздельных признака: слой действующего значения, происхождение кодового
     * значения и Java-символ места. Пустой символ не заменяется ничем: вывести путь из класса
     * сущности значило бы назвать точное место, которого факт не сообщает.
     *
     * <p>Пакетный доступ — для теста текста: колонка текстовая, и её содержание проверяется без
     * поднятия UI.</p>
     */
    static String sourceText(ResolvedValue value) {
        return sourceText(value.source(), value.origin(), value.symbol());
    }

    static String sourceText(FactSource source, FactOrigin origin, String symbol) {
        StringBuilder text = new StringBuilder();
        if (source == FactSource.OVERRIDE) {
            // Переопределение действует, но происхождение описывает кодовый дефолт, который им
            // перекрыт: без пометки два разных состояния читались бы одинаково.
            text.append("переопределение (код: ").append(originLabel(origin)).append(')');
        } else {
            text.append(originLabel(origin));
        }
        return symbol == null || symbol.isBlank()
            ? text.toString()
            : text.append(" · ").append(symbol).toString();
    }

    /** Подпись происхождения кодового значения: восемь различимых состояний, без кодов наружу. */
    static String originLabel(FactOrigin origin) {
        return switch (origin) {
            case EXPLICIT -> "явно";
            case BEAN_VALIDATION -> "bean-валидация";
            case JPA_MAPPING -> "JPA-маппинг";
            case JAVA_TYPE -> "тип Java";
            case PLATFORM_DEFAULT -> "платформа";
            case REGISTRATION -> "регистрация";
            case DERIVED -> "выведено";
            case UNKNOWN -> "происхождение неизвестно";
        };
    }

    /** Строка регистрации формы: происхождение плюс проверенный символ, иначе путь декларанта. */
    static String formSourceText(EntitySummary.FormRow row) {
        String where = !row.symbol().isBlank() ? row.symbol() : row.source();
        String origin = originLabel(row.origin());
        return where.isBlank() ? origin : origin + " · " + where;
    }

    /** Поле: тип вместе с происхождением самого типа (JPA-маппинг, тип Java или явный). */
    static String fieldTypeCell(EntitySummary.FieldRow row) {
        String type = row.typeLabel() == null || row.typeLabel().isBlank() ? "" : row.typeLabel();
        return type.isBlank() ? originLabel(row.typeOrigin())
            : type + " · " + originLabel(row.typeOrigin());
    }

    /**
     * Обязательность вместе с происхождением: «да» без происхождения читалось бы как решение
     * карточки, хотя это решение валидации/маппинга. У необязательного поля происхождение не
     * рисуется — это был бы ответ на вопрос, которого нет.
     */
    static String requiredCell(EntitySummary.FieldRow row) {
        return yesNo(row.required())
            + (row.required() ? " · " + originLabel(row.requiredOrigin()) : "");
    }

    /**
     * Поверхность действия — словарь платформы с читаемыми подписями. Поверхность входит в ключ
     * регистрации (одно действие живёт на разных поверхностях с разными условиями), поэтому она
     * показывается как есть, а не выводится из места рендера.
     */
    static String actionSurfaceLabel(EntitySummary.ActionRow row) {
        return switch (row.surface()) {
            case LIST_TOOLBAR -> "тулбар списка";
            case ITEM_FOOTER -> "подвал карточки";
            case ITEM_MENU -> "меню карточки";
        };
    }

    /** Вариант формы: пусто — default-вариант поверхности (в ключе он `null`), а не «варианта нет». */
    static String actionVariantCell(EntitySummary.ActionRow row) {
        return text(row.key().variant());
    }

    /** Исполнитель: текст владельца («найден»/«не найден»), а не пересказ карточки. */
    static String executorCell(EntitySummary.ActionRow row) {
        return text(row.value().value());
    }

    /**
     * Допуск сценария — текст владельца плана («допущен»/«не допущен»), а не карточкино «да/нет»:
     * иначе «сценарий не допущен» и «сценария нет» читались бы одинаково.
     */
    static String readPlanAdmissionCell(EntitySummary.ReadPlanRow row) {
        return text(row.value().value());
    }

    /**
     * Число путей плана — счётчик, а не «есть/нет»: 0 путей у допущенного сценария это факт плана
     * (пустая ячейка означала бы «неизвестно»), а различие с отказом несёт колонка «Допущен».
     */
    static String readPlanPathCountCell(EntitySummary.ReadPlanRow row) {
        return String.valueOf(row.pathCount());
    }

    /** Причина пути — текст владельца плана: по ней видно, кто добавил путь (объявление, метаданные). */
    static String pathReasonCell(EntitySummary.PathRow row) {
        return text(row.reason());
    }

    /** Род измерения — словарь `RlsDimensionKind` словами, а не именами констант. */
    static String accessKindLabel(EntitySummary.AccessRow row) {
        return switch (row.dimensionKind()) {
            case FILTERABLE -> "фильтруемое";
            case CHECK_ONLY -> "проверяемое";
        };
    }

    /**
     * Каталог грантов — признак «измерение служит источником значений грантов», без значений:
     * сами гранты выданы пользователям, а не объявлены типом, и карточка их не показывает.
     */
    static String grantCatalogCell(EntitySummary.AccessRow row) {
        return yesNo(row.grantCatalog());
    }

    /**
     * Объявленное «null в пути означает: измерение к записи не применимо» — словами владельца
     * (javadoc {@code @RlsDimension#nullsNotApplicable}). Не объявлено — пустое место, а не
     * противоположное утверждение.
     */
    static String nullsNotApplicableCell(EntitySummary.AccessRuleRow row) {
        return row.nullsNotApplicable() ? "измерение не применимо" : "";
    }

    /** Адрес диагностики тем же видом, что и в общем списке Explorer: одно место, один формат. */
    static String diagnosticAddress(EntitySummary.DiagnosticRow row) {
        return row.fieldName().isBlank() ? row.entityFqn() : row.entityFqn() + "#" + row.fieldName();
    }

    static String severityLabel(MetadataDiagnostic.Severity severity) {
        return switch (severity) {
            case ERROR -> "ошибка";
            case WARNING -> "предупреждение";
            case INFO -> "сведения";
        };
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

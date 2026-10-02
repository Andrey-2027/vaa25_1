package org.ip.views.admin;

import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.vaadin.explorer.EntitySummary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Раздел карточки, объявленный один раз (E3.2.1): идентификатор, вкладка, заголовок, примечание и
 * источник строк. Композиция карточки — вкладки и разделы — читается из этого словаря и только из
 * него: рендер не задаёт порядок вызовов, а якоря адреса, адрес диагностики и фокус секции из
 * дерева берут вкладку и раздел отсюда же ({@code CardSection.ALL}).
 *
 * <p>Источник строк — функция сводки. Она же решает, непуст ли раздел, и она же отдаёт готовые
 * строки рендеру: второго чтения того же факта нет, поэтому забор состава может сверить число
 * строк нарисованной секции с числом строк её источника.</p>
 *
 * <p>Разрыв там, где факт делится на два вида строк: действия — объявления и исполнители,
 * сценарии — сценарий и путь, доступ — измерение и правило значений. Разрыв объявлен источником
 * (проекцией одного списка сводки), а не толкованием строк внутри рендера.</p>
 */
final class CardSection<T> {

    /**
     * Общее для разделов состояние рендера: сводка и индекс целей выбора по имени поля. Сводка
     * нужна диагностике: её «Где» выводится из вида грани и места поля в самой сводке.
     */
    record Context(EntitySummary summary, Map<String, EntitySummary.LookupRow> lookupByField) {
    }

    /**
     * Рисование раздела: строки уже выбраны источником, и вызывается только непустой раздел.
     * Возвращает нарисованный раздел ({@link Details}) или {@code null}, если раздела нет: панель
     * запоминает нарисованное, чтобы переходы из обзора знали, куда можно вести.
     */
    @FunctionalInterface
    interface Renderer<T> {
        Details render(EntitySummaryPanel panel, VerticalLayout host, CardSection<T> section,
                       List<T> rows, Context context);
    }

    private final CardTab tab;
    private final String id;
    private final String title;
    private final String note;
    private final Function<EntitySummary, List<T>> rows;
    private final Function<EntitySummary, Boolean> presence;
    private final Renderer<T> renderer;

    private CardSection(CardTab tab, String id, String title, String note,
                        Function<EntitySummary, List<T>> rows,
                        Function<EntitySummary, Boolean> presence, Renderer<T> renderer) {
        this.tab = tab;
        this.id = id;
        this.title = title;
        this.note = note;
        this.rows = rows;
        this.presence = presence;
        this.renderer = renderer;
    }

    static <T> CardSection<T> of(CardTab tab, String id, String title, String note,
                                 Function<EntitySummary, List<T>> rows, Renderer<T> renderer) {
        return new CardSection<>(tab, id, title, note, rows,
            summary -> !rows.apply(summary).isEmpty(), renderer);
    }

    /**
     * Раздел, который рисуется и при пустом источнике: так выражается явное состояние, а не
     * отсутствие факта (E3.2.2 §9.4 — «0 путей загрузки» у действительного плана).
     */
    static <T> CardSection<T> of(CardTab tab, String id, String title, String note,
                                 Function<EntitySummary, List<T>> rows,
                                 Function<EntitySummary, Boolean> presence,
                                 Renderer<T> renderer) {
        return new CardSection<>(tab, id, title, note, rows, presence, renderer);
    }

    /**
     * Непуст ли раздел у сводки (E3.2.2 §4.2): состав детей дерева читается у того же словаря,
     * которым рисуется карточка, и «есть ли раздел» решает он, а не второй список в дереве.
     * Присутствие может быть шире непустоты источника: у плана без путей состояние явное.
     */
    boolean hasRows(EntitySummary summary) {
        return presence.apply(summary);
    }

    CardTab tab() {
        return tab;
    }

    /** Идентификатор раздела внутри вкладки: латиница, строчные, дефисы. */
    String id() {
        return id;
    }

    /** Заголовок раздела — текст для человека. */
    String title() {
        return title;
    }

    /** Пометка рядом с заголовком; пустая — пометки нет. */
    String note() {
        return note == null ? "" : note;
    }

    /** Строки источника на данной сводке — то же, что увидит раздел, если он непуст. */
    List<T> rows(EntitySummary summary) {
        return rows.apply(summary);
    }

    /**
     * Рисует раздел в содержимом вкладки и возвращает его, или {@code null}. Пустой источник не
     * рисует ничего: «записей нет» не выдаётся за раздел.
     */
    Details draw(EntitySummaryPanel panel, VerticalLayout host, EntitySummary summary,
                 Context context) {
        List<T> selected = rows.apply(summary);
        if (!hasRows(summary)) {
            return null;
        }
        return renderer.render(panel, host, this, selected, context);
    }

    /**
     * Куда адресована запись диагностики. {@code tab} пуст — карточка не размещает запись: либо
     * у неё нет ключа владельца («уровень сущности»), либо вид грани ещё никем не заявлен.
     * {@code detail} уточняет место словами (секция и поле owned-строки), не меняя переход.
     */
    record Location(CardTab tab, CardSection<?> section, boolean addressed, String detail) {

        Location(CardTab tab, CardSection<?> section, boolean addressed) {
            this(tab, section, addressed, "");
        }

        Location {
            detail = detail == null ? "" : detail;
        }

        /** Ключа нет: запись принадлежит уровню сущности, а не разделу. */
        static Location entityLevel() {
            return new Location(null, null, false);
        }

        /** Ключ есть, но ни один раздел карточки не показывает этот вид грани. */
        static Location unplaced() {
            return new Location(null, null, true);
        }
    }

    /**
     * Все конкретные места записи диагностики (E3.2.2 §9.4). Владелец даёт ключ только трёх
     * видов ({@link FacetKind#FIELD_STRUCTURE}, {@link FacetKind#LOOKUP_TARGET},
     * {@link FacetKind#RLS_DIMENSION}). Поле, видимое в форме и гриде, даёт <b>оба</b> конкретных
     * перехода, а не одну произвольно выбранную проекцию; owned-поле ведёт в табличные части
     * своего root'а, и совпадение его имени с root-полем не подменяет карточку. Пустой список не
     * возвращается: недоступность выражается одним местом без перехода.
     */
    static List<Location> locateAll(EntitySummary summary, EntitySummary.DiagnosticRow row) {
        if (row == null) {
            return List.of(Location.entityLevel());
        }
        if (isOwnedDiagnostic(summary, row)) {
            return List.of(ownedFieldLocation(summary, row));
        }
        if (row.key() == null) {
            return List.of(Location.entityLevel());
        }
        List<CardSection<?>> candidates = diagnosticTargets(row.key().kind());
        if (candidates.isEmpty()) {
            return List.of(Location.unplaced());
        }
        if (candidates.size() == 1) {
            CardSection<?> only = candidates.get(0);
            return List.of(new Location(only.tab(), only, true));
        }
        List<CardSection<?>> showing = candidates.stream()
            .filter(candidate -> showsField(candidate, summary, row.fieldName()))
            .toList();
        if (showing.size() > 1) {
            return showing.stream()
                .map(candidate -> new Location(candidate.tab(), candidate, true))
                .toList();
        }
        if (showing.size() == 1) {
            CardSection<?> only = showing.get(0);
            return List.of(new Location(only.tab(), only, true));
        }
        return List.of(new Location(candidates.get(0).tab(), null, true));
    }

    /** Прежний вход — первое место записи; для двух проекций места называет {@link #locateAll}. */
    static Location locate(EntitySummary summary, EntitySummary.DiagnosticRow row) {
        return locateAll(summary, row).get(0);
    }

    private static boolean showsField(CardSection<?> candidate, EntitySummary summary,
                                      String fieldName) {
        return candidate.rows(summary).stream().anyMatch(rowAt -> rowAt
            instanceof EntitySummary.FieldRow field && field.name().equals(fieldName));
    }

    /** Запись пришла от строки табличной части, а не от поля root-типа: её ведёт владелец. */
    private static boolean isOwnedDiagnostic(EntitySummary summary,
                                             EntitySummary.DiagnosticRow row) {
        return !diagnosticType(row).isBlank() && !row.fieldName().isBlank()
            && summary.entityClass() != null
            && !diagnosticType(row).equals(summary.entityClass().getName());
    }

    private static String diagnosticType(EntitySummary.DiagnosticRow row) {
        return row.key() == null ? row.entityFqn() : row.key().entityClass().getName();
    }

    /**
     * Место owned-поля: раздел табличных частей root'а, подтвердившего эту строку. Секция и поле
     * называются в подсказке — иначе совпадение имени owned-поля с root-полем читалось бы как
     * место root-проекции; неподтверждённая строка остаётся «видом без раздела».
     */
    private static Location ownedFieldLocation(EntitySummary summary,
                                               EntitySummary.DiagnosticRow row) {
        List<EntitySummary.SectionRow> matching = summary.tableSections().stream()
            .filter(section -> diagnosticType(row).equals(section.value().symbol())).toList();
        if (matching.isEmpty()) {
            return Location.unplaced();
        }
        String names = matching.stream().map(section -> section.value().value())
            .collect(java.util.stream.Collectors.joining(", "));
        return new Location(CardTab.FIELDS, section("table-sections"), true,
            names + " · " + row.fieldName());
    }

    /**
     * Раздел, показывающий табличную часть с этой строкой: узел дерева знает только имя класса
     * строки ({@link EntitySummary.SectionRow#rowClass()}), и место берётся у той секции, чьи строки
     * этот факт действительно показывают. Второго списка «узел дерева → раздел» не заводится.
     */
    static Location locateTableSection(EntitySummary summary, String rowClass) {
        if (rowClass == null || rowClass.isBlank()) {
            return Location.unplaced();
        }
        return ALL.stream()
            .filter(section -> section.rows(summary).stream()
                .anyMatch(row -> row instanceof EntitySummary.SectionRow sectionRow
                    && rowClass.equals(sectionRow.rowClass())))
            .findFirst()
            .map(section -> new Location(section.tab(), section, true))
            .orElseGet(Location::unplaced);
    }

    /** Разделы, показывающие факт этого вида грани; у остальных видов раздел не заведён. */
    private static List<CardSection<?>> diagnosticTargets(FacetKind kind) {
        return switch (kind) {
            case FIELD_STRUCTURE -> List.of(section("form"), section("grid"));
            case LOOKUP_TARGET -> List.of(section("targets"));
            case RLS_DIMENSION -> List.of(section("dimensions"));
            default -> List.of();
        };
    }

    static CardSection<?> section(String id) {
        return ALL.stream()
            .filter(candidate -> candidate.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Нет раздела карточки с id " + id));
    }

    /**
     * Раздел вкладки по id адреса; пусто — такого якоря словарь не знает (см. {@link CardAnchor}).
     * Раздел ищется внутри названной вкладки, а не по всему словарю: вкладка — часть якоря.
     */
    static Optional<CardSection<?>> byId(CardTab tab, String id) {
        return ALL.stream()
            .filter(candidate -> candidate.tab == tab && candidate.id.equals(id))
            .findFirst();
    }

    /**
     * Вся композиция карточки в порядке экрана: двадцать разделов семи вкладок. Порядок объявления
     * — порядок вкладок и разделов; тот же порядок видят словарь, тесты состава и адресные якоря.
     */
    static final List<CardSection<?>> ALL = List.of(
        of(CardTab.OVERVIEW, "summary", "Обзор", null, EntitySummary::overview,
            (panel, host, section, rows, context) ->
                panel.addOverview(host, section.title(), rows)),
        of(CardTab.OVERVIEW, "diagnostics", "Диагностика",
            "стартовый скан метаданных; неадресованные записи — в каталоге слева",
            EntitySummary::diagnostics,
            (panel, host, section, rows, context) ->
                panel.addDiagnosticSection(host, section.title(), section.note(), rows, context)),

        of(CardTab.FIELDS, "form", "Поля — форма", null, EntitySummary::fieldsForm,
            (panel, host, section, rows, context) ->
                panel.addFieldSection(host, section.title(), rows, context.lookupByField())),
        of(CardTab.FIELDS, "grid", "Поля — грид", null, EntitySummary::fieldsGrid,
            (panel, host, section, rows, context) ->
                panel.addFieldSection(host, section.title(), rows, context.lookupByField())),
        of(CardTab.FIELDS, "list-columns", "Колонки списка по умолчанию", null,
            EntitySummary::listColumns,
            (panel, host, section, rows, context) ->
                panel.addColumnSection(host, section.title(), rows)),
        of(CardTab.FIELDS, "select-columns", "Колонки выбора (selectColumns)", null,
            EntitySummary::selectColumns,
            (panel, host, section, rows, context) ->
                panel.addColumnSection(host, section.title(), rows)),
        of(CardTab.FIELDS, "table-sections", "Табличные части",
            "структура — только код, не переопределяется", EntitySummary::tableSections,
            (panel, host, section, rows, context) ->
                panel.addSectionSection(host, section.title(), section.note(), rows)),

        of(CardTab.FORMS, "formats", "Формы и варианты", null, EntitySummary::forms,
            (panel, host, section, rows, context) ->
                panel.addFormSection(host, section.title(), rows)),
        of(CardTab.FORMS, "filters", "Контекст-фильтры", null,
            EntitySummary::contextFilters,
            (panel, host, section, rows, context) ->
                panel.addFilterSection(host, section.title(), rows)),
        of(CardTab.FORMS, "selections", "Наборы выбора (Selection)", null,
            EntitySummary::selections,
            (panel, host, section, rows, context) ->
                panel.addSelectionSection(host, section.title(), rows)),
        of(CardTab.FORMS, "actions", "Действия",
            "объявление действия; исполнитель — в разделе «Действия — исполнители»",
            summary -> actionRowsOfKind(summary.actions(), FacetKind.ACTION),
            (panel, host, section, rows, context) ->
                panel.addActionSection(host, section.title(), section.note(), rows)),
        of(CardTab.FORMS, "executors", "Действия — исполнители",
            "найденный handler или причина, почему его нет",
            summary -> actionRowsOfKind(summary.actions(), FacetKind.ACTION_HANDLER),
            (panel, host, section, rows, context) ->
                panel.addActionExecutorSection(host, section.title(), section.note(), rows)),

        of(CardTab.READING, "scenarios", "Сценарии чтения",
            "набор сценариев и допуск canonical path; пути — в разделе «Сценарии чтения — пути»",
            EntitySummary::readPlans,
            summary -> !summary.readPlanInspectionAvailable() || !summary.readPlans().isEmpty(),
            (panel, host, section, rows, context) ->
                panel.addReadPlanSection(host, section.title(), section.note(), rows)),
        of(CardTab.READING, "paths", "Сценарии чтения — пути",
            "путь плана сценария; причина — из плана, а не пересказ карточки",
            summary -> readPlanPaths(summary.readPlans()),
            summary -> !summary.readPlanInspectionAvailable() || !summary.readPlans().isEmpty(),
            (panel, host, section, rows, context) ->
                panel.addReadPlanPathSection(host, section.title(), section.note(), rows)),

        of(CardTab.ACCESS, "dimensions", "Доступ",
            "измерения RLS и как они участвуют; правила значений — в разделе «Доступ — правила значений»",
            EntitySummary::accessRows,
            (panel, host, section, rows, context) ->
                panel.addAccessSection(host, section.title(), section.note(), rows)),
        of(CardTab.ACCESS, "rules", "Доступ — правила значений",
            "путь измерения; у сложной политики правил нет — значения поставляет запись",
            summary -> accessRules(summary.accessRows()),
            (panel, host, section, rows, context) ->
                panel.addAccessRuleSection(host, section.title(), section.note(), rows)),

        of(CardTab.LINKS, "targets", "Связи",
            "цель выбора поля и откуда она известна; избыточное объявление — в «Примечании»",
            EntitySummary::lookupTargets,
            (panel, host, section, rows, context) ->
                panel.addLookupSection(host, section.title(), section.note(), rows)),
        of(CardTab.LINKS, "references", "Обратные ссылки («где используется»)",
            "структура — только код, не переопределяется. Пути без счётчиков (RLS-контекст)",
            EntitySummary::references,
            (panel, host, section, rows, context) ->
                panel.addReferenceSection(host, section.title(), section.note(), rows)),

        of(CardTab.BEHAVIOR, "numbering", "Нумерация",
            "структура — только код, не переопределяется", EntitySummary::numbering,
            (panel, host, section, rows, context) ->
                panel.addNumberingSection(host, section.title(), section.note(), rows)),
        of(CardTab.BEHAVIOR, "lifecycle", "Lifecycle",
            "правила прикладного handler'а — только код", EntitySummary::lifecycle,
            (panel, host, section, rows, context) ->
                panel.addLifecycleSection(host, section.title(), section.note(), rows)));

    /**
     * Строки одного вида факта: модель различает в аспекте действий объявление и исполнителя, и
     * раздел карточки — это один вид (разные столбцы у одного факта заставляли бы оставлять у
     * строки исполнителя пустой заголовок или повторять заголовок объявления).
     */
    private static List<EntitySummary.ActionRow> actionRowsOfKind(
            List<EntitySummary.ActionRow> rows, FacetKind kind) {
        return rows.stream().filter(row -> row.kind() == kind).toList();
    }

    /** Пути всех сценариев одним списком: раздел путей показывает строки факта, а не сценарии. */
    private static List<EntitySummary.PathRow> readPlanPaths(List<EntitySummary.ReadPlanRow> rows) {
        return rows.stream().flatMap(row -> row.paths().stream()).toList();
    }

    /** Правила всех измерений одним списком: правило вложено в строку измерения. */
    private static List<EntitySummary.AccessRuleRow> accessRules(List<EntitySummary.AccessRow> rows) {
        return rows.stream().flatMap(row -> row.rules().stream()).toList();
    }
}

package org.ip.views.admin;

import org.ipro.metadata.annotation.EntityKind;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.ExplorerSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * UI-проекция снимка Explorer (E3.2.2 §4.2–4.4): группы, типы, аспекты, разделы, поля и
 * owned-секции плюс фильтры каталога и поиск по индексу снимка. Это дерево <b>текущего вида</b>,
 * а не новый платформенный реестр: факты приходят из {@link ExplorerSnapshot}, состав и подписи
 * мест — из словаря карточки ({@link CardTab}, {@link CardSection}), второго описания структуры
 * не заводится.
 *
 * <p><b>Группы.</b> Обычный режим группирует по resolved {@code kind} и хранит подписи групп в одном
 * словаре ({@link #kindGroupTitle}); дополнительный режим — по подсистемам, где подпись берётся у
 * самого снимка. Тип без подсистемы не исчезает: он попадает в явную группу «Без подсистемы»,
 * а тип без resolved kind — в группу «kind неизвестен». Группа не открывает карточку: у узла
 * группы {@code type()} пуст, и фиктивный тип в выбор не передаётся.</p>
 *
 * <p><b>Стабильные идентификаторы.</b> У каждого узла id собран из FQN типа и id места словаря
 * (аспект/раздел/поле) либо из id owned-секции снимка. Подписи меняются — id остаются, поэтому
 * выбор и раскрытия можно держать по id, а не по объектам-копиям отфильтрованного дерева.</p>
 *
 * <p><b>Фильтры — пересечение (шаг 9.3).</b> Фильтр относится к типу/root: kind, подсистема,
 * экспозиция, «только ошибки». Owned-секция не сравнивает свою экспозицию {@code OWNED_ROW} с
 * exposure root'а — она остаётся у владельца; служебные типы показываются отдельным
 * переключателем; root без published-ключа не скрывается лишь из-за отсутствия адреса.</p>
 *
 * <p><b>Поиск — по индексу снимка (шаг 9.3).</b> Признаки уже собраны владельцами фактов в
 * {@link ExplorerSnapshot#searchTerms()}: effective подпись, опубликованный ключ, simpleName/FQN
 * типа, имя/подпись поля, название секции и FQN строки. Проекция только сопоставляет признак
 * узлу: совпавший тип показывает доступную структуру, совпавший потомок сохраняет цепочку
 * предков, owned-поле ведёт к подтверждённой секции владельца с подсказкой имени поля.
 * Совпадение с названием группы ничего не раскрывает — группы не индексируются.</p>
 *
 * <p><b>Счётчики диагностики.</b> ERROR/WARNING считаются один раз на запись диагностики и
 * агрегируются снизу вверх: счётчик группы — сумма записей её типов, а не сумма по проекциям
 * поля. Недоступная диагностика показывает «диагностика недоступна», а не выдуманный ноль.</p>
 */
final class ExplorerTreeModel {

    /** Режим группировки: kind — обычный, subsystem — дополнительный (§4.2). */
    enum GroupMode {
        KIND,
        SUBSYSTEM
    }

    /** Вид узла дерева. Группа — единственный узел без типа. */
    enum NodeKind {
        GROUP,
        TYPE,
        ASPECT,
        SECTION,
        FIELD,
        OWNED_SECTION,
        DIAGNOSTIC
    }

    /**
     * Узел проекции: неизменяемый, с детьми; равенство — по всем полям (детерминизм проверяем).
     * {@code tabId}/{@code sectionId} называют место узла у словаря карточки, {@code fieldName} —
     * имя места внутри раздела: поле для {@code FIELD}, simpleName строки для
     * {@code OWNED_SECTION}.
     */
    record Node(NodeKind kind, String id, String label, Class<?> type, String tabId,
                String sectionId, String fieldName, List<Node> children) {

        Node {
            children = List.copyOf(children);
        }
    }

    /**
     * Фильтр каталога (§4.3): пересечение выбранных условий. {@code null} в поле означает «не
     * выбрано». {@code showServiceTypes} — отдельный переключатель служебных типов; при
     * {@code false} типы экспозиций {@code INTERNAL_STORE}/{@code UNCLASSIFIED} скрыты.
     */
    record Filter(EntityKind kind, String subsystemId, String exposure, boolean onlyErrors,
                  boolean showServiceTypes) {

        /** Снимок-нейтральный фильтр: ничего не скрывает (режим тестов и прежних вызовов). */
        static Filter all() {
            return new Filter(null, null, null, false, true);
        }
    }

    /** Имя «экспозиция неизвестна» в фильтре: у записи нет descriptor'а. */
    static final String UNKNOWN_EXPOSURE = "unknown";

    /** Порядок вариантов экспозиции в фильтре — словарь, а не порядок попадания в инвентарь. */
    private static final List<String> EXPOSURE_ORDER = List.of(
        "STANDARD_ROOT", "INTERNAL_STORE", "UNCLASSIFIED", "OWNED_ROW");

    /**
     * Что проекции нужно от снимка: roots, сводки, confirmed-секции и поисковый индекс. В бою
     * единственная реализация — {@link #of(ExplorerSnapshot)}; шов существует, чтобы тесты
     * строили факты напрямую, не поднимая сборщик сводок.
     */
    interface CatalogView {

        List<ExplorerSnapshot.Entry> roots();

        Optional<EntitySummary> summaryOf(Class<?> type);

        List<ExplorerSnapshot.OwnedSection> sectionsOf(Class<?> type);

        List<ExplorerSnapshot.SearchTerm> searchTerms();

        default List<EntitySummary.DiagnosticRow> unassignedDiagnostics() {
            return List.of();
        }

        static CatalogView of(ExplorerSnapshot snapshot) {
            return new CatalogView() {
                @Override
                public List<ExplorerSnapshot.Entry> roots() {
                    return snapshot.roots();
                }

                @Override
                public Optional<EntitySummary> summaryOf(Class<?> type) {
                    return snapshot.summaryOf(type);
                }

                @Override
                public List<ExplorerSnapshot.OwnedSection> sectionsOf(Class<?> type) {
                    return snapshot.sectionsOf(type);
                }

                @Override
                public List<ExplorerSnapshot.SearchTerm> searchTerms() {
                    return snapshot.searchTerms();
                }

                @Override
                public List<EntitySummary.DiagnosticRow> unassignedDiagnostics() {
                    return snapshot.unassignedDiagnostics();
                }
            };
        }
    }

    private static final List<EntityKind> KIND_ORDER = List.of(
        EntityKind.CATALOG,
        EntityKind.DOCUMENT,
        EntityKind.INFORMATION_REGISTER,
        EntityKind.PLAIN,
        EntityKind.AUTO);

    /** Подписи групп kind — один UI-словарь (§4.2); менять здесь, а не в рендере. */
    private static String kindGroupTitle(EntityKind kind) {
        return switch (kind) {
            case CATALOG -> "Справочники";
            case DOCUMENT -> "Документы";
            case INFORMATION_REGISTER -> "Регистры сведений";
            case PLAIN -> "Прочие сущности";
            case AUTO -> "Не разрешённый kind (AUTO)";
        };
    }

    /** Подпись варианта фильтра kind: тот же словарь, что у групп. */
    static String kindFilterTitle(EntityKind kind) {
        return kindGroupTitle(kind);
    }

    /** Подпись варианта фильтра экспозиции; служебные хранилища названы явно. */
    static String exposureLabel(String exposure) {
        return switch (exposure) {
            case "STANDARD_ROOT" -> "Основные (STANDARD_ROOT)";
            case "INTERNAL_STORE" -> "Служебные хранилища (INTERNAL_STORE)";
            case "UNCLASSIFIED" -> "Вне каталога (UNCLASSIFIED)";
            case "OWNED_ROW" -> "Строки секций (OWNED_ROW)";
            case UNKNOWN_EXPOSURE -> "Неизвестна";
            default -> exposure;
        };
    }

    private ExplorerTreeModel() {
    }

    /** Дерево по готовому снимку: обычный вход вида. */
    static List<Node> roots(ExplorerSnapshot snapshot, GroupMode mode) {
        return roots(CatalogView.of(snapshot), mode, Filter.all());
    }

    /** Дерево без фильтров: прежний вход тестов и мест, где фильтры не выбирались. */
    static List<Node> roots(CatalogView view, GroupMode mode) {
        return roots(view, mode, Filter.all());
    }

    /** Дерево по выбранному фильтру: фильтр применяется к типам/root'ам до группировки. */
    static List<Node> roots(CatalogView view, GroupMode mode, Filter filter) {
        List<ExplorerSnapshot.Entry> visible = filter == null
            ? view.roots()
            : view.roots().stream().filter(entry -> passes(entry, filter)).toList();
        List<Node> roots = new ArrayList<>(mode == GroupMode.SUBSYSTEM
            ? subsystemRoots(visible, view) : kindRoots(visible, view));
        List<Node> problems = view.unassignedDiagnostics().stream()
            .filter(row -> row.code().equals("EXPLORER_OWNER_MISSING")
                || row.code().equals("EXPLORER_OWNER_AMBIGUOUS"))
            .map(row -> new Node(NodeKind.DIAGNOSTIC,
                "ownership:" + row.entityFqn() + "#" + row.code(), row.value().value(),
                null, null, null, null, List.of())).toList();
        if (!problems.isEmpty()) {
            roots.add(new Node(NodeKind.GROUP, "ownership:problems", "Проблемы владельцев",
                null, null, null, null, problems));
        }
        return List.copyOf(roots);
    }

    // ------------------------------------------------------------------ фильтр

    /** Проходит ли root-запись фильтр: пересечение всех выбранных условий (§4.3). */
    static boolean passes(ExplorerSnapshot.Entry entry, Filter filter) {
        if (entry == null || filter == null) {
            return false;
        }
        if (!filter.showServiceTypes() && isService(entry)) {
            return false;
        }
        if (filter.kind() != null && entry.kind() != filter.kind()) {
            return false;
        }
        if (filter.subsystemId() != null
            && !entry.subsystem().map(ExplorerSnapshot.SubsystemRef::id)
                .orElse("").equals(filter.subsystemId())) {
            return false;
        }
        if (filter.exposure() != null && !exposureOf(entry).equals(filter.exposure())) {
            return false;
        }
        // Известная ошибка отличается от недоступной диагностики: при неизвестных счётчиках
        // «только ошибки» ничего не утверждает и запись не проходит.
        return !filter.onlyErrors() || entry.state() == ExplorerSnapshot.EntryState.BUILD_FAILED
            || (entry.countsKnown() && entry.errorCount() > 0);
    }

    /**
     * Служебный тип: экспозиция не выдаёт публичного data handle. Тип без descriptor'а
     * служебным не считается — «экспозиция неизвестна» не приравнивается к служебному хранилищу.
     */
    static boolean isService(ExplorerSnapshot.Entry entry) {
        if (entry == null || entry.descriptor() == null) {
            return false;
        }
        return switch (entry.descriptor().exposure()) {
            case INTERNAL_STORE, UNCLASSIFIED -> true;
            default -> false;
        };
    }

    /** Имя экспозиции записи для фильтра: у записи без descriptor'а — явное «неизвестна». */
    private static String exposureOf(ExplorerSnapshot.Entry entry) {
        return entry.descriptor() == null ? UNKNOWN_EXPOSURE : entry.descriptor().exposure().name();
    }

    /** Присутствуют ли в инвентаре служебные типы: переключатель показывается только тогда. */
    static boolean hasServiceTypes(CatalogView view) {
        return view.roots().stream().anyMatch(ExplorerTreeModel::isService);
    }

    /** Служебный ли тип у записи с этим типом: прямой вход раскрывает путь и к такому типу. */
    static boolean isServiceType(CatalogView view, Class<?> type) {
        return view.roots().stream()
            .filter(entry -> entry.type().equals(type))
            .anyMatch(ExplorerTreeModel::isService);
    }

    /** Присутствующие виды в порядке словаря — варианты фильтра. */
    static List<EntityKind> presentKinds(CatalogView view) {
        return view.roots().stream()
            .map(ExplorerSnapshot.Entry::kind)
            .filter(kind -> kind != null)
            .distinct()
            .sorted(Comparator.comparingInt(ExplorerTreeModel::kindOrder))
            .toList();
    }

    /** Подсистемы инвентаря: идентификатор и подпись, порядок — по подписи. */
    static List<ExplorerSnapshot.SubsystemRef> presentSubsystems(CatalogView view) {
        Map<String, ExplorerSnapshot.SubsystemRef> byId = new LinkedHashMap<>();
        for (ExplorerSnapshot.Entry entry : view.roots()) {
            entry.subsystem().ifPresent(ref -> byId.putIfAbsent(ref.id(), ref));
        }
        return byId.values().stream()
            .sorted(Comparator.comparing(ref -> ref.label().value(), String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    /** Варианты фильтра экспозиции: присутствующие имена плюс «неизвестна», если есть. */
    static List<String> presentExposures(CatalogView view) {
        Set<String> present = new LinkedHashSet<>();
        for (ExplorerSnapshot.Entry entry : view.roots()) {
            present.add(exposureOf(entry));
        }
        List<String> ordered = new ArrayList<>();
        for (String exposure : EXPOSURE_ORDER) {
            if (present.remove(exposure)) {
                ordered.add(exposure);
            }
        }
        present.stream().sorted().forEach(ordered::add);
        return List.copyOf(ordered);
    }

    // ------------------------------------------------------------------ группы

    private static List<Node> kindRoots(List<ExplorerSnapshot.Entry> entries, CatalogView view) {
        Map<EntityKind, List<ExplorerSnapshot.Entry>> byKind = new TreeMap<>(
            Comparator.comparingInt(ExplorerTreeModel::kindOrder).thenComparing(Enum::name));
        List<ExplorerSnapshot.Entry> unknownKind = new ArrayList<>();
        for (ExplorerSnapshot.Entry entry : entries) {
            if (entry.kind() == null) {
                unknownKind.add(entry);
            } else {
                byKind.computeIfAbsent(entry.kind(), kind -> new ArrayList<>()).add(entry);
            }
        }

        List<Node> groups = new ArrayList<>();
        for (Map.Entry<EntityKind, List<ExplorerSnapshot.Entry>> group : byKind.entrySet()) {
            groups.add(groupNode("kind:" + group.getKey().name(),
                kindGroupTitle(group.getKey()), group.getValue(), view));
        }
        if (!unknownKind.isEmpty()) {
            groups.add(groupNode("kind:unknown", "kind неизвестен", unknownKind, view));
        }
        return groups;
    }

    private static int kindOrder(EntityKind kind) {
        int index = KIND_ORDER.indexOf(kind);
        return index >= 0 ? index : KIND_ORDER.size();
    }

    private static List<Node> subsystemRoots(List<ExplorerSnapshot.Entry> entries,
                                             CatalogView view) {
        Map<String, List<ExplorerSnapshot.Entry>> bySubsystem = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        List<ExplorerSnapshot.Entry> none = new ArrayList<>();
        for (ExplorerSnapshot.Entry entry : entries) {
            if (entry.subsystem().isEmpty()) {
                none.add(entry);
                continue;
            }
            ExplorerSnapshot.SubsystemRef subsystem = entry.subsystem().orElseThrow();
            labels.putIfAbsent(subsystem.id(), subsystem.label().value());
            bySubsystem.computeIfAbsent(subsystem.id(), id -> new ArrayList<>()).add(entry);
        }

        List<Node> groups = new ArrayList<>();
        bySubsystem.entrySet().stream()
            .sorted(Comparator.comparing(entry -> labels.get(entry.getKey()).toLowerCase(Locale.ROOT)))
            .forEach(bucket -> groups.add(groupNode("subsystem:" + bucket.getKey(),
                labels.get(bucket.getKey()), bucket.getValue(), view)));
        if (!none.isEmpty()) {
            groups.add(groupNode("subsystem:none", "Без подсистемы", none, view));
        }
        return groups;
    }

    private static Node groupNode(String id, String label, List<ExplorerSnapshot.Entry> entries,
                                  CatalogView view) {
        List<Node> children = new ArrayList<>();
        for (ExplorerSnapshot.Entry entry : sorted(entries)) {
            children.add(typeNode(entry, view));
        }
        return new Node(NodeKind.GROUP, id, label + counterText(entries), null, null, null, null,
            children);
    }

    /**
     * Счётчики группы: сумма записей диагностики её типов, посчитанных один раз каждая.
     * Недоступная у любого типа диагностика делает сумму неполной — группа говорит
     * «диагностика недоступна», а не показывает частичную сумму за полную.
     */
    private static String counterText(List<ExplorerSnapshot.Entry> entries) {
        int errors = 0;
        int warnings = 0;
        boolean known = true;
        for (ExplorerSnapshot.Entry entry : entries) {
            known &= entry.countsKnown();
            errors += entry.errorCount();
            warnings += entry.warningCount();
        }
        return " · " + countsText(errors, warnings, known);
    }

    private static String countsText(int errors, int warnings, boolean known) {
        if (!known) {
            return "диагностика недоступна";
        }
        return "ошибок: " + errors + ", предупреждений: " + warnings;
    }

    private static List<ExplorerSnapshot.Entry> sorted(List<ExplorerSnapshot.Entry> entries) {
        return entries.stream()
            .sorted(Comparator
                .comparing((ExplorerSnapshot.Entry entry) -> entry.displayName().value(),
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ExplorerSnapshot.Entry::simpleName))
            .toList();
    }

    // ------------------------------------------------------------------ тип и его состав

    private static Node typeNode(ExplorerSnapshot.Entry entry, CatalogView view) {
        String id = "type:" + entry.typeFqn();
        String label = entry.displayName().value() + "  (" + entry.simpleName() + ")  ["
            + (entry.kind() == null ? "kind неизвестен" : entry.kind().name())
            + " · "
            + (entry.descriptor() == null
                ? "descriptor неизвестен" : entry.descriptor().exposure().name())
            + " · " + countsText(entry.errorCount(), entry.warningCount(), entry.countsKnown())
            + "]";

        List<Node> children = new ArrayList<>();
        EntitySummary summary = view.summaryOf(entry.type()).orElse(null);
        if (entry.state() == ExplorerSnapshot.EntryState.BUILD_FAILED) {
            label += summary == null ? "  — сводка недоступна" : "  — часть фактов недоступна";
            children.add(diagnosticNode(id + "#diagnostic/failure", entry.type(),
                "Сборка фактов: " + entry.failureReason()));
        }
        if (summary == null) {
            if (entry.state() != ExplorerSnapshot.EntryState.BUILD_FAILED) {
                children.add(diagnosticNode(id + "#diagnostic/missing", entry.type(),
                    "Сводка недоступна: каталог не отдал данные"));
            }
        } else {
            children.addAll(aspectNodes(entry, summary, view));
        }
        return new Node(NodeKind.TYPE, id, label, entry.type(), null, null, null, children);
    }

    /** Аспекты — только непустые: пустая вкладка на экране не рисуется и в дереве не появляется. */
    private static List<Node> aspectNodes(ExplorerSnapshot.Entry entry, EntitySummary summary,
                                          CatalogView view) {
        List<Node> aspects = new ArrayList<>();
        for (CardTab tab : CardTab.values()) {
            List<Node> sections = new ArrayList<>();
            for (CardSection<?> section : CardSection.ALL) {
                if (section.tab() == tab && section.hasRows(summary)) {
                    sections.add(sectionNode(entry, tab, section, summary, view));
                }
            }
            if (!sections.isEmpty()) {
                aspects.add(new Node(NodeKind.ASPECT, typeId(entry) + "#tab:" + tab.id(),
                    tab.title(), entry.type(), tab.id(), null, null, sections));
            }
        }
        return aspects;
    }

    private static Node sectionNode(ExplorerSnapshot.Entry entry, CardTab tab,
                                    CardSection<?> section, EntitySummary summary,
                                    CatalogView view) {
        String id = typeId(entry) + "#tab:" + tab.id() + "/section:" + section.id();
        return new Node(NodeKind.SECTION, id, section.title(), entry.type(), tab.id(),
            section.id(), null, sectionChildren(entry, tab, id, section, summary, view));
    }

    /**
     * Дети раздела: состав берётся у того же словаря, что и рендер. Поля показываются у обеих
     * проекций формы и грида, owned-секции — у раздела табличных частей; прочие разделы — листья
     * (их строки не являются местами дерева).
     */
    private static List<Node> sectionChildren(ExplorerSnapshot.Entry entry, CardTab tab,
                                              String sectionNodeId, CardSection<?> section,
                                              EntitySummary summary, CatalogView view) {
        return switch (section.id()) {
            case "form" -> fieldNodes(entry.type(), tab, section, sectionNodeId,
                summary.fieldsForm());
            case "grid" -> fieldNodes(entry.type(), tab, section, sectionNodeId,
                summary.fieldsGrid());
            case "table-sections" -> ownedSectionNodes(tab, section, sectionNodeId,
                view.sectionsOf(entry.type()));
            default -> List.of();
        };
    }

    private static List<Node> fieldNodes(Class<?> type, CardTab tab, CardSection<?> section,
                                         String sectionNodeId, List<EntitySummary.FieldRow> fields) {
        List<Node> nodes = new ArrayList<>();
        for (EntitySummary.FieldRow field : fields) {
            String caption = field.value() == null ? "" : field.value().value();
            String label = caption == null || caption.isBlank()
                ? field.name() : caption + " (" + field.name() + ")";
            nodes.add(new Node(NodeKind.FIELD, sectionNodeId + "#field:" + field.name(), label,
                type, tab.id(), section.id(), field.name(), List.of()));
        }
        return nodes;
    }

    private static List<Node> ownedSectionNodes(CardTab tab, CardSection<?> section,
                                                String sectionNodeId,
                                                List<ExplorerSnapshot.OwnedSection> sections) {
        List<Node> nodes = new ArrayList<>();
        for (ExplorerSnapshot.OwnedSection ownedSection : sections) {
            nodes.add(new Node(NodeKind.OWNED_SECTION,
                sectionNodeId + "#owned:" + ownedSection.id(),
                ownedSection.label().value() + " (" + ownedSection.rowSimpleName() + ")",
                ownedSection.ownerType(), tab.id(), ownedSection.id(),
                ownedSection.rowSimpleName(), List.of()));
        }
        return nodes;
    }

    private static Node diagnosticNode(String id, Class<?> type, String label) {
        return new Node(NodeKind.DIAGNOSTIC, id, label, type, null, null, null, List.of());
    }

    private static String typeId(ExplorerSnapshot.Entry entry) {
        return "type:" + entry.typeFqn();
    }

    // ------------------------------------------------------------------ поиск по индексу

    static List<Node> withQuery(List<Node> roots, CatalogView view, String query) {
        List<Node> visible = withQuery(roots, view.searchTerms(), query);
        if (query == null || query.isBlank()) {
            return visible;
        }
        return visible.stream().map(group -> {
            if (!group.id().startsWith("kind:") && !group.id().startsWith("subsystem:")) {
                return group;
            }
            Set<Class<?>> types = new LinkedHashSet<>();
            collectTypes(group.children(), types);
            List<ExplorerSnapshot.Entry> entries = view.roots().stream()
                .filter(entry -> types.contains(entry.type())).toList();
            String title;
            if (group.id().startsWith("kind:")) {
                String kind = group.id().substring("kind:".length());
                title = kind.equals("unknown") ? "kind неизвестен" : kindGroupTitle(EntityKind.valueOf(kind));
            } else {
                title = entries.stream().flatMap(entry -> entry.subsystem().stream())
                    .map(ref -> ref.label().value()).findFirst().orElse("Без подсистемы");
            }
            return new Node(group.kind(), group.id(), title + counterText(entries), group.type(),
                group.tabId(), group.sectionId(), group.fieldName(), group.children());
        }).toList();
    }

    private static void collectTypes(List<Node> nodes, Set<Class<?>> out) {
        for (Node node : nodes) {
            if (node.kind() == NodeKind.TYPE) {
                out.add(node.type());
            }
            collectTypes(node.children(), out);
        }
    }

    /**
     * Видимое дерево для поискового запроса: сопоставляет признаки индекса снимка узлам дерева.
     * Совпавший тип сохраняет всю доступную структуру, совпавший потомок — цепочку предков;
     * owned-поле ведёт к подтверждённой секции владельца и называет найденное поле подсказкой.
     * Пустой запрос возвращает дерево как есть.
     */
    static List<Node> withQuery(List<Node> roots, List<ExplorerSnapshot.SearchTerm> terms,
                                String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return roots;
        }

        Map<String, Node> byId = new LinkedHashMap<>();
        Map<String, Node> ownedBySection = new LinkedHashMap<>();
        Map<String, List<Node>> fieldsByKey = new LinkedHashMap<>();
        collectIndex(roots, byId, ownedBySection, fieldsByKey);

        Set<String> matched = new LinkedHashSet<>();
        Map<String, String> hints = new LinkedHashMap<>();
        for (Node node : byId.values()) {
            if ((node.kind() == NodeKind.ASPECT || node.kind() == NodeKind.SECTION
                || node.kind() == NodeKind.DIAGNOSTIC)
                && node.label().toLowerCase(Locale.ROOT).contains(needle)) {
                matched.add(node.id());
            }
        }
        for (ExplorerSnapshot.SearchTerm term : terms == null
                ? List.<ExplorerSnapshot.SearchTerm>of() : terms) {
            if (!term.term().contains(needle)) {
                continue;
            }
            switch (term.kind()) {
                case TYPE -> matchTypeOrOwned(term, byId, ownedBySection, matched, hints);
                case SECTION -> matchOwned(ownedBySection, term.sectionId(), null, matched, hints);
                case FIELD -> {
                    if (term.sectionId().isEmpty()) {
                        String key = term.rootType().getName() + "#" + term.fieldName();
                        for (Node field : fieldsByKey.getOrDefault(key, List.of())) {
                            matched.add(field.id());
                        }
                    } else {
                        // Owned-поля нет среди root-полей: результат ведёт к подтверждённой
                        // секции владельца, а имя найденного поля остаётся подсказкой.
                        matchOwned(ownedBySection, term.sectionId(), term.fieldName(),
                            matched, hints);
                    }
                }
            }
        }
        if (matched.isEmpty()) {
            return List.of();
        }

        List<Node> visible = new ArrayList<>();
        for (Node root : roots) {
            Node kept = prune(root, matched, hints);
            if (kept != null) {
                visible.add(kept);
            }
        }
        return List.copyOf(visible);
    }

    private static void matchTypeOrOwned(ExplorerSnapshot.SearchTerm term, Map<String, Node> byId,
                                         Map<String, Node> ownedBySection, Set<String> matched,
                                         Map<String, String> hints) {
        if (term.sectionId().isEmpty()) {
            Node type = byId.get("type:" + term.rootType().getName());
            if (type != null) {
                matched.add(type.id());
            }
        } else {
            // Признак owned-строки (её подпись/имя/FQN) ведёт к секции подтверждённого владельца.
            matchOwned(ownedBySection, term.sectionId(), null, matched, hints);
        }
    }

    private static void matchOwned(Map<String, Node> ownedBySection, String sectionId,
                                   String fieldName, Set<String> matched,
                                   Map<String, String> hints) {
        if (sectionId == null || sectionId.isEmpty()) {
            return;
        }
        Node owned = ownedBySection.get(sectionId);
        if (owned == null) {
            // Секция не подтверждена или её владелец не прошёл фильтр: чужой root не подставляется.
            return;
        }
        matched.add(owned.id());
        if (fieldName != null && !fieldName.isEmpty()) {
            hints.merge(owned.id(), fieldName,
                (first, second) -> first.contains(second) ? first : first + ", " + second);
        }
    }

    private static void collectIndex(List<Node> nodes, Map<String, Node> byId,
                                     Map<String, Node> ownedBySection,
                                     Map<String, List<Node>> fieldsByKey) {
        for (Node node : nodes) {
            byId.put(node.id(), node);
            if (node.kind() == NodeKind.OWNED_SECTION && node.sectionId() != null) {
                ownedBySection.put(node.sectionId(), node);
            }
            if (node.kind() == NodeKind.FIELD && node.type() != null && node.fieldName() != null) {
                fieldsByKey.computeIfAbsent(node.type().getName() + "#" + node.fieldName(),
                    key -> new ArrayList<>()).add(node);
            }
            collectIndex(node.children(), byId, ownedBySection, fieldsByKey);
        }
    }

    /** Оставить совпавший узел (с детьми), предков совпавших потомков и скрыть пустые группы. */
    private static Node prune(Node node, Set<String> matched, Map<String, String> hints) {
        boolean self = matched.contains(node.id());
        List<Node> keptChildren = new ArrayList<>();
        for (Node child : node.children()) {
            Node kept = prune(child, matched, hints);
            if (kept != null) {
                keptChildren.add(kept);
            }
        }
        if (!self && keptChildren.isEmpty()) {
            return null;
        }
        List<Node> children = self ? copyAll(node.children(), hints) : List.copyOf(keptChildren);
        return withHint(node, hints.get(node.id()), children);
    }

    /** Совпавший тип показывает всю доступную структуру; подсказки доходят и до её потомков. */
    private static List<Node> copyAll(List<Node> nodes, Map<String, String> hints) {
        List<Node> copy = new ArrayList<>();
        for (Node node : nodes) {
            copy.add(withHint(node, hints.get(node.id()), copyAll(node.children(), hints)));
        }
        return List.copyOf(copy);
    }

    private static Node withHint(Node node, String hint, List<Node> children) {
        String label = hint == null || hint.isEmpty()
            ? node.label() : node.label() + " — найдено поле: " + hint;
        return new Node(node.kind(), node.id(), label, node.type(), node.tabId(),
            node.sectionId(), node.fieldName(), children);
    }
}

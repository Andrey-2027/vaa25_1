package org.ipro.vaadin.explorer;

import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityExposure;
import org.ipro.metadata.MetadataDiagnostic;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.facet.ResolvedValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Immutable снимок каталога Explorer (E3.2.2 §4.1/§9.1): типизированные записи инвентаря, по одной
 * сводке на тип, индексы секций и поисковый индекс.
 *
 * <p>Дерево, карточка, счётчики и поиск читают одни и те же факты: записи строятся из того же
 * перечня {@code @EntityMetadata}-классов, что и {@link EntitySummaryAssembler#entities()}, а
 * секции и подписи — у тех же владельцев, что и {@link EntitySummaryAssembler#summarize(Class)}.
 * Второй инвентарь и вторая формула подписи не заводятся.</p>
 *
 * <p><b>Одна попытка сборки на тип.</b> Сводка каждого типа собирается один раз при построении
 * снимка. Отказ сборки одного типа не лишает администратора остальных: запись остаётся в снимке с
 * состоянием {@link EntryState#BUILD_FAILED} и причиной, а не исчезает и не выдаётся за пустую
 * конфигурацию.</p>
 *
 * <p><b>Ноль и «неизвестно» — разные факты.</b> {@code countsKnown} записи говорит, посчитаны ли
 * ERROR/WARNING по действительной диагностике: недоступная проверка и отказ сборки дают
 * {@code false}, а не выдуманный ноль.</p>
 *
 * <p><b>Идентичность секции.</b> Секция адресуется root'ом, полем связи и FQN класса строки:
 * простого {@code rowClass} мало — два класса могут иметь одно simpleName, и тогда два узла
 * дерева слились бы в один.</p>
 *
 * <p>Снимок не создаёт UI-компонентов и не читает прикладные записи: его источники — только
 * владельцы конфигурации (metadata resolver, descriptor catalog, registry секций, каталог
 * маршрутов, сборщик сводок). Кеш снимка принадлежит текущему UI: создаётся заново на новый UI,
 * при смене пользователя/локали и при явном обновлении метаданных, а переключение
 * Workspace-вкладки его не пересоздаёт (E3.2.2 §9.1).</p>
 */
public final class ExplorerSnapshot {

    /** Состояние записи: сводка собрана либо её сборка отказала явной причиной. */
    public enum EntryState {
        READY,
        BUILD_FAILED
    }

    /** Подсистема записи: стабильный идентификатор — FQN маркера, подпись — эффективная. */
    public record SubsystemRef(String id, ResolvedValue label) {
        public SubsystemRef {
            Objects.requireNonNull(id, "id must not be null");
            if (id.isEmpty()) {
                throw new IllegalArgumentException("SubsystemRef id must not be empty");
            }
            Objects.requireNonNull(label, "label must not be null");
        }
    }

    /**
     * Секция, объявленная root'ом: владелец, поле связи, класс строки, эффективная подпись и
     * порядок объявления.
     */
    public record OwnedSection(
            Class<?> ownerType,
            String ownerFqn,
            String sectionField,
            Class<?> rowType,
            String rowClassFqn,
            String rowSimpleName,
            ResolvedValue label,
            int order) {

        public OwnedSection {
            Objects.requireNonNull(ownerType, "ownerType must not be null");
            Objects.requireNonNull(sectionField, "sectionField must not be null");
            Objects.requireNonNull(rowType, "rowType must not be null");
            ownerFqn = ownerFqn == null ? ownerType.getName() : ownerFqn;
            rowClassFqn = rowClassFqn == null ? rowType.getName() : rowClassFqn;
            rowSimpleName = rowSimpleName == null ? rowType.getSimpleName() : rowSimpleName;
            Objects.requireNonNull(label, "label must not be null");
        }

        /**
         * Стабильный идентификатор узла секции: root + поле связи + FQN строки. Простой
         * {@code rowClass} не годится — одно simpleName у двух классов слило бы два узла в один.
         */
        public String id() {
            return ownerFqn + "#" + sectionField + "#" + rowClassFqn;
        }
    }

    /**
     * Запись каталога: тип, его эффективные факты и состояние сборки сводки.
     *
     * <p>{@code kind} и {@code descriptor} пусты ровно тогда, когда факт не удалось прочитать у
     * владельца; запись при этом остаётся видимой. {@code descriptor} пуст и в конфигурации без
     * классифицированного каталога — это «экспозиция неизвестна», а не {@code UNCLASSIFIED}.</p>
     */
    public record Entry(
            Class<?> type,
            String typeFqn,
            String simpleName,
            ResolvedValue displayName,
            EntityKind kind,
            EntityDescriptor descriptor,
            Optional<SubsystemRef> subsystem,
            Optional<String> publishedKey,
            List<OwnedSection> ownedSections,
            EntryState state,
            String failureReason,
            List<EntitySummary.DiagnosticRow> diagnostics,
            int errorCount,
            int warningCount,
            boolean countsKnown) {

        public Entry {
            Objects.requireNonNull(type, "type must not be null");
            typeFqn = typeFqn == null ? type.getName() : typeFqn;
            Objects.requireNonNull(simpleName, "simpleName must not be null");
            Objects.requireNonNull(displayName, "displayName must not be null");
            subsystem = subsystem == null ? Optional.empty() : subsystem;
            publishedKey = publishedKey == null ? Optional.empty() : publishedKey;
            ownedSections = List.copyOf(ownedSections);
            Objects.requireNonNull(state, "state must not be null");
            failureReason = failureReason == null ? "" : failureReason;
            diagnostics = List.copyOf(diagnostics);
            if (state == EntryState.BUILD_FAILED && failureReason.isEmpty()) {
                throw new IllegalArgumentException("BUILD_FAILED entry must name its failure");
            }
            if (state == EntryState.BUILD_FAILED) {
                countsKnown = false;
            }
        }

        /** Является ли тип самостоятельным root'ом дерева (не подтверждённой owned-строкой). */
        public boolean rootCandidate() {
            return descriptor == null || descriptor.exposure() != EntityExposure.OWNED_ROW;
        }
    }

    /** Вид поискового признака: тип, поле или секция. */
    public enum SearchKind {
        TYPE,
        FIELD,
        SECTION
    }

    /**
     * Признак поискового индекса: {@code rootType} — карточка, к которой ведёт результат,
     * {@code sectionId} — подтверждённая секция для owned-поля, {@code fieldName} — найденное
     * поле (для подсказки). {@code term} хранится в нижнем регистре.
     */
    public record SearchTerm(
            Class<?> rootType,
            SearchKind kind,
            String term,
            String sectionId,
            String fieldName) {

        public SearchTerm {
            Objects.requireNonNull(rootType, "rootType must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(term, "term must not be null");
            sectionId = sectionId == null ? "" : sectionId;
            fieldName = fieldName == null ? "" : fieldName;
        }
    }

    /** Стоимость построения снимка: типы, попытки сборки сводки, отказы и время. */
    public record BuildStats(
            int typeCount,
            int summarizeAttempts,
            int summarizeFailures,
            long buildNanos) {
    }

    /**
     * Источники снимка — общие владельцы фактов. Package-private: снимок собирает
     * {@link EntitySummaryAssembler}, а тесты подставляют владельцев фикстурами.
     */
    record Sources(
            List<EntitySummaryAssembler.EntityRef> inventory,
            Function<Class<?>, EntityKind> kind,
            Function<Class<?>, EntityDescriptor> descriptor,
            Function<Class<?>, Optional<SubsystemRef>> subsystem,
            Function<Class<?>, List<OwnedSection>> sections,
            Function<Class<?>, Optional<String>> publishedKey,
            Function<Class<?>, EntitySummary> summary,
            boolean diagnosticsAvailable,
            Supplier<List<EntitySummary.DiagnosticRow>> unassignedDiagnostics) {
    }

    private final List<Entry> entries;
    private final Map<Class<?>, Entry> byType;
    private final Map<Class<?>, EntitySummary> summaries;
    private final List<SearchTerm> searchTerms;
    private final Map<Class<?>, List<OwnedSection>> sectionsByOwner;
    private final Map<String, List<OwnedSection>> ownersByRowFqn;
    private final List<EntitySummary.DiagnosticRow> unassignedDiagnostics;
    private final boolean diagnosticsAvailable;
    private final BuildStats stats;

    private ExplorerSnapshot(List<Entry> entries,
                             Map<Class<?>, EntitySummary> summaries,
                             List<SearchTerm> searchTerms,
                             Map<Class<?>, List<OwnedSection>> sectionsByOwner,
                             Map<String, List<OwnedSection>> ownersByRowFqn,
                             List<EntitySummary.DiagnosticRow> unassignedDiagnostics,
                             boolean diagnosticsAvailable,
                             BuildStats stats) {
        this.entries = List.copyOf(entries);
        Map<Class<?>, Entry> byType = new LinkedHashMap<>();
        for (Entry entry : this.entries) {
            byType.put(entry.type(), entry);
        }
        this.byType = Map.copyOf(byType);
        this.summaries = Map.copyOf(summaries);
        this.searchTerms = List.copyOf(searchTerms);
        this.sectionsByOwner = Map.copyOf(sectionsByOwner);
        Map<String, List<OwnedSection>> immutableOwners = new LinkedHashMap<>();
        ownersByRowFqn.forEach((row, owners) -> immutableOwners.put(row, List.copyOf(owners)));
        this.ownersByRowFqn = Map.copyOf(immutableOwners);
        this.unassignedDiagnostics = List.copyOf(unassignedDiagnostics);
        this.diagnosticsAvailable = diagnosticsAvailable;
        this.stats = stats;
    }

    /**
     * Собрать снимок из владельцев фактов. Сводка каждого типа собирается ровно один раз; отказ
     * сборки остаётся в записи типа и не скрывает остальной инвентарь.
     */
    static ExplorerSnapshot build(Sources sources) {
        Objects.requireNonNull(sources, "sources must not be null");
        long startedAt = System.nanoTime();

        List<Entry> entries = new ArrayList<>();
        Map<Class<?>, EntitySummary> summaries = new LinkedHashMap<>();
        Map<Class<?>, List<OwnedSection>> sectionsByOwner = new LinkedHashMap<>();
        Map<String, List<OwnedSection>> ownersByRowFqn = new LinkedHashMap<>();
        int attempts = 0;
        int failures = 0;
        List<EntitySummary.DiagnosticRow> unassigned = new ArrayList<>();
        boolean diagnosticsAvailable = sources.diagnosticsAvailable();
        try {
            if (sources.unassignedDiagnostics() != null) {
                unassigned.addAll(Objects.requireNonNull(sources.unassignedDiagnostics().get(),
                    "diagnostics source returned null"));
            }
        } catch (RuntimeException unavailable) {
            diagnosticsAvailable = false;
            unassigned.add(problem(null, "EXPLORER_DIAGNOSTICS_UNAVAILABLE",
                "Диагностика каталога недоступна: " + failureText(unavailable)));
        }
        Set<Class<?>> seen = new LinkedHashSet<>();

        for (EntitySummaryAssembler.EntityRef ref : sources.inventory()) {
            Class<?> type = ref.entityClass();
            if (!seen.add(type)) {
                continue;
            }
            List<String> factFailures = new ArrayList<>();

            List<OwnedSection> sections = new ArrayList<>();
            try {
                List<OwnedSection> read = List.copyOf(Objects.requireNonNull(
                    sources.sections().apply(type), "sections source returned null"));
                for (OwnedSection section : read) {
                    if (!section.ownerType().equals(type)) {
                        throw new IllegalStateException("section belongs to " + section.ownerFqn());
                    }
                }
                sections.addAll(read);
            } catch (RuntimeException sectionsFailure) {
                factFailures.add("Секции: " + failureText(sectionsFailure));
            }
            List<OwnedSection> ownerSections = List.copyOf(sections);
            sectionsByOwner.put(type, ownerSections);
            for (OwnedSection section : ownerSections) {
                ownersByRowFqn.computeIfAbsent(section.rowClassFqn(), key -> new ArrayList<>())
                    .add(section);
            }

            EntityKind kind = readFact("Вид", () -> sources.kind().apply(type), null, factFailures);
            EntityDescriptor descriptor = readFact("Экспозиция", () -> sources.descriptor().apply(type),
                null, factFailures);
            Optional<SubsystemRef> subsystem = readFact("Подсистема",
                () -> readOptional(sources.subsystem().apply(type), "subsystem"),
                Optional.empty(), factFailures);
            Optional<String> publishedKey = readFact("Ключ",
                () -> readOptional(sources.publishedKey().apply(type), "publishedKey"),
                Optional.empty(), factFailures);

            EntitySummary summary = null;
            attempts++;
            try {
                summary = Objects.requireNonNull(sources.summary().apply(type),
                    "summary source returned null");
            } catch (RuntimeException summaryFailure) {
                factFailures.add("Сводка: " + failureText(summaryFailure));
                failures++;
            }
            String failure = String.join("; ", factFailures);
            Set<EntitySummary.DiagnosticRow> uniqueDiagnostics = new LinkedHashSet<>();
            if (summary != null) {
                uniqueDiagnostics.addAll(summary.diagnostics());
            }
            if (!failure.isEmpty()) {
                uniqueDiagnostics.add(problem(type, "EXPLORER_FACTS_UNAVAILABLE", failure));
            }
            List<EntitySummary.DiagnosticRow> diagnostics = List.copyOf(uniqueDiagnostics);
            if (summary != null) {
                summary = withDiagnostics(summary, diagnostics);
                summaries.put(type, summary);
            }
            int errors = 0;
            int warnings = 0;
            for (EntitySummary.DiagnosticRow row : diagnostics) {
                if (row.severity() == MetadataDiagnostic.Severity.ERROR) {
                    errors++;
                } else if (row.severity() == MetadataDiagnostic.Severity.WARNING) {
                    warnings++;
                }
            }
            boolean countsKnown = summary != null && diagnosticsAvailable && failure.isEmpty();

            entries.add(new Entry(type, type.getName(), ref.simpleName(), ref.displayName(),
                kind, descriptor, subsystem, publishedKey, ownerSections,
                failure.isEmpty() ? EntryState.READY : EntryState.BUILD_FAILED,
                failure, diagnostics, errors, warnings, countsKnown));
        }

        List<SearchTerm> searchTerms = buildSearchTerms(entries, summaries, ownersByRowFqn);
        for (Entry entry : entries) {
            if (!entry.rootCandidate()) {
                List<OwnedSection> owners = ownersByRowFqn.getOrDefault(entry.typeFqn(), List.of());
                long rootCount = owners.stream().map(OwnedSection::ownerType).distinct().count();
                if (owners.isEmpty()) {
                    unassigned.add(problem(entry.type(), "EXPLORER_OWNER_MISSING",
                        "Строка " + entry.typeFqn() + ": подтверждённая секция-владелец не найдена"));
                } else if (rootCount > 1) {
                    String names = owners.stream().map(OwnedSection::ownerFqn).distinct().sorted()
                        .collect(java.util.stream.Collectors.joining(", "));
                    unassigned.add(problem(entry.type(), "EXPLORER_OWNER_AMBIGUOUS",
                        "Строка " + entry.typeFqn() + ": неоднозначный владелец — " + names));
                }
                if (entry.state() == EntryState.BUILD_FAILED) {
                    unassigned.addAll(entry.diagnostics());
                }
            }
        }

        BuildStats stats = new BuildStats(entries.size(), attempts, failures,
            System.nanoTime() - startedAt);
        return new ExplorerSnapshot(entries, summaries, searchTerms, sectionsByOwner,
            ownersByRowFqn, unassigned.stream().distinct().toList(), diagnosticsAvailable, stats);
    }

    private static <T> T readFact(String name, Supplier<T> source, T fallback, List<String> failures) {
        try {
            return source.get();
        } catch (RuntimeException failure) {
            failures.add(name + ": " + failureText(failure));
            return fallback;
        }
    }

    private static EntitySummary.DiagnosticRow problem(Class<?> type, String code, String reason) {
        return new EntitySummary.DiagnosticRow(MetadataDiagnostic.Severity.ERROR, code,
            type == null ? "" : type.getName(), "", reason, null,
            ResolvedValue.fact(reason, FactOrigin.DERIVED, ""), "ExplorerSnapshot");
    }

    private static EntitySummary withDiagnostics(EntitySummary summary,
                                                  List<EntitySummary.DiagnosticRow> diagnostics) {
        return new EntitySummary(summary.entityClass(), summary.simpleName(), summary.displayName(),
            summary.overview(), summary.fieldsForm(), summary.fieldsGrid(), summary.listColumns(),
            summary.selectColumns(), summary.tableSections(), summary.forms(), summary.contextFilters(),
            summary.selections(), summary.references(), summary.numbering(), summary.lifecycle(),
            diagnostics, summary.actions(), summary.readPlans(), summary.accessRows(),
            summary.lookupTargets(), summary.readPlanInspectionAvailable());
    }

    /** Все записи инвентаря в порядке перечня сущностей (эффективная подпись, затем simpleName). */
    public List<Entry> entries() {
        return entries;
    }

    /** Записи — кандидаты корней дерева: не подтверждённые owned-строки. */
    public List<Entry> roots() {
        return entries.stream().filter(Entry::rootCandidate).toList();
    }

    /** Записи подтверждённых owned-строк. */
    public List<Entry> ownedRows() {
        return entries.stream().filter(entry -> !entry.rootCandidate()).toList();
    }

    public Optional<Entry> entryOf(Class<?> type) {
        return Optional.ofNullable(byType.get(type));
    }

    /** Сводка типа, если её сборка не отказала: та же сводка, что читали дерево и счётчики. */
    public Optional<EntitySummary> summaryOf(Class<?> type) {
        return Optional.ofNullable(summaries.get(type));
    }

    /** Секции, объявленные root'ом, в порядке объявления. */
    public List<OwnedSection> sectionsOf(Class<?> ownerType) {
        return sectionsByOwner.getOrDefault(ownerType, List.of());
    }

    /** Подтверждённые секции, в которых этот класс — строка. Пусто — владелец не подтверждён. */
    public List<OwnedSection> ownersOf(Class<?> rowType) {
        return ownersByRowFqn.getOrDefault(rowType.getName(), List.of());
    }

    /** Поисковый индекс: признаки типов, полей и секций (в нижнем регистре). */
    public List<SearchTerm> searchTerms() {
        return searchTerms;
    }

    /** Диагностики без однозначной карточки; при недоступной проверке — явная запись о ней. */
    public List<EntitySummary.DiagnosticRow> unassignedDiagnostics() {
        return unassignedDiagnostics;
    }

    /** Доступна ли стартовая проверка метаданных: при {@code false} счётчики не выдаются за ноль. */
    public boolean diagnosticsAvailable() {
        return diagnosticsAvailable;
    }

    /** Стоимость построения: число типов, попыток сборки, отказов и время. */
    public BuildStats stats() {
        return stats;
    }

    private static List<SearchTerm> buildSearchTerms(
            List<Entry> entries,
            Map<Class<?>, EntitySummary> summaries,
            Map<String, List<OwnedSection>> ownersByRowFqn) {
        Set<SearchTerm> terms = new LinkedHashSet<>();
        for (Entry entry : entries) {
            Class<?> type = entry.type();
            if (entry.rootCandidate()) {
                addTerm(terms, type, SearchKind.TYPE, entry.displayName().value(), "", "");
                addTerm(terms, type, SearchKind.TYPE, entry.simpleName(), "", "");
                addTerm(terms, type, SearchKind.TYPE, entry.typeFqn(), "", "");
                entry.publishedKey()
                    .ifPresent(key -> addTerm(terms, type, SearchKind.TYPE, key, "", ""));
                EntitySummary summary = summaries.get(type);
                if (summary != null) {
                    addFieldTerms(terms, type, "", summary);
                }
                for (OwnedSection section : entry.ownedSections()) {
                    addTerm(terms, type, SearchKind.SECTION, section.label().value(), section.id(), "");
                    addTerm(terms, type, SearchKind.SECTION, section.rowSimpleName(), section.id(), "");
                    addTerm(terms, type, SearchKind.SECTION, section.rowClassFqn(), section.id(), "");
                }
            } else {
                // Owned-строка: собственного корня у неё нет — признаки ведут к подтверждённым
                // секциям владельцев. Неподтверждённая строка остаётся без признаков: случайный
                // root не выбирается.
                List<OwnedSection> owners = ownersByRowFqn.getOrDefault(type.getName(), List.of());
                EntitySummary summary = summaries.get(type);
                for (OwnedSection section : owners) {
                    addTerm(terms, section.ownerType(), SearchKind.TYPE,
                        entry.displayName().value(), section.id(), "");
                    addTerm(terms, section.ownerType(), SearchKind.TYPE,
                        entry.simpleName(), section.id(), "");
                    addTerm(terms, section.ownerType(), SearchKind.TYPE,
                        entry.typeFqn(), section.id(), "");
                    if (summary != null) {
                        addFieldTerms(terms, section.ownerType(), section.id(), summary);
                    }
                }
            }
        }
        return List.copyOf(terms);
    }

    private static void addFieldTerms(Set<SearchTerm> terms, Class<?> rootType, String sectionId,
                                      EntitySummary summary) {
        for (EntitySummary.FieldRow field : summary.fieldsForm()) {
            addTerm(terms, rootType, SearchKind.FIELD, field.name(), sectionId, field.name());
            addTerm(terms, rootType, SearchKind.FIELD, field.value().value(), sectionId, field.name());
        }
        for (EntitySummary.FieldRow field : summary.fieldsGrid()) {
            addTerm(terms, rootType, SearchKind.FIELD, field.name(), sectionId, field.name());
            addTerm(terms, rootType, SearchKind.FIELD, field.value().value(), sectionId, field.name());
        }
    }

    private static void addTerm(Set<SearchTerm> terms, Class<?> rootType, SearchKind kind,
                                String text, String sectionId, String fieldName) {
        if (text == null) {
            return;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return;
        }
        terms.add(new SearchTerm(rootType, kind, normalized, sectionId, fieldName));
    }

    private static <T> Optional<T> readOptional(Optional<T> value, String what) {
        if (value == null) {
            throw new IllegalStateException(what + " source returned null instead of Optional");
        }
        return value;
    }

    private static String failureText(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
    }
}

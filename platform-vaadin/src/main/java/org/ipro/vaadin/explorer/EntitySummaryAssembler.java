package org.ipro.vaadin.explorer;

import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionHandler;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.ActionProvenance;
import org.ipro.form.action.ActionProvenanceCatalog;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionRequirement;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormType;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.FormRouteKind;
import org.ipro.form.link.NotLinkableReason;
import org.ipro.form.link.PublishedFormRoute;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilities;
import org.ipro.data.FetchPlanInspection;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.AnnotationClassScanner;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.MetadataDiagnostic;
import org.ipro.metadata.MetadataDiagnosticCodes;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.Subsystem;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.metadata.facet.FactSource;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsPolicyDescriptor;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.numbering.NumberingPeriod;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleInspection;
import org.ipro.lifecycle.EntityLifecycleRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * Собирает {@link EntitySummary} для сущности из уже резолвнутых реестров платформы.
 *
 * <p>Единственное место знания о том, «что входит в сводку». Класс только читает:
 * никаких {@code register*} и мутаций реестров. Сам скан {@code @EntityMetadata}-классов
 * делает по base package (тот же {@code platform.subsystem-scan-package}, что у
 * ReferenceIndex/SubsystemRegistry), а не ходит по сырым реестрам.</p>
 *
 * <p>Резолюция надписей: каждая переопределяемая грань ({@link FacetKind#overridable()})
 * проходит через {@link FacetResolver} (код-дефолт ← переопределение); структурные грани
 * показываются как есть, пути переопределения у них нет.</p>
 */
public class EntitySummaryAssembler {

    private final String basePackage;
    private final MetadataResolver metadataResolver;
    private final FormRegistry formRegistry;
    private final ReferenceIndex referenceIndex;
    private final NumberingMetadataRegistry numberingMetadataRegistry;
    private final SubsystemRegistry subsystemRegistry;
    private final FacetResolver facetResolver;
    private final EntityDescriptorCatalog descriptorCatalog;
    private final FormRouteCatalog formRouteCatalog;
    private final EntityLifecycleRegistry lifecycleRegistry;
    private final SectionMetadataRegistry sectionMetadataRegistry;
    private final ActionRegistry actionRegistry;
    private final ActionHandlerRegistry actionHandlerRegistry;
    private final ActionProvenanceCatalog actionCatalog;
    private final FetchPlanInspection fetchPlanInspection;
    private final RlsDimensionRegistry rlsDimensionRegistry;
    private final boolean diagnosticsAvailable;
    private final Map<Class<?>, List<EntitySummary.DiagnosticRow>> diagnosticsByEntity;
    private final List<EntitySummary.DiagnosticRow> unassignedDiagnosticRows;

    public EntitySummaryAssembler(
            String basePackage,
            MetadataResolver metadataResolver,
            FormRegistry formRegistry,
            ReferenceIndex referenceIndex,
            NumberingMetadataRegistry numberingMetadataRegistry,
            SubsystemRegistry subsystemRegistry,
            FacetResolver facetResolver) {
        this(basePackage, metadataResolver, formRegistry, referenceIndex,
            numberingMetadataRegistry, subsystemRegistry, facetResolver,
            null, null, null, null, null, null, null, null);
    }

    public EntitySummaryAssembler(
            String basePackage,
            MetadataResolver metadataResolver,
            FormRegistry formRegistry,
            ReferenceIndex referenceIndex,
            NumberingMetadataRegistry numberingMetadataRegistry,
            SubsystemRegistry subsystemRegistry,
            FacetResolver facetResolver,
            EntityDescriptorCatalog descriptorCatalog,
            FormRouteCatalog formRouteCatalog,
            EntityLifecycleRegistry lifecycleRegistry,
            SectionMetadataRegistry sectionMetadataRegistry,
            MetadataConsistencyStartupCheck startupCheck) {
        this(basePackage, metadataResolver, formRegistry, referenceIndex,
            numberingMetadataRegistry, subsystemRegistry, facetResolver, descriptorCatalog,
            formRouteCatalog, lifecycleRegistry, sectionMetadataRegistry, startupCheck,
            null, null, null, null);
    }

    /**
     * Полный конструктор: те же коллабораторы плюс аспект действий (E3.2.0).
     *
     * <p>Три источника действий, а не один, и это не разрастание ради полноты: состав берётся у
     * {@link ActionRegistry} (единственная логика разрешения), «исполнитель найден» — у
     * {@link ActionHandlerRegistry} (тот же вызов, что делает {@code ActionResolver}), а «где
     * объявлено» — у {@link ActionProvenanceCatalog} (единственное место, знающее три источника
     * сборки). Свести их к одному коллаборатору значило бы либо продублировать разрешение в
     * каталоге, либо выводить место объявления догадкой.</p>
     */
    public EntitySummaryAssembler(
            String basePackage,
            MetadataResolver metadataResolver,
            FormRegistry formRegistry,
            ReferenceIndex referenceIndex,
            NumberingMetadataRegistry numberingMetadataRegistry,
            SubsystemRegistry subsystemRegistry,
            FacetResolver facetResolver,
            EntityDescriptorCatalog descriptorCatalog,
            FormRouteCatalog formRouteCatalog,
            EntityLifecycleRegistry lifecycleRegistry,
            SectionMetadataRegistry sectionMetadataRegistry,
            MetadataConsistencyStartupCheck startupCheck,
            ActionRegistry actionRegistry,
            ActionHandlerRegistry actionHandlerRegistry,
            ActionProvenanceCatalog actionCatalog) {
        this(basePackage, metadataResolver, formRegistry, referenceIndex,
            numberingMetadataRegistry, subsystemRegistry, facetResolver, descriptorCatalog,
            formRouteCatalog, lifecycleRegistry, sectionMetadataRegistry, startupCheck,
            actionRegistry, actionHandlerRegistry, actionCatalog, null);
    }

    /**
     * Полный конструктор: те же коллабораторы плюс сценарии чтения (E3.2.0 шаг 2).
     *
     * <p>План читается у владельца ({@link FetchPlanInspection} в {@code platform-core}), а не
     * выводится здесь из metadata: план, его пути и причины путей — один факт с одним хозяином,
     * и второй его реализации в UI быть не должно.</p>
     */
    public EntitySummaryAssembler(
            String basePackage,
            MetadataResolver metadataResolver,
            FormRegistry formRegistry,
            ReferenceIndex referenceIndex,
            NumberingMetadataRegistry numberingMetadataRegistry,
            SubsystemRegistry subsystemRegistry,
            FacetResolver facetResolver,
            EntityDescriptorCatalog descriptorCatalog,
            FormRouteCatalog formRouteCatalog,
            EntityLifecycleRegistry lifecycleRegistry,
            SectionMetadataRegistry sectionMetadataRegistry,
            MetadataConsistencyStartupCheck startupCheck,
            ActionRegistry actionRegistry,
            ActionHandlerRegistry actionHandlerRegistry,
            ActionProvenanceCatalog actionCatalog,
            FetchPlanInspection fetchPlanInspection) {
        this(basePackage, metadataResolver, formRegistry, referenceIndex, numberingMetadataRegistry,
            subsystemRegistry, facetResolver, descriptorCatalog, formRouteCatalog, lifecycleRegistry,
            sectionMetadataRegistry, startupCheck, actionRegistry, actionHandlerRegistry,
            actionCatalog, fetchPlanInspection, null);
    }

    /**
     * Полный конструктор: те же коллабораторы плюс аспект доступа (E3.2.0 шаг 3).
     *
     * <p>Измерения, правила и каталог грантов читаются у владельца
     * ({@link RlsDimensionRegistry} в {@code platform-rls}), а не выводятся здесь из аннотаций: род
     * измерения, пути и сверка read/write-предикатов — один факт с одним хозяином, проверенный при
     * старте приложения. {@link RlsPolicyDescriptor} остаётся локальной величиной: в рекорды сводки
     * и в поверхность модуля он не попадает.</p>
     */
    public EntitySummaryAssembler(
            String basePackage,
            MetadataResolver metadataResolver,
            FormRegistry formRegistry,
            ReferenceIndex referenceIndex,
            NumberingMetadataRegistry numberingMetadataRegistry,
            SubsystemRegistry subsystemRegistry,
            FacetResolver facetResolver,
            EntityDescriptorCatalog descriptorCatalog,
            FormRouteCatalog formRouteCatalog,
            EntityLifecycleRegistry lifecycleRegistry,
            SectionMetadataRegistry sectionMetadataRegistry,
            MetadataConsistencyStartupCheck startupCheck,
            ActionRegistry actionRegistry,
            ActionHandlerRegistry actionHandlerRegistry,
            ActionProvenanceCatalog actionCatalog,
            FetchPlanInspection fetchPlanInspection,
            RlsDimensionRegistry rlsDimensionRegistry) {
        this.basePackage = basePackage;
        this.metadataResolver = metadataResolver;
        this.formRegistry = formRegistry;
        this.referenceIndex = referenceIndex;
        this.numberingMetadataRegistry = numberingMetadataRegistry;
        this.subsystemRegistry = subsystemRegistry;
        this.facetResolver = facetResolver;
        this.descriptorCatalog = descriptorCatalog;
        this.formRouteCatalog = formRouteCatalog;
        this.lifecycleRegistry = lifecycleRegistry;
        this.sectionMetadataRegistry = sectionMetadataRegistry;
        this.actionRegistry = actionRegistry;
        this.actionHandlerRegistry = actionHandlerRegistry;
        this.actionCatalog = actionCatalog;
        this.fetchPlanInspection = fetchPlanInspection;
        this.rlsDimensionRegistry = rlsDimensionRegistry;
        this.diagnosticsAvailable = startupCheck != null;
        DiagnosticProjection projection = startupCheck == null
            ? DiagnosticProjection.empty()
            : projectDiagnostics(startupCheck.diagnostics());
        this.diagnosticsByEntity = projection.byEntity();
        this.unassignedDiagnosticRows = projection.unassigned();
    }

    /** Запись для левого списка сущностей. */
    public record EntityRef(Class<?> entityClass, String simpleName, ResolvedValue displayName) {
    }

    /**
     * Все {@code @EntityMetadata}-сущности base package, отсортированные по эффективному
     * displayName (затем simpleName) — детерминированно (П4).
     */
    public List<EntityRef> entities() {
        List<EntityRef> result = new ArrayList<>();
        for (Class<?> entityClass : AnnotationClassScanner.scanAnnotated(basePackage, EntityMetadata.class)) {
            String simpleName = entityClass.getSimpleName();
            EntityMetadata annotation = entityClass.getAnnotation(EntityMetadata.class);
            String codeTitle = annotation.listFormTitle().isBlank() ? simpleName : annotation.listFormTitle();
            FactOrigin origin = annotation.listFormTitle().isBlank()
                ? FactOrigin.PLATFORM_DEFAULT : FactOrigin.EXPLICIT;
            String symbol = origin == FactOrigin.EXPLICIT ? entityClass.getName() : "";
            result.add(new EntityRef(entityClass, simpleName,
                resolve(FacetKey.of(FacetKind.ENTITY_LIST_TITLE, entityClass), codeTitle,
                    origin, symbol)));
        }
        result.sort(Comparator
            .comparing((EntityRef r) -> r.displayName().value().toLowerCase(Locale.ROOT))
            .thenComparing(r -> r.simpleName().toLowerCase(Locale.ROOT)));
        return result;
    }

    /**
     * Сводка одной сущности. Бросает {@link IllegalArgumentException}, если класс не помечен
     * {@code @EntityMetadata} (тот же fail-fast, что у {@link MetadataResolver#resolve}).
     */
    public EntitySummary summarize(Class<?> entityClass) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        EntityMetadata annotation = meta.getAnnotation();
        String simpleName = entityClass.getSimpleName();

        String codeTitle = annotation.listFormTitle().isBlank() ? simpleName : annotation.listFormTitle();
        FactOrigin displayOrigin = annotation.listFormTitle().isBlank()
            ? FactOrigin.PLATFORM_DEFAULT : FactOrigin.EXPLICIT;
        ResolvedValue displayName = resolve(FacetKey.of(FacetKind.ENTITY_LIST_TITLE, entityClass),
            codeTitle, displayOrigin,
            displayOrigin == FactOrigin.EXPLICIT ? entityClass.getName() : "");

        EntityDescriptor descriptor = descriptorCatalog == null ? null
            : descriptorCatalog.descriptorOf(entityClass);
        PublishedFormRoute route = formRouteCatalog == null ? null
            : formRouteCatalog.find(entityClass).orElse(null);
        List<EntitySummary.OverviewRow> overview = overviewRows(
            entityClass, meta, annotation, simpleName, descriptor, route);

        List<EntitySummary.FieldRow> fieldsForm = fieldRows(
            meta.getFormFields(), entityClass, FacetKind.FIELD_LABEL);
        List<EntitySummary.FieldRow> fieldsGrid = fieldRows(
            meta.getGridFields(), entityClass, FacetKind.GRID_COLUMN_HEADER);

        List<EntitySummary.ColumnRow> listColumns = columnRows(entityClass, meta.getListColumnPaths(),
            annotation.listColumns().length == 0 ? "выведены из полей грида" : "");
        List<EntitySummary.ColumnRow> selectColumns = columnRows(entityClass, meta.getSelectColumnPaths(),
            annotation.selectColumns().length == 0 ? "использует колонки списка" : "");

        List<EntitySummary.SectionRow> tableSections = sectionRows(entityClass);
        List<EntitySummary.FormRow> forms = formRows(entityClass);
        List<EntitySummary.FilterRow> contextFilters = contextFilterRows(entityClass);
        List<EntitySummary.SelectionRow> selections = selectionRows(entityClass);
        List<EntitySummary.ReferenceRow> references = referenceRows(entityClass);
        List<EntitySummary.NumberingRow> numbering = numberingRows(entityClass);
        List<EntitySummary.LifecycleRow> lifecycle = lifecycleRows(entityClass);
        List<EntitySummary.ActionRow> actions = actionRows(entityClass);
        List<EntitySummary.ReadPlanRow> readPlans = readPlanRows(entityClass);
        List<EntitySummary.AccessRow> accessRows = accessRows(entityClass);
        List<EntitySummary.LookupRow> lookupTargets = lookupRows(entityClass, meta);

        return new EntitySummary(
            entityClass, simpleName, displayName,
            overview, fieldsForm, fieldsGrid, listColumns, selectColumns,
            tableSections, forms, contextFilters, selections, references, numbering,
            lifecycle, withAccessDiagnostics(diagnosticsFor(entityClass), entityClass),
            actions, readPlans, accessRows, lookupTargets, fetchPlanInspection != null);
    }

    /** Диагностики без однозначной карточки; служебная строка не входит в snapshot валидатора. */
    public List<EntitySummary.DiagnosticRow> unassignedDiagnostics() {
        if (!diagnosticsAvailable) {
            return List.of(new EntitySummary.DiagnosticRow(
                MetadataDiagnostic.Severity.INFO, "DIAGNOSTICS_UNAVAILABLE", "", "",
                "Диагностика недоступна", null,
                ResolvedValue.fact("Стартовая проверка не подключена",
                    FactOrigin.PLATFORM_DEFAULT, ""), ""));
        }
        return unassignedDiagnosticRows;
    }

    /**
     * Простые имена строк табличных частей сущности ({@code @TableSectionMetadata})-классы),
     * в порядке объявления. Пусто — табличных частей нет. Используется деревом левой панели
     * Entity Explorer, чтобы показывать табчасти как детей главной сущности.
     */
    public List<String> tableSectionRowNames(Class<?> entityClass) {
        return metadataResolver.resolveTableSections(entityClass).stream()
            .map(section -> section.getRowClass().getSimpleName())
            .toList();
    }

    /**
     * Immutable снимок каталога Explorer (E3.2.2 §4.1/§9.1): типизированные записи всего
     * инвентаря, по одной сводке на тип, индексы секций и поисковый индекс.
     *
     * <p>Снимок — та же поверхность чтения, что и {@link #entities()}/{@link #summarize(Class)}:
     * перечень не сканируется второй раз, kind берётся у metadata resolver, экспозиция — у
     * descriptor catalog, подсистема — у registry подсистем, подпись секции — той же формулой,
     * что у карточки, а сводка каждого типа собирается ровно один раз. Отказ сборки отдельного
     * типа остаётся в его записи и не скрывает остальной инвентарь. Кеш полученного снимка
     * принадлежит текущему UI (E3.2.2 §4.1).
     */
    public ExplorerSnapshot explorerSnapshot() {
        return ExplorerSnapshot.build(new ExplorerSnapshot.Sources(
            entities(),
            type -> metadataResolver.resolve(type).getEntityKind(),
            type -> descriptorCatalog == null ? null : descriptorCatalog.descriptorOf(type),
            this::subsystemRef,
            this::ownedSections,
            type -> formRouteCatalog == null
                ? Optional.empty()
                : formRouteCatalog.find(type).map(PublishedFormRoute::entityKey),
            this::summarize,
            diagnosticsAvailable,
            this::unassignedDiagnostics));
    }

    /** Подсистема типа: FQN маркера как стабильный идентификатор и эффективная подпись карточки. */
    private Optional<ExplorerSnapshot.SubsystemRef> subsystemRef(Class<?> entityClass) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        Class<?> marker = meta.getAnnotation().subsystem();
        if (marker == Subsystem.NoSubsystem.class) {
            return Optional.empty();
        }
        String title = subsystemRegistry.findByMarker(marker)
            .map(SubsystemNode::getTitle).orElse(null);
        String code = title != null ? title : marker.getSimpleName();
        FactOrigin origin = title != null ? FactOrigin.REGISTRATION : FactOrigin.PLATFORM_DEFAULT;
        ResolvedValue label = resolve(FacetKey.of(FacetKind.SUBSYSTEM_MEMBERSHIP, entityClass),
            code, origin, marker.getName());
        return Optional.of(new ExplorerSnapshot.SubsystemRef(marker.getName(), label));
    }

    /** Owned-секции root'а: идентичность — поле связи и FQN строки, подпись — формула карточки. */
    private List<ExplorerSnapshot.OwnedSection> ownedSections(Class<?> entityClass) {
        List<TableSectionMetadataInfo> sections = sectionMetadataRegistry == null
            ? metadataResolver.resolveTableSections(entityClass)
            : sectionMetadataRegistry.forOwner(entityClass);
        List<ExplorerSnapshot.OwnedSection> result = new ArrayList<>(sections.size());
        for (TableSectionMetadataInfo section : sections) {
            result.add(new ExplorerSnapshot.OwnedSection(
                entityClass, entityClass.getName(), section.getParentFieldName(),
                section.getRowClass(), section.getRowClass().getName(),
                section.getRowClass().getSimpleName(), sectionLabel(section),
                section.getOrder()));
        }
        return result;
    }

    /**
     * Подпись секции: одна формула у карточки и у каталога — иначе индекс разошёлся бы с
     * карточкой. Грань структурная ({@link FacetKind#TABLE_SECTION} не переопределяется), поэтому
     * значение показывается как есть, без обращения к {@code FacetResolver}.
     */
    private static ResolvedValue sectionLabel(TableSectionMetadataInfo section) {
        return ResolvedValue.fact(section.getTitle(), section.getTitleOrigin(),
            section.getRowClass().getName());
    }

    // === Обзор ===

    private List<EntitySummary.OverviewRow> overviewRows(
            Class<?> entityClass, EntityMetadataInfo meta, EntityMetadata annotation,
            String simpleName, EntityDescriptor descriptor, PublishedFormRoute route) {
        List<EntitySummary.OverviewRow> rows = new ArrayList<>(12);

        // Подсистема (членство — переопределяемая грань роли 3).
        Class<?> marker = annotation.subsystem();
        String subsystemCode = "";
        String title = null;
        boolean hasSubsystem = marker != Subsystem.NoSubsystem.class;
        if (hasSubsystem) {
            title = subsystemRegistry.findByMarker(marker)
                .map(SubsystemNode::getTitle).orElse(null);
            subsystemCode = title != null ? title : marker.getSimpleName();
        }
        FactOrigin subsystemOrigin = marker == Subsystem.NoSubsystem.class
            ? FactOrigin.PLATFORM_DEFAULT
            : title != null ? FactOrigin.REGISTRATION : FactOrigin.PLATFORM_DEFAULT;
        String subsystemSymbol = marker == Subsystem.NoSubsystem.class ? "" : marker.getName();
        rows.add(new EntitySummary.OverviewRow("Подсистема",
            FacetKey.of(FacetKind.SUBSYSTEM_MEMBERSHIP, entityClass),
            resolve(FacetKey.of(FacetKind.SUBSYSTEM_MEMBERSHIP, entityClass), subsystemCode,
                subsystemOrigin, subsystemSymbol)));

        rows.add(new EntitySummary.OverviewRow("Тип сущности",
            FacetKey.of(FacetKind.ENTITY_KIND, entityClass),
            ResolvedValue.fact(meta.getEntityKind().name(), meta.getEntityKindOrigin(),
                meta.getEntityKindSymbol())));

        if (descriptor != null) {
            rows.add(new EntitySummary.OverviewRow("Экспозиция",
                FacetKey.of(FacetKind.ENTITY_EXPOSURE, entityClass),
                ResolvedValue.fact(descriptor.exposure().name(), descriptor.exposureOrigin(),
                    descriptor.exposureSymbol()), descriptor.reason()));

            String keyValue = route == null ? "" : route.entityKey();
            FactOrigin keyOrigin = route == null
                ? FactOrigin.PLATFORM_DEFAULT : route.keyOrigin();
            String keySymbol = route == null ? "" : route.keySymbol();
            String keyDetail = route == null ? descriptor.reason() : route.keyReason();
            rows.add(new EntitySummary.OverviewRow("Внешний ключ",
                FacetKey.of(FacetKind.ENTITY_KEY, entityClass),
                ResolvedValue.fact(keyValue, keyOrigin, keySymbol), keyDetail));
            rows.addAll(linkabilityRows(entityClass, route));
        }

        String itemCode = annotation.itemFormTitle().isBlank()
            ? annotation.listFormTitle().isBlank() ? simpleName : annotation.listFormTitle()
            : annotation.itemFormTitle();
        FactOrigin itemOrigin = !annotation.itemFormTitle().isBlank() ? FactOrigin.EXPLICIT
            : !annotation.listFormTitle().isBlank() ? FactOrigin.DERIVED
            : FactOrigin.PLATFORM_DEFAULT;
        rows.add(new EntitySummary.OverviewRow("Заголовок формы элемента",
            FacetKey.of(FacetKind.ENTITY_ITEM_TITLE, entityClass),
            resolve(FacetKey.of(FacetKind.ENTITY_ITEM_TITLE, entityClass), itemCode,
                itemOrigin, itemOrigin == FactOrigin.PLATFORM_DEFAULT ? "" : entityClass.getName())));

        String selectionCode = annotation.selectionFormTitle().isBlank()
            ? annotation.listFormTitle().isBlank() ? simpleName : annotation.listFormTitle()
            : annotation.selectionFormTitle();
        FactOrigin selectionOrigin = !annotation.selectionFormTitle().isBlank() ? FactOrigin.EXPLICIT
            : !annotation.listFormTitle().isBlank() ? FactOrigin.DERIVED
            : FactOrigin.PLATFORM_DEFAULT;
        rows.add(new EntitySummary.OverviewRow("Заголовок формы выбора",
            FacetKey.of(FacetKind.ENTITY_SELECTION_TITLE, entityClass),
            resolve(FacetKey.of(FacetKind.ENTITY_SELECTION_TITLE, entityClass), selectionCode,
                selectionOrigin,
                selectionOrigin == FactOrigin.PLATFORM_DEFAULT ? "" : entityClass.getName())));
        return rows;
    }

    private static List<EntitySummary.OverviewRow> linkabilityRows(
            Class<?> entityClass, PublishedFormRoute route) {
        List<EntitySummary.OverviewRow> rows = new ArrayList<>();
        for (FormRouteKind kind : FormRouteKind.values()) {
            List<String> variants = route == null
                ? List.of("default")
                : route.variants(kind).stream().sorted().toList();
            for (String variant : variants) {
                String fieldName = kind.name();
                if (!"default".equals(variant)) {
                    fieldName += ":" + variant;
                }
                NotLinkableReason blocker = route == null
                    ? NotLinkableReason.NOT_PUBLISHED
                    : route.notLinkable(kind, variant).orElse(null);
                String value = blocker == null ? "Доступна" : "Недоступна";
                rows.add(new EntitySummary.OverviewRow(
                    "Ссылка " + kind.name().toLowerCase(Locale.ROOT)
                        + ("default".equals(variant)
                            ? "" : " («" + variant + "» )"),
                    FacetKey.of(FacetKind.LINKABILITY, entityClass, fieldName),
                    ResolvedValue.fact(value, FactOrigin.DERIVED, ""),
                    blocker == null ? "" : blocker.name()));
            }
        }
        return rows;
    }

    // === Поля ===

    private List<EntitySummary.FieldRow> fieldRows(
            List<FieldMetadataInfo> fields, Class<?> entityClass, FacetKind kind) {
        List<EntitySummary.FieldRow> rows = new ArrayList<>(fields.size());
        for (FieldMetadataInfo field : fields) {
            FacetKey key = FacetKey.of(kind, entityClass, field.getName());
            FactOrigin labelOrigin = field.getAnnotation().label().isEmpty()
                ? FactOrigin.PLATFORM_DEFAULT : FactOrigin.EXPLICIT;
            String symbol = field.getField().getDeclaringClass().getName() + "#" + field.getName();
            rows.add(new EntitySummary.FieldRow(
                key,
                field.getName(),
                typeLabel(field.getResolvedType()),
                resolve(key, field.getLabel(), labelOrigin, symbol),
                field.isRequired(),
                field.isReadOnly(),
                field.getRequiredOrigin(), field.getTypeOrigin()));
        }
        return rows;
    }

    // === Цели выбора (@Lookup) ===

    /**
     * Строки грани {@link FacetKind#LOOKUP_TARGET}: по строке на поле с целью. Цель, её класс и
     * происхождение берутся у владельца метаданных ({@code FieldMetadataInfo}) — аспект не решает,
     * откуда цель взялась.
     *
     * <p><b>Почему не из строк полей.</b> Поля формы и грида пересекаются, а факт принадлежит полю,
     * а не проекции: одна строка на поле — и один адрес. Порядок задаёт сборщик (по имени поля),
     * потому что порядок объявления полей — не контракт.</p>
     */
    private List<EntitySummary.LookupRow> lookupRows(Class<?> entityClass, EntityMetadataInfo meta) {
        List<EntitySummary.LookupRow> rows = new ArrayList<>();
        for (FieldMetadataInfo field : meta.getAllAnnotatedFields()) {
            if (!field.hasLookup()) {
                continue;
            }
            String symbol = field.getField().getDeclaringClass().getName() + "#" + field.getName();
            rows.add(new EntitySummary.LookupRow(
                FacetKey.of(FacetKind.LOOKUP_TARGET, entityClass, field.getName()),
                field.getName(),
                ResolvedValue.fact(field.getLookupEntity().getSimpleName(),
                    field.getReferenceOrigin(), symbol),
                field.getLookupEntity(),
                field.getLookupVariant(),
                lookupNote(field)));
        }
        rows.sort(Comparator.comparing(EntitySummary.LookupRow::fieldName));
        return rows;
    }

    /**
     * Примечание строки называет только то, чего не видно из значения и компонентов: избыточность
     * объявления цели. Ошибки цели ({@code REFERENCE_CONFLICT},
     * {@code REFERENCE_TARGET_NOT_METADATA}) здесь не пересказываются — владелец выводит их своим
     * каналом, и они адресованы той же грани.
     */
    private static String lookupNote(FieldMetadataInfo field) {
        boolean redundant = field.getReferenceOrigin() == FactOrigin.EXPLICIT
            && field.getLookupEntity() == field.getField().getType();
        return redundant
            ? "объявленная цель совпадает с типом ссылки: объявление избыточно"
            : "";
    }

    private static String typeLabel(FieldType type) {
        return type == null ? "" : type.name();
    }

    // === Колонки списка/выбора ===

    private List<EntitySummary.ColumnRow> columnRows(
            Class<?> entityClass, List<ColumnPath> paths, String note) {
        List<EntitySummary.ColumnRow> rows = new ArrayList<>(paths.size());
        for (ColumnPath path : paths) {
            FacetKey key = FacetKey.of(FacetKind.GRID_COLUMN_HEADER, entityClass, path.getKey());
            String symbol = path.isNested()
                || (path.getLabelOrigin() != FactOrigin.EXPLICIT
                    && path.getLabelOrigin() != FactOrigin.PLATFORM_DEFAULT)
                ? "" : path.getRootField().getDeclaringClass().getName()
                    + "#" + path.getRootField().getName();
            rows.add(new EntitySummary.ColumnRow(
                key,
                path.getKey(),
                resolve(key, path.getLabel(), path.getLabelOrigin(), symbol),
                typeLabel(path.getResolvedType()),
                path.isNested(), note));
        }
        return rows;
    }

    // === Табличные части ===

    private List<EntitySummary.SectionRow> sectionRows(Class<?> entityClass) {
        List<EntitySummary.SectionRow> rows = new ArrayList<>();
        List<TableSectionMetadataInfo> sections = sectionMetadataRegistry == null
            ? metadataResolver.resolveTableSections(entityClass)
            : sectionMetadataRegistry.forOwner(entityClass);
        for (TableSectionMetadataInfo section : sections) {
            FacetKey key = FacetKey.of(FacetKind.TABLE_SECTION, entityClass,
                section.getRowClass().getSimpleName());
            rows.add(new EntitySummary.SectionRow(
                key,
                sectionLabel(section),
                section.getRowClass().getSimpleName(),
                section.getOrder(),
                section.getMinRows(),
                section.getFormFields().size(),
                section.getGridFields().size()));
        }
        return rows;
    }

    // === Формы и варианты ===

    private List<EntitySummary.FormRow> formRows(Class<?> entityClass) {
        // Снимок регистраций, сгруппированный по (formType, variant).
        java.util.Map<FormKey, List<FormRegistry.Registration>> byKey = new java.util.LinkedHashMap<>();
        for (FormRegistry.Registration registration : formRegistry.registrationsOf(entityClass)) {
            byKey.computeIfAbsent(new FormKey(registration.formType(), registration.variant()),
                k -> new ArrayList<>()).add(registration);
        }

        List<EntitySummary.FormRow> rows = new ArrayList<>();
        for (FormType formType : FormType.values()) {
            List<FormRegistry.Registration> defaults = byKey.get(new FormKey(formType, null));
            if (defaults == null) {
                // Платформенный дефолт: автогенерация формы типом (ничего не зарегистрировано
                // на default-вариант). Появление Store-слоя роли 3 не меняет этой строки —
                // источник показывается колонкой (см. EntitySummary.FormRow).
                rows.add(new EntitySummary.FormRow(formType, null,
                    "платформенная автогенерация", true, "",
                    FactOrigin.PLATFORM_DEFAULT, ""));
            } else {
                rows.add(new EntitySummary.FormRow(formType, null,
                    registrationKindLabel(defaults), false, sourceLabel(defaults),
                    FactOrigin.REGISTRATION, registrationSymbol(defaults)));
            }
            // Именованные варианты.
            for (java.util.Map.Entry<FormKey, List<FormRegistry.Registration>> entry : byKey.entrySet()) {
                FormKey key = entry.getKey();
                if (key.formType == formType && key.variant != null) {
                    rows.add(new EntitySummary.FormRow(formType, key.variant,
                        registrationKindLabel(entry.getValue()), false, sourceLabel(entry.getValue()),
                        FactOrigin.REGISTRATION, registrationSymbol(entry.getValue())));
                }
            }
        }
        // Детерминизм (П4): default-вариант раньше именованных, внутри — по имени варианта.
        rows.sort(Comparator
            .comparing((EntitySummary.FormRow r) -> r.formType())
            .thenComparing(r -> r.variant(), Comparator.nullsFirst(String::compareTo)));
        return rows;
    }

    private record FormKey(FormType formType, String variant) {
    }

    private static String registrationKindLabel(List<FormRegistry.Registration> registrations) {
        StringJoiner joiner = new StringJoiner(", ");
        for (FormRegistry.Registration registration : registrations) {
            switch (registration.kind()) {
                case FORM_FACTORY -> joiner.add("кастомная фабрика");
                case LIST_VIEW_CLASS -> joiner.add("кастомный View");
                case LIST_VIEW_FACTORY -> joiner.add("кастомная фабрика View");
                case SELECTION_COLUMNS -> joiner.add("набор колонок выбора");
            }
        }
        return joiner.toString();
    }

    /**
     * Пути к .java-файлам классов-декларантов регистраций (без повторов) — «где явно»:
     * FQN конфига/View-класса превращается в путь относительно src/main/java.
     */
    private static String sourceLabel(List<FormRegistry.Registration> registrations) {
        return registrations.stream()
            .map(FormRegistry.Registration::source)
            .filter(source -> source != null && !source.isBlank())
            .distinct()
            .map(EntitySummaryAssembler::toSourcePath)
            .collect(java.util.stream.Collectors.joining(", "));
    }

    /** Единственный symbol показываем только если реестр сообщает разрешимый FQN. */
    private static String registrationSymbol(List<FormRegistry.Registration> registrations) {
        List<String> sources = registrations.stream()
            .map(FormRegistry.Registration::source)
            .filter(source -> source != null && !source.isBlank())
            .distinct()
            .toList();
        return sources.size() == 1 ? checkedSymbol(sources.get(0)) : "";
    }

    private static String checkedSymbol(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "";
        }
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> resolved = Class.forName(candidate, false,
                loader == null ? EntitySummaryAssembler.class.getClassLoader() : loader);
            return resolved.getName();
        } catch (ClassNotFoundException | LinkageError ignored) {
            return "";
        }
    }

    /** FQN класса → путь к .java-файлу (src/main/java/org/ip/views/forms/X.java → org/ip/views/forms/X.java). */
    private static String toSourcePath(String className) {
        return className.replace('.', '/') + ".java";
    }

    // === Контекст-фильтры ===

    private List<EntitySummary.FilterRow> contextFilterRows(Class<?> entityClass) {
        List<EntitySummary.FilterRow> rows = new ArrayList<>();

        // Общий ряд списка (уровень сущности).
        String listSource = formRegistry.getContextFilterSource(entityClass);
        for (ContextFilterField filter : formRegistry.getContextFilters(entityClass)) {
            rows.add(filterRow(entityClass, filter, null, "список (общий)", listSource));
        }
        // Вариантные ряды списка.
        for (var entry : formRegistry.getVariantContextFilters(entityClass, FormType.LIST).entrySet()) {
            String variantSource = formRegistry.getVariantContextFilterSource(
                entityClass, FormType.LIST, entry.getKey());
            for (ContextFilterField filter : entry.getValue()) {
                rows.add(filterRow(entityClass, filter, entry.getKey(),
                    "список, вариант «" + entry.getKey() + "»", variantSource));
            }
        }
        // Собственный ряд диалога выбора.
        String selectionSource = formRegistry.getSelectionContextFilterSource(entityClass);
        for (ContextFilterField filter : formRegistry.getSelectionContextFilters(entityClass)) {
            rows.add(filterRow(entityClass, filter, null, "выбор (собственный)", selectionSource));
        }
        // Вариантные ряды выбора.
        for (var entry : formRegistry.getVariantContextFilters(entityClass, FormType.SELECTION).entrySet()) {
            String variantSource = formRegistry.getVariantContextFilterSource(
                entityClass, FormType.SELECTION, entry.getKey());
            for (ContextFilterField filter : entry.getValue()) {
                rows.add(filterRow(entityClass, filter, entry.getKey(),
                    "выбор, вариант «" + entry.getKey() + "»", variantSource));
            }
        }
        rows.sort(Comparator
            .comparing((EntitySummary.FilterRow r) -> r.scope())
            .thenComparing(r -> r.path()));
        return rows;
    }

    private EntitySummary.FilterRow filterRow(
            Class<?> entityClass, ContextFilterField filter, String variant, String scope,
            String sourceFqn) {
        FacetKey key = FacetKey.of(FacetKind.CONTEXT_FILTER_LABEL, entityClass, filter.path(), variant);
        String codeDefault = filter.label() == null || filter.label().isBlank()
            ? filter.path() : filter.label();
        FactOrigin origin = filter.label() == null || filter.label().isBlank()
            ? FactOrigin.PLATFORM_DEFAULT : FactOrigin.REGISTRATION;
        String sourcePath = sourceFqn == null || sourceFqn.isBlank() ? "" : toSourcePath(sourceFqn);
        return new EntitySummary.FilterRow(
            key,
            filter.path(),
            resolve(key, codeDefault, origin,
                origin == FactOrigin.REGISTRATION ? checkedSymbol(sourceFqn) : ""),
            filter.control() == null ? "" : filter.control().name(),
            filter.required(),
            filter.allLists(),
            scope,
            sourcePath);
    }

    // === Selection (наборы колонок Формы Выбора) ===

    private List<EntitySummary.SelectionRow> selectionRows(Class<?> entityClass) {
        List<EntitySummary.SelectionRow> rows = new ArrayList<>();
        for (FormRegistry.Registration registration : formRegistry.registrationsOf(entityClass)) {
            if (registration.kind() != FormRegistry.RegistrationKind.SELECTION_COLUMNS) {
                continue;
            }
            org.ipro.form.registry.SelectionColumnsDef def =
                formRegistry.getSelectionColumns(entityClass, registration.variant());
            String codeTitle = def != null && def.title() != null && !def.title().isBlank()
                ? def.title()
                : (registration.variant() == null ? "по умолчанию" : registration.variant());
            FactOrigin origin = def != null && def.title() != null && !def.title().isBlank()
                ? FactOrigin.REGISTRATION : FactOrigin.DERIVED;
            rows.add(new EntitySummary.SelectionRow(
                registration.variant(),
                ResolvedValue.fact(codeTitle, origin,
                    origin == FactOrigin.REGISTRATION
                        ? checkedSymbol(registration.source()) : ""),
                def == null ? List.of() : def.columns(),
                true));
        }
        return rows;
    }

    // === Обратные ссылки ===

    private List<EntitySummary.ReferenceRow> referenceRows(Class<?> entityClass) {
        List<EntitySummary.ReferenceRow> rows = new ArrayList<>();
        for (ReferenceIndex.ReverseReference reference
                : referenceIndex.getReverseReferences(entityClass)) {
            FacetKey key = FacetKey.of(FacetKind.REVERSE_REFERENCE, entityClass,
                reference.referencingClass().getSimpleName() + "." + reference.fieldName());
            String value = reference.referencingClass().getSimpleName() + " (поле \"" +
                reference.fieldName() + "\")" + (reference.columnRef() ? " — ссылка колонкой" : "");
            rows.add(new EntitySummary.ReferenceRow(
                key,
                reference.referencingClass(),
                reference.fieldName(),
                reference.columnRef(),
                ResolvedValue.fact(value, FactOrigin.DERIVED,
                    reference.referencingClass().getName() + "#" + reference.fieldName())));
        }
        rows.sort(Comparator
            .comparing((EntitySummary.ReferenceRow r) -> r.referencingClass().getSimpleName())
            .thenComparing(EntitySummary.ReferenceRow::fieldName));
        return rows;
    }

    // === Нумерация ===

    private List<EntitySummary.NumberingRow> numberingRows(Class<?> entityClass) {
        List<EntitySummary.NumberingRow> rows = new ArrayList<>();
        for (NumberingMetadataRegistry.NumberedFieldInfo info
                : numberingMetadataRegistry.all()) {
            if (!info.entityClass().equals(entityClass)) {
                continue;
            }
            var definition = info.definition();
            FacetKey key = FacetKey.of(FacetKind.NUMBERING_DECL, entityClass, info.fieldName());
            String scope = definition.scope().isEmpty()
                ? "GLOBAL"
                : String.join(", ", definition.scope());
            rows.add(new EntitySummary.NumberingRow(
                key,
                info.fieldName(),
                ResolvedValue.fact(info.fieldName(), FactOrigin.EXPLICIT,
                    info.field().getDeclaringClass().getName() + "#" + info.fieldName()),
                scope,
                periodLabel(definition.period()),
                definition.allowManual()));
        }
        rows.sort(Comparator.comparing(EntitySummary.NumberingRow::fieldName));
        return rows;
    }

    private static String periodLabel(NumberingPeriod period) {
        return period == null ? "" : period.name();
    }

    // === Lifecycle ===

    /**
     * Оговорка о границе метода: правило, вызванное делегатом или listener'ом, остаётся внутри
     * тела хука и снаружи невидимо. Стоит один раз — в строке handler'а, а не в шести строках
     * хуков.
     */
    private static final String LIFECYCLE_DELEGATE_NOTE =
        "другие listeners и делегаты не анализируются";

    /**
     * Сводка lifecycle: строка handler'а и — только когда переопределение доказуемо — строки
     * хуков. Четыре состояния handler'а различимы текстом и происхождением, и ни одно из них
     * не читается как «правил нет»:
     * <ul>
     *   <li>handler развёрнут — {@code REGISTRATION} и шесть строк хуков с точным символом;</li>
     *   <li>handler есть, класс не развернулся (Java-proxy вне Spring) — происхождение хуков
     *       не подтверждено, строки хуков не публикуются;</li>
     *   <li>handler не зарегистрирован — {@code PLATFORM_DEFAULT};</li>
     *   <li>тип не {@link IdentifiableEntity} — {@code DERIVED}, lifecycle к нему неприменим.</li>
     * </ul>
     */
    private List<EntitySummary.LifecycleRow> lifecycleRows(Class<?> entityClass) {
        FacetKey handlerKey = FacetKey.of(FacetKind.ENTITY_LIFECYCLE_HANDLER, entityClass);
        if (!IdentifiableEntity.class.isAssignableFrom(entityClass)) {
            return List.of(new EntitySummary.LifecycleRow(handlerKey, null, true,
                ResolvedValue.fact("lifecycle не применим: тип не IdentifiableEntity",
                    FactOrigin.DERIVED, entityClass.getName()),
                ""));
        }
        if (lifecycleRegistry == null) {
            return List.of(new EntitySummary.LifecycleRow(handlerKey, null, true,
                ResolvedValue.fact("реестр lifecycle не подключён",
                    FactOrigin.PLATFORM_DEFAULT, ""),
                LIFECYCLE_DELEGATE_NOTE));
        }
        Optional<EntityLifecycle<?>> handler = registered(entityClass);
        if (handler.isEmpty()) {
            return List.of(new EntitySummary.LifecycleRow(handlerKey, null, true,
                ResolvedValue.fact("handler в реестре не зарегистрирован",
                    FactOrigin.PLATFORM_DEFAULT, ""),
                LIFECYCLE_DELEGATE_NOTE));
        }
        Optional<EntityLifecycleInspection.Assessment> inspected =
            EntityLifecycleInspection.of(handler.get());
        if (inspected.isEmpty()) {
            return List.of(new EntitySummary.LifecycleRow(handlerKey, null, true,
                ResolvedValue.fact("handler зарегистрирован; класс не развёрнут, "
                        + "происхождение хуков не подтверждено",
                    FactOrigin.PLATFORM_DEFAULT, ""),
                LIFECYCLE_DELEGATE_NOTE));
        }

        String handlerSymbol = inspected.get().handlerType().getName();
        List<EntitySummary.LifecycleRow> rows = new ArrayList<>(1 + inspected.get().hooks().size());
        rows.add(new EntitySummary.LifecycleRow(handlerKey, null, true,
            ResolvedValue.fact(inspected.get().handlerType().getSimpleName(),
                FactOrigin.REGISTRATION, handlerSymbol),
            LIFECYCLE_DELEGATE_NOTE));
        for (EntityLifecycleInspection.Hook hook : inspected.get().hooks()) {
            rows.add(new EntitySummary.LifecycleRow(
                FacetKey.of(FacetKind.ENTITY_LIFECYCLE_HOOK, entityClass, hook.name()),
                hook.name(),
                hook.declared(),
                hook.declared()
                    ? ResolvedValue.fact("переопределён handler'ом", FactOrigin.EXPLICIT,
                        handlerSymbol + "#" + hook.name())
                    : ResolvedValue.fact("не переопределён", FactOrigin.PLATFORM_DEFAULT,
                        EntityLifecycle.class.getName() + "#" + hook.name()),
                ""));
        }
        return rows;
    }

    // === Действия (E3.2.0) ===

    /**
     * Строки действий: на каждую победившую регистрацию — объявление ({@link FacetKind#ACTION}) и
     * исполнитель ({@link FacetKind#ACTION_HANDLER}).
     *
     * <p><b>Состав — не догадка.</b> Пары «поверхность + вариант» берутся у {@link FormRegistry}
     * (default-вариант и именованные варианты того же типа формы), а состав действий в каждой паре
     * даёт {@link ActionRegistry#registrationsOf} — тот же алгоритм разрешения, по которому решает
     * политика. Выдуманных комбинаций нет: спрашиваются только те варианты, которые существуют как
     * форма, плюс те, на которых действие объявлено явно — иначе объявленное действие на варианте
     * без своей формы было бы скрыто картой, а не отсутствовало.</p>
     *
     * <p>Пустой результат без коллабораторов — не «действий нет», а «аспект не подключён»:
     * в собранном приложении эти бины обязательны ({@code EntityExplorerAutoConfiguration}),
     * поэтому так выглядит только вручную собранный сборщик.</p>
     */
    private List<EntitySummary.ActionRow> actionRows(Class<?> entityClass) {
        if (actionRegistry == null || actionHandlerRegistry == null || actionCatalog == null) {
            return List.of();
        }
        EntityDescriptor descriptor = descriptorCatalog == null ? null
            : descriptorCatalog.descriptorOf(entityClass);

        List<EntitySummary.ActionRow> rows = new ArrayList<>();
        for (ActionSurface surface : ActionSurface.values()) {
            for (String variant : actionVariants(surface, entityClass)) {
                for (ActionRegistry.Registration registration
                        : actionRegistry.registrationsOf(surface, entityClass, variant)) {
                    rows.add(declarationRow(entityClass, surface, variant, registration, descriptor));
                    rows.add(executorRow(entityClass, surface, variant, registration));
                }
            }
        }
        return rows;
    }

    /**
     * Варианты, в которых живёт поверхность: default плюс именованные — объявленные формой или
     * действием. Порядок — default, затем по имени (П4).
     */
    private List<String> actionVariants(ActionSurface surface, Class<?> entityClass) {
        Set<String> named = new TreeSet<>();
        FormType formType = formTypeOf(surface);
        for (FormRegistry.Registration registration : formRegistry.registrationsOf(entityClass)) {
            if (registration.formType() == formType && registration.variant() != null) {
                named.add(registration.variant());
            }
        }
        for (ActionRegistry.Registration registration : actionRegistry.registrationsWithKeys()) {
            ActionRegistry.Key key = registration.key();
            if (key.surface() != surface || key.variant() == null) {
                continue;
            }
            if (key.entityType() == null || key.entityType().equals(entityClass)) {
                named.add(key.variant());
            }
        }
        List<String> variants = new ArrayList<>(named.size() + 1);
        variants.add(null);
        variants.addAll(named);
        return variants;
    }

    private static FormType formTypeOf(ActionSurface surface) {
        return switch (surface) {
            case LIST_TOOLBAR -> FormType.LIST;
            case ITEM_FOOTER, ITEM_MENU -> FormType.ITEM;
        };
    }

    private EntitySummary.ActionRow declarationRow(Class<?> entityClass, ActionSurface surface,
                                                   String variant, ActionRegistry.Registration registration,
                                                   EntityDescriptor descriptor) {
        ActionDefinition definition = registration.definition();
        ActionProvenance provenance = actionCatalog.declarationOf(registration.key());
        String label = labelOf(definition);
        return new EntitySummary.ActionRow(
            actionKey(FacetKind.ACTION, entityClass, surface, definition.id(), variant),
            surface, definition.id().value(), label, definition.order(), definition.visible(),
            registration.key().entityType() == null,
            false,
            ResolvedValue.fact(label, provenance.origin(), provenance.symbol()),
            declarationNote(definition, provenance, variant, descriptor));
    }

    private EntitySummary.ActionRow executorRow(Class<?> entityClass, ActionSurface surface,
                                                String variant, ActionRegistry.Registration registration) {
        ActionId id = registration.definition().id();
        Optional<ActionHandler> handler = actionHandlerRegistry.handlerOf(surface, entityClass,
            variant, id);
        ActionProvenance executor = handler
            .map(found -> actionCatalog.executorOf(ActionRegistry.Key.of(found.definition())))
            .orElseGet(() -> ActionProvenance.platformDefault("", "исполнитель не зарегистрирован"));
        return new EntitySummary.ActionRow(
            actionKey(FacetKind.ACTION_HANDLER, entityClass, surface, id, variant),
            surface, id.value(), "", 0, true, false, handler.isPresent(),
            ResolvedValue.fact(handler.isPresent() ? "найден" : "не найден",
                executor.origin(), executor.symbol()),
            executor.note());
    }

    /** Ключ обеих граней действия — один: грань различает факт, а не адрес. */
    private static FacetKey actionKey(FacetKind kind, Class<?> entityClass, ActionSurface surface,
                                      ActionId id, String variant) {
        return FacetKey.of(kind, entityClass, surface.name() + "/" + id.value(), variant);
    }

    private static String labelOf(ActionDefinition definition) {
        String title = definition.title();
        return title == null || title.isBlank() ? definition.id().value() : title;
    }

    /**
     * Примечание строки объявления. Названы две вещи, которые нельзя смешивать:
     * <b>подавление</b> — «объявлено, но этому типу не выдаётся» (политика приложения) и
     * <b>отсутствие операции у типа</b> — capability самого типа. Второе читается по требованию
     * объявления и дескриптору, а не по догадке из имени {@code id} и не из отсутствия
     * определения: «подавлено» не должно читаться как «право отнято», а объявленное действие,
     * которого тип не умеет, — как предлагаемое.
     */
    private String declarationNote(ActionDefinition definition, ActionProvenance provenance,
                                   String variant, EntityDescriptor descriptor) {
        CapabilityNote capability = capabilityNote(requirementOf(definition), descriptor);
        if (!definition.visible()) {
            return capability == null
                ? "подавлено приложением"
                : "подавлено приложением; " + capability.sentence();
        }
        String base = variant != null ? "объявлено для варианта «" + variant + "»"
            : provenance.note();
        if (capability == null || capability.allowed()) {
            // Для предлагаемой строки положительный факт — шум: причина нужна там, где её нет.
            return base;
        }
        return base.isEmpty() ? capability.sentence() : base + "; " + capability.sentence();
    }

    /**
     * Требование, по которому читается capability типа. У подавления собственное требование пусто
     * ({@link ActionRequirement#none()}), поэтому берётся требование платформенного действия-цели:
     * иначе карточка молчала бы о том, что тип операцию всё-таки допускает.
     */
    private static ActionRequirement requirementOf(ActionDefinition definition) {
        if (definition.visible()) {
            return definition.requirement();
        }
        CrudAction target = crudActionOf(definition.id());
        return target == null ? null : target.requirement();
    }

    /**
     * Операция, которой требует объявление, и допускает ли её тип; {@code null} — спрашивать
     * нечего: требование не называет операции, дескриптора нет либо операция выбирается
     * состоянием записи (тогда одна строка-объявление не может сказать, о какой из них речь).
     *
     * <p>Это пояснение к факту, а не второе решение: права пользователя и состояние строки в
     * карточку не входят — она показывает конфигурацию типа.</p>
     */
    private static CapabilityNote capabilityNote(ActionRequirement requirement,
                                                 EntityDescriptor descriptor) {
        if (requirement == null || descriptor == null || requirement.rowStateSelectsOperation()) {
            return null;
        }
        EntityCapabilities capabilities = descriptor.capabilities();
        if (requirement.create() == ActionRequirement.Level.REQUIRED) {
            return new CapabilityNote("запись", capabilities.allows(DataOperation.CREATE));
        }
        if (requirement.update() == ActionRequirement.Level.REQUIRED) {
            return new CapabilityNote("изменение", capabilities.allows(DataOperation.UPDATE));
        }
        if (requirement.delete() == ActionRequirement.Level.REQUIRED) {
            return new CapabilityNote("удаление", capabilities.allows(DataOperation.DELETE));
        }
        if (requirement.detail() == ActionRequirement.Level.REQUIRED) {
            return new CapabilityNote("чтение", capabilities.allows(FetchScenario.DETAIL));
        }
        return null;
    }

    /** Операция, которой требует объявление, и допускает ли её тип. */
    private record CapabilityNote(String operation, boolean allowed) {

        String sentence() {
            return "capability типа " + operation + (allowed ? " допускает" : " не допускает");
        }
    }

    /** Платформенное действие по id — из той же таблицы, что решает политика, а не из имени. */
    private static CrudAction crudActionOf(ActionId id) {
        for (CrudAction candidate : CrudAction.values()) {
            if (candidate.id().equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    // === Сценарии чтения (E3.2.0 шаг 2) ===

    /**
     * Строки сценариев чтения типа. Состав строк и порядок приходят от владельца плана
     * ({@link FetchPlanInspection}): союз «допущено ∪ план непуст» и порядок
     * {@link FetchScenario#values()}. Здесь к фактам добавляются ключ грани и текст примечания —
     * и ничего больше: второй реализации разрешения плана в UI быть не должно.
     *
     * <p>Отсутствие владельца — не пустой план: сводка несёт это отдельным флагом
     * ({@code readPlanInspectionAvailable}), а список остаётся пустым. Выдуманная строка «0 путей»
     * выдала бы «не спросили» за «путей нет».</p>
     */
    private List<EntitySummary.ReadPlanRow> readPlanRows(Class<?> entityClass) {
        if (fetchPlanInspection == null) {
            return List.of();
        }
        EntityDescriptor descriptor = descriptorCatalog == null ? null
            : descriptorCatalog.descriptorOf(entityClass);
        List<EntitySummary.ReadPlanRow> rows = new ArrayList<>();
        for (FetchPlanInspection.Scenario scenario : fetchPlanInspection.scenariosOf(entityClass)) {
            rows.add(readPlanRow(entityClass, scenario, descriptor));
        }
        return rows;
    }

    private EntitySummary.ReadPlanRow readPlanRow(Class<?> entityClass,
                                                  FetchPlanInspection.Scenario scenario,
                                                  EntityDescriptor descriptor) {
        List<EntitySummary.PathRow> paths = new ArrayList<>(scenario.paths().size());
        for (FetchPlanInspection.Path path : scenario.paths()) {
            paths.add(new EntitySummary.PathRow(
                FacetKey.of(FacetKind.FETCH_PLAN_PATH, entityClass,
                    scenario.scenario() + "/" + path.attributePath()),
                scenario.scenario(), path.attributePath(), path.reason(),
                ResolvedValue.fact(path.attributePath(), FactOrigin.DERIVED, "")));
        }
        return new EntitySummary.ReadPlanRow(
            FacetKey.of(FacetKind.FETCH_PLAN, entityClass, scenario.scenario()),
            scenario.scenario(), scenario.allowed(), scenario.pathCount(),
            ResolvedValue.fact(scenario.allowed() ? "допущен" : "не допущен",
                scenario.origin(), scenario.symbol()),
            readPlanNote(scenario, descriptor), paths);
    }

    /**
     * Примечание строки сценария. Различаются три вещи, которые нельзя смешивать: откуда пришёл
     * <b>набор</b> (объявление приложения против правила экспозиции) и <b>допускает</b> ли сценарий
     * canonical path. Отсутствие путей примечанием не объясняется: это видно счётчиком, а догадка
     * о «почему пусто» была бы вторым решением вместо факта.
     *
     * <p>Набор, равный правилу, читается объявлением: override заменяет выведенное, поэтому
     * сравнение значений здесь не измерение.</p>
     */
    private static String readPlanNote(FetchPlanInspection.Scenario scenario,
                                       EntityDescriptor descriptor) {
        String base;
        if (scenario.origin() == FactOrigin.REGISTRATION) {
            String reason = descriptor == null ? "" : descriptor.capabilities().reason();
            base = reason.isBlank() ? "набор сценариев объявлен приложением"
                : "набор сценариев объявлен приложением: " + reason;
        } else {
            String exposure = descriptor == null ? "UNCLASSIFIED" : descriptor.exposure().name();
            base = "набор сценариев выведен из экспозиции типа (" + exposure + ")";
        }
        return scenario.allowed() ? base : base + "; canonical path сценарий не допускает";
    }

    // === Доступ: RLS (E3.2.0 шаг 3) ===

    /**
     * Строки измерений типа. Факты берутся у владельца ({@link RlsDimensionRegistry}): род
     * измерения, правила значения, каталог грантов и место объявления уже разрешены и сверены при
     * старте приложения, поэтому здесь к ним добавляются только ключ грани и текст примечания.
     *
     * <p>Порядок строк задаёт эта сторона: владелец отдаёт {@code Set} без контракта порядка
     * (внутри скана — {@code TreeSet}, наружу — {@code Set.copyOf}), а карточка обязана быть
     * детерминированной.</p>
     */
    private List<EntitySummary.AccessRow> accessRows(Class<?> entityClass) {
        if (rlsDimensionRegistry == null) {
            return List.of();
        }
        RlsPolicyDescriptor policy;
        try {
            policy = rlsDimensionRegistry.policyOf(entityClass);
        } catch (IllegalStateException unregistered) {
            return List.of();
        }
        if (policy.dimensions().isEmpty()) {
            return List.of();
        }
        List<EntitySummary.AccessRow> rows = new ArrayList<>(policy.dimensions().size());
        policy.dimensions().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> rows.add(accessRow(entityClass, entry.getKey(), entry.getValue(),
                policy.valueRules().get(entry.getKey()))));
        return rows;
    }

    private EntitySummary.AccessRow accessRow(Class<?> entityClass, String dimension,
                                              RlsDimensionKind kind,
                                              RlsPolicyDescriptor.ValueRule rule) {
        boolean grantCatalog = rlsDimensionRegistry.grantValueDimensions().contains(dimension);
        return new EntitySummary.AccessRow(
            FacetKey.of(FacetKind.RLS_DIMENSION, entityClass, dimension),
            dimension, kind, grantCatalog,
            ResolvedValue.fact(dimension, FactOrigin.EXPLICIT, entityClass.getName()),
            accessNote(kind, grantCatalog, rule),
            accessRuleRows(entityClass, dimension, rule));
    }

    /**
     * Примечание строки измерения. Различаются три вещи: род измерения (фильтруется против
     * проверяется), сложная политика и то, служит ли измерение источником значений грантов.
     * Значения грантов здесь не называются — они данные, а не конфигурация типа.
     */
    private static String accessNote(RlsDimensionKind dimensionKind, boolean grantCatalog,
                                     RlsPolicyDescriptor.ValueRule rule) {
        StringBuilder note = new StringBuilder(dimensionKind == RlsDimensionKind.CHECK_ONLY
            ? "проверяется, не фильтруется (write-guard и readable-ids)"
            : "фильтруется @Filter с тем же именем; read- и write-предикаты сверены при старте");
        if (rule != null && rule.custom()) {
            note.append("; сложная политика: пути не участвуют, значения поставляет запись"
                + " (RlsDimensionValue), read-предикат объявлен явно");
        }
        if (grantCatalog) {
            note.append("; измерение служит источником значений грантов (значения — данные"
                + " пользователя и в карточке не показываются)");
        }
        return note.toString();
    }

    /**
     * Правила значения измерения: по строке на объявленный путь. У сложной политики строк нет:
     * записанный {@code valuePaths} (дефолт аннотации) там не действует — фильтрация идёт по
     * {@code readCondition}, а значения поставляет запись. Публикация такого дефолта выдала бы
     * недействующий атрибут за правило фильтрации.
     */
    private static List<EntitySummary.AccessRuleRow> accessRuleRows(
            Class<?> entityClass, String dimension, RlsPolicyDescriptor.ValueRule rule) {
        if (rule == null || rule.custom()) {
            return List.of();
        }
        List<EntitySummary.AccessRuleRow> rows = new ArrayList<>(rule.paths().size());
        for (String path : rule.paths()) {
            rows.add(new EntitySummary.AccessRuleRow(
                FacetKey.of(FacetKind.RLS_VALUE_RULE, entityClass, dimension + "/" + path),
                dimension, path, rule.nullsNotApplicable(),
                ResolvedValue.fact(path, FactOrigin.EXPLICIT, entityClass.getName())));
        }
        return rows;
    }

    /**
     * Отказ владельца (класс объявляет измерение, а пакет не попал в
     * {@code rls.dimension-scan-package}) не роняет карточку и не выдаётся за «измерений нет»:
     * строк аспекта нет, а причина идёт диагностикой того же типа, что и остальные стартовые
     * несоответствия. Fail-fast остаётся на путях enforcement — там он защищает права, а не
     * отчётную поверхность.
     */
    private List<EntitySummary.DiagnosticRow> withAccessDiagnostics(
            List<EntitySummary.DiagnosticRow> diagnostics, Class<?> entityClass) {
        if (rlsDimensionRegistry == null) {
            return diagnostics;
        }
        try {
            rlsDimensionRegistry.dimensionsOf(entityClass);
            return diagnostics;
        } catch (IllegalStateException unregistered) {
            List<EntitySummary.DiagnosticRow> rows = new ArrayList<>(diagnostics.size() + 1);
            rows.addAll(diagnostics);
            rows.add(new EntitySummary.DiagnosticRow(
                MetadataDiagnostic.Severity.WARNING, "RLS_SCAN", entityClass.getName(), "",
                "Измерение RLS объявлено, но не зарегистрировано",
                FacetKey.of(FacetKind.RLS_DIMENSION, entityClass),
                ResolvedValue.fact(unregistered.getMessage() == null ? ""
                    : unregistered.getMessage(), FactOrigin.PLATFORM_DEFAULT, ""), ""));
            return List.copyOf(rows);
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<EntityLifecycle<?>> registered(Class<?> entityClass) {
        Class<? extends IdentifiableEntity> entityType =
            (Class<? extends IdentifiableEntity>) entityClass;
        return lifecycleRegistry.find(entityType).map(handler -> (EntityLifecycle<?>) handler);
    }

    private List<EntitySummary.DiagnosticRow> diagnosticsFor(Class<?> entityClass) {
        return diagnosticsByEntity.getOrDefault(entityClass, List.of());
    }

    // === Резолюция надписей ===

    /**
     * Эффективное значение переопределяемой грани: переопределение из {@link FacetResolver},
     * если есть, иначе кодовый дефолт. Вызывается ТОЛЬКО для {@link FacetKind#overridable()}.
     */
    private ResolvedValue resolve(FacetKey key, String codeDefault,
                                  FactOrigin origin, String symbol) {
        if (!key.kind().overridable()) {
            throw new IllegalArgumentException(
                "Resolve is called only for overridable facets, got " + key.kind());
        }
        Optional<String> override = facetResolver.findOverride(key);
        return override
            .map(value -> new ResolvedValue(value, FactSource.OVERRIDE, origin, symbol))
            .orElseGet(() -> ResolvedValue.fact(codeDefault, origin, symbol));
    }

    private DiagnosticProjection projectDiagnostics(List<MetadataDiagnostic> diagnostics) {
        Map<String, Class<?>> cardsByName = new LinkedHashMap<>();
        for (Class<?> type : AnnotationClassScanner.scanAnnotated(basePackage, EntityMetadata.class)) {
            cardsByName.put(type.getName(), type);
        }

        Map<String, TableSectionMetadataInfo> sectionsByRowName = new LinkedHashMap<>();
        if (sectionMetadataRegistry != null) {
            for (TableSectionMetadataInfo section : sectionMetadataRegistry.all()) {
                sectionsByRowName.put(section.getRowClass().getName(), section);
            }
        }

        Map<Class<?>, List<EntitySummary.DiagnosticRow>> assigned = new LinkedHashMap<>();
        List<EntitySummary.DiagnosticRow> unassigned = new ArrayList<>();
        for (MetadataDiagnostic diagnostic : diagnostics) {
            TableSectionMetadataInfo section = sectionsByRowName.get(diagnostic.entity());
            Class<?> cardType;
            Class<?> diagnosticType;
            if (section != null) {
                diagnosticType = section.getRowClass();
                cardType = cardsByName.get(section.getOwnerClass().getName());
            } else {
                diagnosticType = cardsByName.get(diagnostic.entity());
                cardType = diagnosticType;
            }

            EntitySummary.DiagnosticRow row = diagnosticRow(diagnostic, diagnosticType);
            if (cardType == null) {
                unassigned.add(row);
            } else {
                assigned.computeIfAbsent(cardType, ignored -> new ArrayList<>()).add(row);
            }
        }

        Comparator<EntitySummary.DiagnosticRow> order = Comparator
            .comparing(EntitySummary.DiagnosticRow::entityFqn)
            .thenComparing(EntitySummary.DiagnosticRow::fieldName)
            .thenComparing(row -> row.severity().ordinal())
            .thenComparing(EntitySummary.DiagnosticRow::code)
            .thenComparing(EntitySummary.DiagnosticRow::caption);
        Map<Class<?>, List<EntitySummary.DiagnosticRow>> immutableAssigned = new LinkedHashMap<>();
        assigned.forEach((type, rows) -> {
            rows.sort(order);
            immutableAssigned.put(type, List.copyOf(rows));
        });
        unassigned.sort(order);
        return new DiagnosticProjection(Map.copyOf(immutableAssigned), List.copyOf(unassigned));
    }

    private static EntitySummary.DiagnosticRow diagnosticRow(
            MetadataDiagnostic diagnostic, Class<?> diagnosticType) {
        FacetKind kind = diagnosticFacet(diagnostic.code());
        FacetKey key = kind == null || diagnostic.field().isBlank() || diagnosticType == null
            ? null : FacetKey.of(kind, diagnosticType, diagnostic.field());
        return new EntitySummary.DiagnosticRow(
            diagnostic.severity(), diagnostic.code(), diagnostic.entity(), diagnostic.field(),
            diagnostic.severity() + " [" + diagnostic.code() + "]", key,
            ResolvedValue.fact(diagnostic.message(), FactOrigin.DERIVED, ""),
            diagnostic.source());
    }

    /**
     * Только известные коды получают FacetKey. Коды политики исключений и будущие коды
     * остаются видимыми на уровне исходной сущности/поля без выдуманной грани.
     */
    private static FacetKind diagnosticFacet(String code) {
        return switch (code) {
            case MetadataDiagnosticCodes.UI_OPTIONAL_SERVER_REQUIRED,
                 MetadataDiagnosticCodes.UI_REQUIRED_SERVER_OPTIONAL,
                 MetadataDiagnosticCodes.REDUNDANT_REQUIRED,
                 MetadataDiagnosticCodes.REDUNDANT_TYPE,
                 MetadataDiagnosticCodes.TYPE_CONFLICT,
                 MetadataDiagnosticCodes.FALLBACK_TYPE -> FacetKind.FIELD_STRUCTURE;
            case MetadataDiagnosticCodes.REDUNDANT_LOOKUP_TARGET,
                 MetadataDiagnosticCodes.REFERENCE_CONFLICT,
                 MetadataDiagnosticCodes.REFERENCE_TARGET_NOT_METADATA -> FacetKind.LOOKUP_TARGET;
            default -> null;
        };
    }

    private record DiagnosticProjection(
            Map<Class<?>, List<EntitySummary.DiagnosticRow>> byEntity,
            List<EntitySummary.DiagnosticRow> unassigned) {
        private static DiagnosticProjection empty() {
            return new DiagnosticProjection(Map.of(), List.of());
        }
    }

}

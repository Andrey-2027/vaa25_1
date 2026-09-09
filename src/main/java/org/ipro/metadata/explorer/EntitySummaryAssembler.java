package org.ipro.metadata.explorer;

import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormType;
import org.ipro.metadata.AnnotationClassScanner;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
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
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.numbering.NumberingPeriod;
import org.ipro.numbering.annotation.Numbered;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;

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

    public EntitySummaryAssembler(
            String basePackage,
            MetadataResolver metadataResolver,
            FormRegistry formRegistry,
            ReferenceIndex referenceIndex,
            NumberingMetadataRegistry numberingMetadataRegistry,
            SubsystemRegistry subsystemRegistry,
            FacetResolver facetResolver) {
        this.basePackage = basePackage;
        this.metadataResolver = metadataResolver;
        this.formRegistry = formRegistry;
        this.referenceIndex = referenceIndex;
        this.numberingMetadataRegistry = numberingMetadataRegistry;
        this.subsystemRegistry = subsystemRegistry;
        this.facetResolver = facetResolver;
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
            result.add(new EntityRef(entityClass, simpleName,
                resolve(FacetKey.of(FacetKind.ENTITY_LIST_TITLE, entityClass), codeTitle)));
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
        ResolvedValue displayName = resolve(FacetKey.of(FacetKind.ENTITY_LIST_TITLE, entityClass), codeTitle);

        List<EntitySummary.OverviewRow> overview = overviewRows(entityClass, meta, annotation, simpleName);

        List<EntitySummary.FieldRow> fieldsForm = fieldRows(
            meta.getFormFields(), entityClass, FacetKind.FIELD_LABEL);
        List<EntitySummary.FieldRow> fieldsGrid = fieldRows(
            meta.getGridFields(), entityClass, FacetKind.GRID_COLUMN_HEADER);

        List<EntitySummary.ColumnRow> listColumns = columnRows(entityClass, meta.getListColumnPaths());
        List<EntitySummary.ColumnRow> selectColumns = columnRows(entityClass, meta.getSelectColumnPaths());

        List<EntitySummary.SectionRow> tableSections = sectionRows(entityClass);
        List<EntitySummary.FormRow> forms = formRows(entityClass);
        List<EntitySummary.FilterRow> contextFilters = contextFilterRows(entityClass);
        List<EntitySummary.SelectionRow> selections = selectionRows(entityClass);
        List<EntitySummary.ReferenceRow> references = referenceRows(entityClass);
        List<EntitySummary.NumberingRow> numbering = numberingRows(entityClass);

        return new EntitySummary(
            entityClass, simpleName, displayName,
            overview, fieldsForm, fieldsGrid, listColumns, selectColumns,
            tableSections, forms, contextFilters, selections, references, numbering);
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

    // === Обзор ===

    private List<EntitySummary.OverviewRow> overviewRows(
            Class<?> entityClass, EntityMetadataInfo meta, EntityMetadata annotation, String simpleName) {
        List<EntitySummary.OverviewRow> rows = new ArrayList<>(4);

        // Подсистема (членство — переопределяемая грань роли 3).
        Class<?> marker = annotation.subsystem();
        String subsystemCode = "";
        boolean hasSubsystem = marker != Subsystem.NoSubsystem.class;
        if (hasSubsystem) {
            String title = subsystemRegistry.findByMarker(marker)
                .map(SubsystemNode::getTitle).orElse(null);
            subsystemCode = title != null ? title : marker.getSimpleName();
        }
        rows.add(new EntitySummary.OverviewRow("Подсистема",
            FacetKey.of(FacetKind.SUBSYSTEM_MEMBERSHIP, entityClass),
            resolve(FacetKey.of(FacetKind.SUBSYSTEM_MEMBERSHIP, entityClass), subsystemCode)));

        String itemCode = annotation.itemFormTitle().isBlank()
            ? annotation.listFormTitle().isBlank() ? simpleName : annotation.listFormTitle()
            : annotation.itemFormTitle();
        rows.add(new EntitySummary.OverviewRow("Заголовок формы элемента",
            FacetKey.of(FacetKind.ENTITY_ITEM_TITLE, entityClass),
            resolve(FacetKey.of(FacetKind.ENTITY_ITEM_TITLE, entityClass), itemCode)));

        String selectionCode = annotation.selectionFormTitle().isBlank()
            ? annotation.listFormTitle().isBlank() ? simpleName : annotation.listFormTitle()
            : annotation.selectionFormTitle();
        rows.add(new EntitySummary.OverviewRow("Заголовок формы выбора",
            FacetKey.of(FacetKind.ENTITY_SELECTION_TITLE, entityClass),
            resolve(FacetKey.of(FacetKind.ENTITY_SELECTION_TITLE, entityClass), selectionCode)));
        return rows;
    }

    // === Поля ===

    private List<EntitySummary.FieldRow> fieldRows(
            List<FieldMetadataInfo> fields, Class<?> entityClass, FacetKind kind) {
        List<EntitySummary.FieldRow> rows = new ArrayList<>(fields.size());
        for (FieldMetadataInfo field : fields) {
            FacetKey key = FacetKey.of(kind, entityClass, field.getName());
            rows.add(new EntitySummary.FieldRow(
                key,
                field.getName(),
                typeLabel(field.getResolvedType()),
                resolve(key, field.getLabel()),
                field.isRequired(),
                field.isReadOnly(),
                lookupTarget(field),
                field.hasLookup() ? field.getLookupEntity() : null));
        }
        return rows;
    }

    private static String lookupTarget(FieldMetadataInfo field) {
        if (!field.hasLookup()) {
            return "";
        }
        String target = field.getLookupEntity() == null ? "?" : field.getLookupEntity().getSimpleName();
        if (field.getLookupVariant() != null && !field.getLookupVariant().isEmpty()) {
            target = target + " [" + field.getLookupVariant() + "]";
        }
        return target;
    }

    private static String typeLabel(FieldType type) {
        return type == null ? "" : type.name();
    }

    // === Колонки списка/выбора ===

    private List<EntitySummary.ColumnRow> columnRows(Class<?> entityClass, List<ColumnPath> paths) {
        List<EntitySummary.ColumnRow> rows = new ArrayList<>(paths.size());
        for (ColumnPath path : paths) {
            FacetKey key = FacetKey.of(FacetKind.GRID_COLUMN_HEADER, entityClass, path.getKey());
            rows.add(new EntitySummary.ColumnRow(
                key,
                path.getKey(),
                resolve(key, path.getLabel()),
                typeLabel(path.getResolvedType()),
                path.isNested()));
        }
        return rows;
    }

    // === Табличные части ===

    private List<EntitySummary.SectionRow> sectionRows(Class<?> entityClass) {
        List<EntitySummary.SectionRow> rows = new ArrayList<>();
        for (TableSectionMetadataInfo section : metadataResolver.resolveTableSections(entityClass)) {
            FacetKey key = FacetKey.of(FacetKind.TABLE_SECTION, entityClass,
                section.getRowClass().getSimpleName());
            rows.add(new EntitySummary.SectionRow(
                key,
                ResolvedValue.code(section.getTitle()),
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
                    "платформенная автогенерация", true, ""));
            } else {
                rows.add(new EntitySummary.FormRow(formType, null,
                    registrationKindLabel(defaults), false, sourceLabel(defaults)));
            }
            // Именованные варианты.
            for (java.util.Map.Entry<FormKey, List<FormRegistry.Registration>> entry : byKey.entrySet()) {
                FormKey key = entry.getKey();
                if (key.formType == formType && key.variant != null) {
                    rows.add(new EntitySummary.FormRow(formType, key.variant,
                        registrationKindLabel(entry.getValue()), false, sourceLabel(entry.getValue())));
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
        String sourcePath = sourceFqn == null || sourceFqn.isBlank() ? "" : toSourcePath(sourceFqn);
        return new EntitySummary.FilterRow(
            key,
            filter.path(),
            resolve(key, codeDefault),
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
            rows.add(new EntitySummary.SelectionRow(
                registration.variant(),
                ResolvedValue.code(codeTitle),
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
                ResolvedValue.code(value)));
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
            Numbered annotation = info.annotation();
            FacetKey key = FacetKey.of(FacetKind.NUMBERING_DECL, entityClass, info.fieldName());
            String scope = annotation.scope().length == 0
                ? "GLOBAL"
                : String.join(", ", annotation.scope());
            rows.add(new EntitySummary.NumberingRow(
                key,
                info.fieldName(),
                ResolvedValue.code(info.fieldName()),
                scope,
                periodLabel(annotation.period()),
                annotation.allowManual()));
        }
        rows.sort(Comparator.comparing(EntitySummary.NumberingRow::fieldName));
        return rows;
    }

    private static String periodLabel(NumberingPeriod period) {
        return period == null ? "" : period.name();
    }

    // === Резолюция надписей ===

    /**
     * Эффективное значение переопределяемой грани: переопределение из {@link FacetResolver},
     * если есть, иначе кодовый дефолт. Вызывается ТОЛЬКО для {@link FacetKind#overridable()}.
     */
    private ResolvedValue resolve(FacetKey key, String codeDefault) {
        if (!key.kind().overridable()) {
            throw new IllegalArgumentException(
                "Resolve is called only for overridable facets, got " + key.kind());
        }
        Optional<String> override = facetResolver.findOverride(key);
        return override
            .map(value -> new ResolvedValue(value, FactSource.OVERRIDE))
            .orElseGet(() -> ResolvedValue.code(codeDefault));
    }

}

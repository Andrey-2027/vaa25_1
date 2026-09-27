package org.ip.vaadin.explorer;

import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormType;
import org.ipro.form.registry.SelectionColumnsDef;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.AnnotationClassScanner;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.metadata.facet.FactSource;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.metadata.FactOrigin;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.settings.SettingsReverseReferenceSource;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ip.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест {@link EntitySummaryAssembler} (срез 1 Entity Explorer): сводка сущности
 * собирается из уже резолвнутых реестров платформы (MetadataResolver, FormRegistry,
 * ReferenceIndex, NumberingMetadataRegistry, SubsystemRegistry) — на реальных сущностях
 * приложения (basePackage {@code org.ip}), как принято в юнит-тестах платформы.
 */
class EntitySummaryAssemblerTest {

    private FormRegistry formRegistry;
    private EntitySummaryAssembler assembler;

    @BeforeEach
    void setUp() {
        MetadataResolver metadataResolver = new MetadataResolver();
        formRegistry = new FormRegistry();
        ReferenceIndex referenceIndex = new ReferenceIndex("org.ip");
        referenceIndex.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry("org.ip");
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry("org.ip");
        subsystems.afterPropertiesSet();
        assembler = assembler("org.ip", metadataResolver, formRegistry,
                referenceIndex, numbering, subsystems, FacetResolver.none());
    }

    // ---------------------------------------------------------------- перечень

    @Test
    void entitiesAreSortedByDisplayNameAndDeterministic() {
        List<EntitySummaryAssembler.EntityRef> entities = assembler.entities();

        assertThat(entities).isNotEmpty();
        assertThat(entities).anyMatch(r -> r.entityClass() == Nomenclature.class);
        assertThat(entities).anyMatch(r -> r.entityClass() == ReceivingDocument.class);

        for (int i = 0; i < entities.size() - 1; i++) {
            String a = entities.get(i).displayName().value().toLowerCase();
            String b = entities.get(i + 1).displayName().value().toLowerCase();
            assertThat(a.compareTo(b)).isLessThanOrEqualTo(0);
        }
        assertThat(assembler.entities()).isEqualTo(entities);
    }

    // ---------------------------------------------------------------- полная сводка

    @Test
    void fullSummaryAssemblesAllFacetsForDocumentEntity() {
        EntitySummary summary = assembler.summarize(ReceivingDocument.class);

        assertThat(summary.displayName().value()).isNotBlank();
        assertThat(summary.displayName().source()).isEqualTo(FactSource.CODE);

        // Обзор: подсистема + заголовки форм.
        assertThat(summary.overview()).anyMatch(r ->
            "Подсистема".equals(r.caption()) && !r.value().value().isBlank());
        assertThat(summary.overview())
            .extracting(r -> r.key().kind())
            .contains(FacetKind.ENTITY_KIND, FacetKind.ENTITY_EXPOSURE,
                FacetKind.ENTITY_KEY, FacetKind.LINKABILITY)
            .contains(FacetKind.ENTITY_ITEM_TITLE, FacetKind.ENTITY_SELECTION_TITLE);

        // Поля обеих проекций.
        assertThat(summary.fieldsForm()).isNotEmpty();
        assertThat(summary.fieldsGrid()).isNotEmpty();
        assertThat(summary.fieldsForm())
            .extracting(r -> r.key().kind())
            .containsOnly(FacetKind.FIELD_LABEL);
        assertThat(summary.fieldsGrid())
            .extracting(r -> r.key().kind())
            .containsOnly(FacetKind.GRID_COLUMN_HEADER);

        // Табличная часть ReceivingDocumentItem.
        assertThat(summary.tableSections()).anyMatch(r ->
            "ReceivingDocumentItem".equals(r.rowClass()) && !r.value().value().isBlank());
        assertThat(summary.lifecycle()).isEmpty();
        assertThat(summary.diagnostics()).isEmpty();

        // Нумерация: number, scope JOURNAL, период YEAR.
        assertThat(summary.numbering()).anyMatch(r ->
            "number".equals(r.fieldName())
                && "JOURNAL".equals(r.scope())
                && "YEAR".equals(r.period()));

        // Пустой реестр форм -> только платформенные дефолты (3 типа).
        assertThat(summary.forms()).hasSize(3);
        assertThat(summary.forms())
            .filteredOn(EntitySummary.FormRow::platformDefault)
            .extracting(r -> r.formType())
            .containsExactlyInAnyOrder(FormType.LIST, FormType.ITEM, FormType.SELECTION);
    }

    @Test
    void effectiveFactsRetainOriginAndCheckedJavaSymbols() {
        EntitySummary nomenclature = assembler.summarize(Nomenclature.class);
        assertThat(nomenclature.displayName().origin()).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(nomenclature.displayName().symbol()).isEqualTo(Nomenclature.class.getName());
        assertThat(assembler.entities())
            .filteredOn(ref -> ref.entityClass() == Nomenclature.class)
            .singleElement()
            .satisfies(ref -> assertThat(ref.displayName().origin()).isEqualTo(FactOrigin.EXPLICIT));

        assertThat(nomenclature.overview())
            .filteredOn(row -> row.key().kind() == FacetKind.ENTITY_KIND)
            .singleElement()
            .satisfies(row -> {
                assertThat(row.value().origin()).isEqualTo(FactOrigin.EXPLICIT);
                assertThat(row.value().symbol()).isEqualTo(Nomenclature.class.getName());
            });
        assertThat(nomenclature.overview())
            .filteredOn(row -> row.key().kind() == FacetKind.SUBSYSTEM_MEMBERSHIP)
            .singleElement()
            .satisfies(row -> {
                assertThat(row.value().origin()).isEqualTo(FactOrigin.REGISTRATION);
                assertThat(row.value().symbol()).isEqualTo(
                    org.ip.subsystem.Subsystems.Directories.class.getName());
            });

        assertThat(nomenclature.fieldsForm())
            .filteredOn(row -> row.name().equals("code"))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.value().origin()).isEqualTo(FactOrigin.EXPLICIT);
                assertThat(row.value().symbol()).isEqualTo(Nomenclature.class.getName() + "#code");
                assertThat(row.requiredOrigin()).isIn(FactOrigin.EXPLICIT,
                    FactOrigin.BEAN_VALIDATION, FactOrigin.JPA_MAPPING);
                assertThat(row.typeOrigin()).isIn(FactOrigin.JAVA_TYPE, FactOrigin.EXPLICIT);
            });

        EntitySummary prdSpecMtr = assembler.summarize(org.ip.model.PrdSpecMtr.class);
        assertThat(prdSpecMtr.overview())
            .filteredOn(row -> row.key().kind() == FacetKind.ENTITY_SELECTION_TITLE)
            .singleElement()
            .satisfies(row -> assertThat(row.value().origin()).isEqualTo(FactOrigin.DERIVED));
        assertThat(prdSpecMtr.overview())
            .filteredOn(row -> row.key().kind() == FacetKind.ENTITY_KEY)
            .singleElement()
            .satisfies(row -> {
                assertThat(row.value().origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
                assertThat(row.value().value()).isEmpty();
            });
        assertThat(prdSpecMtr.overview())
            .filteredOn(row -> row.key().kind() == FacetKind.LINKABILITY)
            .allSatisfy(row -> {
                assertThat(row.value().value()).isEqualTo("Недоступна");
                assertThat(row.detail()).isEqualTo("NOT_PUBLISHED");
            });

        EntitySummary receiving = assembler.summarize(ReceivingDocument.class);
        assertThat(receiving.listColumns())
            .filteredOn(row -> row.path().equals("journal.code"))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.value().origin()).isEqualTo(FactOrigin.DERIVED);
                assertThat(row.value().symbol()).isEmpty();
            });
        assertThat(receiving.listColumns())
            .filteredOn(row -> row.path().equals("number"))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.value().origin()).isEqualTo(FactOrigin.EXPLICIT);
                assertThat(row.value().symbol()).isEqualTo(ReceivingDocument.class.getName() + "#number");
            });
        assertThat(receiving.listColumns())
            .filteredOn(row -> row.path().equals("id"))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.value().origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
                assertThat(row.value().symbol())
                    .isEqualTo(org.ipro.crud.BaseEntity.class.getName() + "#id");
            });
        assertThat(receiving.selectColumns())
            .allSatisfy(row -> assertThat(row.note()).isEqualTo("использует колонки списка"));
        assertThat(receiving.tableSections())
            .filteredOn(row -> row.rowClass().equals("ReceivingDocumentItem"))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.value().origin()).isEqualTo(FactOrigin.EXPLICIT);
                assertThat(row.value().symbol()).isEqualTo(
                    org.ip.model.ReceivingDocumentItem.class.getName());
            });

        EntitySummary inherited = assembler.summarize(InheritedCatalogFixture.class);
        assertThat(inherited.overview())
            .filteredOn(row -> row.key().kind() == FacetKind.ENTITY_KIND)
            .singleElement()
            .satisfies(row -> {
                assertThat(row.value().origin()).isEqualTo(FactOrigin.DERIVED);
                assertThat(row.value().symbol())
                    .isEqualTo(org.ipro.crud.StandardCatalogEntity.class.getName());
            });
        assertThat(inherited.fieldsForm())
            .filteredOn(row -> row.name().equals("code"))
            .singleElement()
            .satisfies(row -> assertThat(row.value().symbol())
                .isEqualTo(org.ipro.crud.StandardCatalogEntity.class.getName() + "#code"));
    }

    @Test
    void missingStartupCheckIsVisibleAsAnUnassignedServiceDiagnostic() {
        assertThat(assembler.unassignedDiagnostics())
            .singleElement()
            .satisfies(row -> {
                assertThat(row.code()).isEqualTo("DIAGNOSTICS_UNAVAILABLE");
                assertThat(row.value().value()).isEqualTo("Стартовая проверка не подключена");
                assertThat(row.key()).isNull();
            });
    }

    @Test
    void nonEmptySymbolsResolveToClassesAndFieldsOnTheClasspath() throws Exception {
        List<ResolvedValue> facts = new java.util.ArrayList<>();
        facts.addAll(facts(assembler.summarize(Nomenclature.class)));
        facts.addAll(facts(assembler.summarize(ReceivingDocument.class)));
        facts.addAll(facts(assembler.summarize(InheritedCatalogFixture.class)));

        for (ResolvedValue fact : facts) {
            if (fact.symbol().isBlank()) {
                continue;
            }
            String[] parts = fact.symbol().split("#", 2);
            Class<?> owner = Class.forName(parts[0]);
            if (parts.length == 2) {
                assertThat(owner.getDeclaredField(parts[1])).isNotNull();
            }
        }
    }

    @Test
    void knownRegistrationOriginDoesNotRequireARegistrationSymbol() {
        FormRegistry forms = new FormRegistry();
        forms.register(Nomenclature.class, FormType.ITEM, "no-source", context -> null);
        MetadataResolver metadataResolver = new MetadataResolver();
        ReferenceIndex references = new ReferenceIndex("org.ip");
        references.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry("org.ip");
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry("org.ip");
        subsystems.afterPropertiesSet();
        EntitySummaryAssembler local = assembler("org.ip", metadataResolver, forms,
            references, numbering, subsystems, FacetResolver.none());

        assertThat(local.summarize(Nomenclature.class).forms())
            .filteredOn(row -> "no-source".equals(row.variant()))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.origin()).isEqualTo(FactOrigin.REGISTRATION);
                assertThat(row.symbol()).isEmpty();
            });
    }

    @Test
    void unknownOriginIsLimitedToCompatibilityFactories() {
        assertThat(ResolvedValue.code("legacy").origin()).isEqualTo(FactOrigin.UNKNOWN);
        assertThat(new ResolvedValue("legacy", FactSource.CODE).origin())
            .isEqualTo(FactOrigin.UNKNOWN);

        List<ResolvedValue> facts = facts(assembler.summarize(Nomenclature.class));
        assertThat(facts).allSatisfy(fact ->
            assertThat(fact.origin()).isNotEqualTo(FactOrigin.UNKNOWN));
        assertThat(assembler.summarize(Nomenclature.class).fieldsForm())
            .allSatisfy(row -> {
                assertThat(row.requiredOrigin()).isNotEqualTo(FactOrigin.UNKNOWN);
                assertThat(row.typeOrigin()).isNotEqualTo(FactOrigin.UNKNOWN);
            });
    }

    private static List<ResolvedValue> facts(EntitySummary summary) {
        List<ResolvedValue> values = new java.util.ArrayList<>();
        values.add(summary.displayName());
        summary.overview().forEach(row -> values.add(row.value()));
        summary.fieldsForm().forEach(row -> values.add(row.value()));
        summary.fieldsGrid().forEach(row -> values.add(row.value()));
        summary.listColumns().forEach(row -> values.add(row.value()));
        summary.selectColumns().forEach(row -> values.add(row.value()));
        summary.tableSections().forEach(row -> values.add(row.value()));
        summary.contextFilters().forEach(row -> values.add(row.value()));
        summary.selections().forEach(row -> values.add(row.value()));
        summary.references().forEach(row -> values.add(row.value()));
        summary.numbering().forEach(row -> values.add(row.value()));
        summary.lifecycle().forEach(row -> values.add(row.value()));
        summary.diagnostics().forEach(row -> values.add(row.value()));
        return values;
    }

    @Test
    void declaredSectionsAreResolvedAndEmptyCollectionsAreNotNull() {
        EntitySummary summary = assembler.summarize(Nomenclature.class);

        assertThat(summary.fieldsForm()).isNotEmpty();
        assertThat(summary.tableSections()).singleElement().satisfies(section ->
            assertThat(section.rowClass()).isEqualTo("NomAttributeValue"));
        assertThat(summary.contextFilters()).isEmpty();
        assertThat(summary.selections()).isEmpty();
        assertThat(summary.numbering()).extracting(EntitySummary.NumberingRow::fieldName)
            .contains("code");
    }

    @Test
    void registryRegistrationsSurfaceInFormsFiltersSelections() {
        formRegistry.registerContextFilters(ReceivingDocument.class,
            List.of(ContextFilterField.auto("number", "Номер документа")),
            "org.ip.views.forms.ReceivingDocumentFormConfig");
        formRegistry.registerVariantContextFilters(ReceivingDocument.class, FormType.LIST,
            "compact", List.of(ContextFilterField.auto("number", "Номер")),
            "org.ip.views.forms.ReceivingDocumentFormConfig");
        formRegistry.registerSelectionColumns(ReceivingDocument.class, "compact",
            SelectionColumnsDef.of(List.of("number", "nomenclature"), "Кратко"),
            "org.ip.views.forms.ReceivingDocumentSelectionConfig");
        formRegistry.registerListForm(ReceivingDocument.class, "compact", ctx -> null,
            "org.ip.views.forms.ReceivingDocumentFormConfig");

        EntitySummary summary = assembler.summarize(ReceivingDocument.class);

        // Контекст-фильтры: общий ряд + ряд варианта, источник — путь к конфигу.
        assertThat(summary.contextFilters()).anyMatch(r ->
            "number".equals(r.path())
                && "список (общий)".equals(r.scope())
                && "Номер документа".equals(r.value().value())
                && r.value().source() == FactSource.CODE
                && "org/ip/views/forms/ReceivingDocumentFormConfig.java".equals(r.source()));
        assertThat(summary.contextFilters()).anyMatch(r ->
            "number".equals(r.path())
                && r.scope().startsWith("список, вариант «compact»")
                && "org/ip/views/forms/ReceivingDocumentFormConfig.java".equals(r.source()));

        // Selection-набор.
        assertThat(summary.selections()).anyMatch(r ->
            "compact".equals(r.variant())
                && "Кратко".equals(r.value().value())
                && List.of("number", "nomenclature").equals(r.columns())
                && r.registered());

        // Формы: вариант «compact» + незанятый default -> платформенный дефолт рядом.
        assertThat(summary.forms()).anyMatch(r ->
            r.formType() == FormType.LIST
                && "compact".equals(r.variant())
                && !r.platformDefault()
                && r.registrationKind().contains("кастомная фабрика"));
        assertThat(summary.forms()).anyMatch(r ->
            r.formType() == FormType.LIST
                && r.variant() == null
                && r.platformDefault());
        assertThat(summary.forms()).anyMatch(r ->
            r.formType() == FormType.SELECTION
                && "compact".equals(r.variant())
                && r.registrationKind().contains("набор колонок выбора"));

        // Конкретный источник «где явно»: пути к .java-файлам классов-декларантов.
        assertThat(summary.forms()).anyMatch(r ->
            r.formType() == FormType.LIST
                && "compact".equals(r.variant())
                && "org/ip/views/forms/ReceivingDocumentFormConfig.java".equals(r.source()));
        assertThat(summary.forms()).anyMatch(r ->
            r.formType() == FormType.SELECTION
                && "compact".equals(r.variant())
                && "org/ip/views/forms/ReceivingDocumentSelectionConfig.java".equals(r.source()));
        // Платформенные дефолты источника не несут.
        assertThat(summary.forms())
            .filteredOn(EntitySummary.FormRow::platformDefault)
            .allMatch(r -> r.source() == null || r.source().isBlank());
    }

    @Test
    void referencesSectionShowsSettingsColumnRef() {
        ReferenceIndex index = new ReferenceIndex("org.ip",
            List.of(new SettingsReverseReferenceSource("org.ip.settings")));
        index.afterPropertiesSet();

        EntitySummary summary = summarizeWith(index, User.class);

        assertThat(summary.references()).anyMatch(r ->
            r.referencingClass().getName().endsWith("SettingValue")
                && "entityRefId".equals(r.fieldName())
                && r.columnRef());
        assertThat(summary.references())
            .extracting(r -> r.key().kind())
            .containsOnly(FacetKind.REVERSE_REFERENCE);
    }

    // ---------------------------------------------------------------- чистота/стабильность

    @Test
    void summarizeIsPureAndRegistryUntouched() {
        int registrationsBefore = formRegistry.registrationsOf(ReceivingDocument.class).size();

        EntitySummary first = assembler.summarize(ReceivingDocument.class);
        EntitySummary second = assembler.summarize(ReceivingDocument.class);

        assertThat(second).isEqualTo(first);
        assertThat(formRegistry.registrationsOf(ReceivingDocument.class)).hasSize(registrationsBefore);
    }

    @Test
    void reverseReferencesAreDeterministicallySorted() {
        List<EntitySummary.ReferenceRow> references =
            assembler.summarize(Nomenclature.class).references();

        for (int i = 0; i < references.size() - 1; i++) {
            String a = references.get(i).referencingClass().getSimpleName();
            String b = references.get(i + 1).referencingClass().getSimpleName();
            int cmp = a.compareTo(b);
            assertThat(cmp).isLessThanOrEqualTo(0);
            if (cmp == 0) {
                assertThat(references.get(i).fieldName())
                    .isLessThanOrEqualTo(references.get(i + 1).fieldName());
            }
        }
    }

    private EntitySummary summarizeWith(ReferenceIndex index, Class<?> entityClass) {
        MetadataResolver metadataResolver = new MetadataResolver();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry("org.ip");
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry("org.ip");
        subsystems.afterPropertiesSet();
        EntitySummaryAssembler custom = assembler("org.ip", metadataResolver,
            formRegistry, index, numbering, subsystems, FacetResolver.none());
        return custom.summarize(entityClass);
    }

    // ---------------------------------------------------------------- резолюция (FacetResolver)

    @Test
    void overridableFacetsResolveThroughFacetResolver() {
        FacetResolver resolver = key -> {
            if (key.kind() == FacetKind.ENTITY_LIST_TITLE
                    && key.entityClass() == ReceivingDocument.class) {
                return Optional.of("Накладные (переопределено)");
            }
            if (key.kind() == FacetKind.FIELD_LABEL
                    && key.entityClass() == ReceivingDocument.class
                    && "number".equals(key.fieldName())) {
                return Optional.of("Номер (переопределено)");
            }
            return Optional.empty();
        };

        EntitySummary summary = summarizeWithResolver(resolver, ReceivingDocument.class);

        assertThat(summary.displayName().value()).isEqualTo("Накладные (переопределено)");
        assertThat(summary.displayName().source()).isEqualTo(FactSource.OVERRIDE);

        assertThat(summary.fieldsForm())
            .filteredOn(r -> "number".equals(r.name()))
            .singleElement()
            .satisfies(r -> {
                assertThat(r.value().value()).isEqualTo("Номер (переопределено)");
                assertThat(r.value().source()).isEqualTo(FactSource.OVERRIDE);
            });

        // Грань без переопределения (заголовок колонки грида) — кодовый дефолт.
        assertThat(summary.fieldsGrid())
            .filteredOn(r -> "number".equals(r.name()))
            .singleElement()
            .satisfies(r -> {
                assertThat(r.value().source()).isEqualTo(FactSource.CODE);
            });
    }

    @Test
    void resolverIsNeverAskedForStructuralFacets() {
        // Резолвер, который «готов переопределить всё», включая структурные ключи.
        FacetResolver resolver = key -> {
            assertThat(key.kind().overridable()).as("резолвер вызван для %s", key.kind()).isTrue();
            return Optional.empty();
        };

        EntitySummary summary = summarizeWithResolver(resolver, ReceivingDocument.class);

        // Структурные грани не резолвятся и показываются как есть, источник «код».
        assertThat(summary.tableSections()).isNotEmpty();
        assertThat(summary.tableSections())
            .extracting(EntitySummary.FacetRow::value)
            .extracting(org.ipro.metadata.facet.ResolvedValue::source)
            .containsOnly(FactSource.CODE);
        assertThat(summary.numbering())
            .extracting(EntitySummary.FacetRow::value)
            .extracting(org.ipro.metadata.facet.ResolvedValue::source)
            .containsOnly(FactSource.CODE);
    }

    @Test
    void noOpFacetResolverReturnsEmpty() {
        FacetResolver none = FacetResolver.none();
        assertThat(none.findOverride(
            FacetKey.of(FacetKind.ENTITY_LIST_TITLE, Nomenclature.class))).isEmpty();
    }

    private EntitySummary summarizeWithResolver(FacetResolver resolver, Class<?> entityClass) {
        MetadataResolver metadataResolver = new MetadataResolver();
        ReferenceIndex referenceIndex = new ReferenceIndex("org.ip");
        referenceIndex.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry("org.ip");
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry("org.ip");
        subsystems.afterPropertiesSet();
        EntitySummaryAssembler custom = assembler("org.ip", metadataResolver,
            formRegistry, referenceIndex, numbering, subsystems, resolver);
        return custom.summarize(entityClass);
    }

    static EntitySummaryAssembler assembler(
            String basePackage,
            MetadataResolver metadataResolver,
            FormRegistry formRegistry,
            ReferenceIndex referenceIndex,
            NumberingMetadataRegistry numbering,
            SubsystemRegistry subsystems,
            FacetResolver facetResolver) {
        SectionMetadataRegistry sections = new SectionMetadataRegistry(basePackage, metadataResolver);
        sections.afterPropertiesSet();
        Set<Class<?>> managedTypes = new LinkedHashSet<>(
            AnnotationClassScanner.scanAnnotated(basePackage, EntityMetadata.class));
        sections.all().forEach(section -> managedTypes.add(section.getRowClass()));
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(managedTypes);
        EntityDescriptorCatalog descriptors = new EntityDescriptorCatalog(
            managed, sections, metadataResolver, List.of(), List.of());
        FormRouteCatalog routes = FormRouteCatalog.build(descriptors, formRegistry, List.of());
        return new EntitySummaryAssembler(basePackage, metadataResolver, formRegistry,
            referenceIndex, numbering, subsystems, facetResolver, descriptors, routes,
            new EntityLifecycleRegistry(List.of()), sections, null);
    }

    @EntityMetadata(listFormTitle = "Унаследованный справочник")
    static class InheritedCatalogFixture extends org.ipro.crud.StandardCatalogEntity {
    }
}

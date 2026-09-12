package org.ipro.metadata.explorer;

import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormType;
import org.ipro.form.registry.SelectionColumnsDef;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.metadata.facet.FactSource;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.settings.SettingsReverseReferenceSource;
import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ip.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
        assembler = new EntitySummaryAssembler("org.ip", metadataResolver, formRegistry,
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
            .allMatch(FacetKind::overridable);

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
        EntitySummaryAssembler custom = new EntitySummaryAssembler("org.ip", metadataResolver,
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
        EntitySummaryAssembler custom = new EntitySummaryAssembler("org.ip", metadataResolver,
            formRegistry, referenceIndex, numbering, subsystems, resolver);
        return custom.summarize(entityClass);
    }
}

package org.ip.vaadin.explorer;

import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormType;
import org.ipro.form.registry.SelectionColumnsDef;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.AnnotationClassScanner;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.MetadataDiagnostic;
import org.ipro.metadata.MetadataDiagnosticCodes;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityKind;
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
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import org.ip.application.catalog.NomenclatureLifecycle;
import org.ip.model.NomAttributeValue;
import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ip.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
        // Сводка собрана без прикладного registry: lifecycle виден одной строкой состояния,
        // а не «правил нет» (подробности — lifecycleHandlerRowCarriesRegistration...).
        assertThat(summary.lifecycle()).singleElement().satisfies(row ->
            assertThat(row.value().value()).isEqualTo("handler в реестре не зарегистрирован"));
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
    void startupDiagnosticsAreMappedToFieldsAndSectionOwnerWithoutLoss() {
        List<MetadataDiagnostic> snapshot = List.of(
            new MetadataDiagnostic(MetadataDiagnostic.Severity.INFO,
                MetadataDiagnosticCodes.REDUNDANT_REQUIRED,
                ReceivingDocument.class.getName(), "number", "@FieldMetadata.required",
                "явное REQUIRED совпадает с контрактом записи"),
            new MetadataDiagnostic(MetadataDiagnostic.Severity.INFO,
                MetadataDiagnosticCodes.REDUNDANT_TYPE,
                ReceivingDocument.class.getName(), "date", "@FieldMetadata.type",
                "явный тип совпадает с выводом из Java-типа"),
            new MetadataDiagnostic(MetadataDiagnostic.Severity.WARNING,
                MetadataDiagnosticCodes.REFERENCE_TARGET_NOT_METADATA,
                ReceivingDocumentItem.class.getName(), "nomenclature", "@Lookup / тип ссылки",
                "цель выбора не объявлена как metadata-driven"),
            new MetadataDiagnostic(MetadataDiagnostic.Severity.WARNING,
                "FUTURE_DIAGNOSTIC_CODE", ReceivingDocument.class.getName(), "futureField",
                "validator", "новая диагностика без известной грани"),
            new MetadataDiagnostic(MetadataDiagnostic.Severity.INFO,
                "EXTERNAL_DIAGNOSTIC", "external.module.HiddenEntity", "field",
                "validator", "тип не представлен карточкой Explorer"));
        EntitySummaryAssembler withDiagnostics = assemblerWithDiagnostics(snapshot);

        EntitySummary summary = withDiagnostics.summarize(ReceivingDocument.class);
        assertThat(summary.diagnostics())
            .filteredOn(row -> row.code().equals(MetadataDiagnosticCodes.REDUNDANT_REQUIRED))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.key()).isEqualTo(FacetKey.of(FacetKind.FIELD_STRUCTURE,
                    ReceivingDocument.class, "number"));
                assertThat(row.entityFqn()).isEqualTo(ReceivingDocument.class.getName());
                assertThat(row.fieldName()).isEqualTo("number");
            });
        assertThat(summary.diagnostics())
            .filteredOn(row -> row.code().equals(MetadataDiagnosticCodes.REDUNDANT_TYPE))
            .singleElement()
            .satisfies(row -> assertThat(row.key()).isEqualTo(FacetKey.of(
                FacetKind.FIELD_STRUCTURE, ReceivingDocument.class, "date")));

        // Строковая диагностика видна на карточке root ровно один раз, но адрес сохраняет тип строки.
        assertThat(summary.diagnostics())
            .filteredOn(row -> row.entityFqn().equals(ReceivingDocumentItem.class.getName()))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.fieldName()).isEqualTo("nomenclature");
                assertThat(row.key()).isEqualTo(FacetKey.of(FacetKind.LOOKUP_TARGET,
                    ReceivingDocumentItem.class, "nomenclature"));
                assertThat(row.source()).isEqualTo("@Lookup / тип ссылки");
            });

        // Будущий/неизвестный код сохраняет entity и field, но не получает выдуманный FacetKey.
        assertThat(summary.diagnostics())
            .filteredOn(row -> row.code().equals("FUTURE_DIAGNOSTIC_CODE"))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.entityFqn()).isEqualTo(ReceivingDocument.class.getName());
                assertThat(row.fieldName()).isEqualTo("futureField");
                assertThat(row.key()).isNull();
                assertThat(row.value().value()).isEqualTo("новая диагностика без известной грани");
            });

        assertThat(withDiagnostics.unassignedDiagnostics())
            .singleElement()
            .satisfies(row -> {
                assertThat(row.entityFqn()).isEqualTo("external.module.HiddenEntity");
                assertThat(row.fieldName()).isEqualTo("field");
                assertThat(row.key()).isNull();
            });

        // В этом snapshot все записи адресуются карточке ReceivingDocument или общему списку.
        assertThat(summary.diagnostics().size() + withDiagnostics.unassignedDiagnostics().size())
            .isEqualTo(snapshot.size());
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

    // ---------------------------------------------------------------- lifecycle

    @Test
    void lifecycleHandlerRowCarriesRegistrationAndOnlyTheOverriddenHookIsExplicit() {
        EntitySummary summary = summarizerWithLifecycle(new NomenclatureLifecycle(
            mock(ObjectProvider.class))).summarize(Nomenclature.class);

        List<EntitySummary.LifecycleRow> rows = summary.lifecycle();
        assertThat(rows).hasSize(7);

        EntitySummary.LifecycleRow handlerRow = rows.get(0);
        assertThat(handlerRow.key().kind()).isEqualTo(FacetKind.ENTITY_LIFECYCLE_HANDLER);
        assertThat(handlerRow.key().fieldName()).isNull();
        assertThat(handlerRow.hook()).isEmpty();
        assertThat(handlerRow.declared()).isTrue();
        assertThat(handlerRow.value().origin()).isEqualTo(FactOrigin.REGISTRATION);
        assertThat(handlerRow.value().symbol()).isEqualTo(NomenclatureLifecycle.class.getName());
        assertThat(handlerRow.note()).isNotBlank();

        assertThat(rows.subList(1, rows.size()))
            .extracting(EntitySummary.LifecycleRow::hook)
            .containsExactly("beforeSave", "beforeUpdate", "beforeAggregateSave",
                "beforeDelete", "onSave", "afterCommit");
        assertThat(rows.subList(1, rows.size())).allSatisfy(row -> {
            assertThat(row.key().kind()).isEqualTo(FacetKind.ENTITY_LIFECYCLE_HOOK);
            assertThat(row.key().entityClass()).isEqualTo(Nomenclature.class);
            assertThat(row.key().fieldName()).isEqualTo(row.hook());
            assertThat(row.key().variant()).isNull();
        });

        // Переопределён реально один хук — остальные остались default-методами контракта.
        assertThat(rows.subList(1, rows.size()))
            .filteredOn(EntitySummary.LifecycleRow::declared)
            .extracting(EntitySummary.LifecycleRow::hook)
            .containsExactly("beforeAggregateSave");
        assertThat(rows).filteredOn(row -> !row.declared())
            .extracting(EntitySummary.LifecycleRow::hook)
            .containsExactly("beforeSave", "beforeUpdate", "beforeDelete", "onSave", "afterCommit");

        assertThat(rows).filteredOn(row -> "beforeAggregateSave".equals(row.hook()))
            .singleElement().satisfies(row -> {
                assertThat(row.value().origin()).isEqualTo(FactOrigin.EXPLICIT);
                assertThat(row.value().symbol())
                    .isEqualTo(NomenclatureLifecycle.class.getName() + "#beforeAggregateSave");
            });
        assertThat(rows).filteredOn(row -> "afterCommit".equals(row.hook()))
            .singleElement().satisfies(row -> {
                assertThat(row.value().origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
                assertThat(row.value().symbol())
                    .isEqualTo(EntityLifecycle.class.getName() + "#afterCommit");
            });

        // Оговорка о делегатах стоит один раз — в строке handler'а, а не в шести строках хуков.
        assertThat(rows).filteredOn(row -> !row.note().isBlank()).hasSize(1);
    }

    @Test
    void missingHandlerIsNotReadAsHandlerWithoutRules() {
        EntitySummary summary = assembler.summarize(Nomenclature.class);
        EntitySummary withIdleHandler = summarizerWithLifecycle(new IdleLifecycle())
            .summarize(Nomenclature.class);

        assertThat(summary.lifecycle()).singleElement().satisfies(row -> {
            assertThat(row.key().kind()).isEqualTo(FacetKind.ENTITY_LIFECYCLE_HANDLER);
            assertThat(row.value().value()).isEqualTo("handler в реестре не зарегистрирован");
            assertThat(row.value().origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
            assertThat(row.value().symbol()).isEmpty();
            assertThat(row.note()).isNotBlank();
        });

        // Handler без единого переопределения — другой факт: он есть, и все шесть хуков default.
        List<EntitySummary.LifecycleRow> idleRows = withIdleHandler.lifecycle();
        assertThat(idleRows).hasSize(7);
        assertThat(idleRows.subList(1, idleRows.size()))
            .filteredOn(EntitySummary.LifecycleRow::declared)
            .isEmpty();
        assertThat(idleRows).filteredOn(row -> !row.declared())
            .allSatisfy(row -> assertThat(row.value().origin())
                .isEqualTo(FactOrigin.PLATFORM_DEFAULT));
    }

    @Test
    void typeOutsideIdentityContractIsReportedAsNotApplicable() {
        EntitySummary summary = summarizerWithLifecycle(new NomenclatureLifecycle(
            mock(ObjectProvider.class))).summarize(NoIdentityFixture.class);

        assertThat(summary.lifecycle()).singleElement().satisfies(row -> {
            assertThat(row.key().kind()).isEqualTo(FacetKind.ENTITY_LIFECYCLE_HANDLER);
            assertThat(row.key().entityClass()).isEqualTo(NoIdentityFixture.class);
            assertThat(row.value().origin()).isEqualTo(FactOrigin.DERIVED);
            assertThat(row.value().value()).contains("не применим");
            assertThat(row.value().symbol()).isEqualTo(NoIdentityFixture.class.getName());
            assertThat(row.note()).isEmpty();
        });
    }

    @Test
    void lifecycleRowsStayOutOfTheDiagnosticsSnapshot() {
        EntitySummary plain = assembler.summarize(Nomenclature.class);
        EntitySummary withHandler = summarizerWithLifecycle(new NomenclatureLifecycle(
            mock(ObjectProvider.class))).summarize(Nomenclature.class);

        assertThat(withHandler.lifecycle()).hasSize(7);
        assertThat(withHandler.diagnostics()).hasSameSizeAs(plain.diagnostics());
        assertThat(withHandler.diagnostics()).isEmpty();
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
        return assembler(basePackage, metadataResolver, formRegistry, referenceIndex,
            numbering, subsystems, facetResolver, null);
    }

    private EntitySummaryAssembler assemblerWithDiagnostics(List<MetadataDiagnostic> diagnostics) {
        MetadataConsistencyStartupCheck startupCheck = mock(MetadataConsistencyStartupCheck.class);
        when(startupCheck.diagnostics()).thenReturn(List.copyOf(diagnostics));
        MetadataResolver metadataResolver = new MetadataResolver();
        ReferenceIndex references = new ReferenceIndex("org.ip");
        references.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry("org.ip");
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry("org.ip");
        subsystems.afterPropertiesSet();
        return assembler("org.ip", metadataResolver, new FormRegistry(), references,
            numbering, subsystems, FacetResolver.none(), startupCheck);
    }

    /** Сборщик с одним прикладным handler'ом: остальные части сводки те же. */
    private static EntitySummaryAssembler summarizerWithLifecycle(EntityLifecycle<?>... handlers) {
        MetadataResolver metadataResolver = new MetadataResolver();
        ReferenceIndex references = new ReferenceIndex("org.ip");
        references.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry("org.ip");
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry("org.ip");
        subsystems.afterPropertiesSet();
        return assembler("org.ip", metadataResolver, new FormRegistry(), references,
            numbering, subsystems, FacetResolver.none(), null,
            new EntityLifecycleRegistry(List.of(handlers)));
    }

    private static EntitySummaryAssembler assembler(
            String basePackage,
            MetadataResolver metadataResolver,
            FormRegistry formRegistry,
            ReferenceIndex referenceIndex,
            NumberingMetadataRegistry numbering,
            SubsystemRegistry subsystems,
            FacetResolver facetResolver,
            MetadataConsistencyStartupCheck startupCheck) {
        return assembler(basePackage, metadataResolver, formRegistry, referenceIndex,
            numbering, subsystems, facetResolver, startupCheck,
            new EntityLifecycleRegistry(List.of()));
    }

    private static EntitySummaryAssembler assembler(
            String basePackage,
            MetadataResolver metadataResolver,
            FormRegistry formRegistry,
            ReferenceIndex referenceIndex,
            NumberingMetadataRegistry numbering,
            SubsystemRegistry subsystems,
            FacetResolver facetResolver,
            MetadataConsistencyStartupCheck startupCheck,
            EntityLifecycleRegistry lifecycleRegistry) {
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
            lifecycleRegistry, sections, startupCheck);
    }

    // ---------------------------------------------------------------- снимок каталога (E3.2.2 §9.1)

    @Test
    void explorerSnapshotCarriesKindExposureSubsystemAndKeyFromTheirOwners() {
        SnapshotWiring wiring = snapshotWiring();

        ExplorerSnapshot snapshot = snapshotAssembler(wiring, FacetResolver.none(), emptyStartupCheck())
            .explorerSnapshot();

        ExplorerSnapshot.Entry catalog = snapshot.entryOf(Nomenclature.class).orElseThrow();
        assertThat(catalog.kind())
            .as("kind берётся у metadata resolver, а не выводится из имени или формы")
            .isEqualTo(wiring.metadata().resolve(Nomenclature.class).getEntityKind())
            .isEqualTo(EntityKind.CATALOG);
        assertThat(catalog.descriptor()).isNotNull();
        assertThat(catalog.descriptor().exposure())
            .as("экспозиция — решение descriptor catalog")
            .isEqualTo(wiring.descriptors().descriptorOf(Nomenclature.class).exposure())
            .isEqualTo(EntityExposure.STANDARD_ROOT);
        assertThat(catalog.subsystem()).isPresent();
        assertThat(catalog.subsystem().orElseThrow().id())
            .isEqualTo(org.ip.subsystem.Subsystems.Directories.class.getName());
        assertThat(catalog.subsystem().orElseThrow().label().value()).isNotBlank();
        assertThat(catalog.publishedKey())
            .as("опубликованный ключ — тот же, что выдаёт каталог маршрутов")
            .contains(wiring.routes().find(Nomenclature.class).orElseThrow().entityKey());
        assertThat(catalog.state()).isEqualTo(ExplorerSnapshot.EntryState.READY);
        assertThat(catalog.countsKnown()).isTrue();

        // Пилот документов: тип берётся у resolver'а, а не из имени класса. Сегодня
        // ReceivingDocument резолвится как PLAIN — приложение не объявляет DOCUMENT и не
        // наследует стандартный базовый класс; матрица §6 ждёт DOCUMENT, и это отдельное
        // решение о метаданных приложения, а не факт read-model (зафиксировано в прогрессе 9.1).
        ExplorerSnapshot.Entry document = snapshot.entryOf(ReceivingDocument.class).orElseThrow();
        assertThat(document.kind())
            .isEqualTo(wiring.metadata().resolve(ReceivingDocument.class).getEntityKind())
            .isNotEqualTo(EntityKind.AUTO);
        assertThat(document.publishedKey())
            .contains(wiring.routes().find(ReceivingDocument.class).orElseThrow().entityKey());
    }

    @Test
    void explorerSnapshotIndexesOwnedRowsUnderTheirConfirmedSections() {
        SnapshotWiring wiring = snapshotWiring();

        ExplorerSnapshot snapshot = snapshotAssembler(wiring, FacetResolver.none(), emptyStartupCheck())
            .explorerSnapshot();

        ExplorerSnapshot.Entry owned = snapshot.entryOf(NomAttributeValue.class).orElseThrow();
        assertThat(owned.descriptor().exposure()).isEqualTo(EntityExposure.OWNED_ROW);
        assertThat(owned.rootCandidate()).isFalse();

        assertThat(snapshot.ownersOf(NomAttributeValue.class)).singleElement().satisfies(section -> {
            assertThat(section.ownerType()).isEqualTo(Nomenclature.class);
            assertThat(section.sectionField()).isEqualTo("nomenclature");
            assertThat(section.rowClassFqn()).isEqualTo(NomAttributeValue.class.getName());
            assertThat(section.label().value()).isEqualTo("Атрибуты номенклатуры");
            assertThat(section.id()).isEqualTo(Nomenclature.class.getName()
                + "#nomenclature#" + NomAttributeValue.class.getName());
        });
        assertThat(snapshot.sectionsOf(Nomenclature.class))
            .extracting(ExplorerSnapshot.OwnedSection::rowClassFqn)
            .contains(NomAttributeValue.class.getName());
        assertThat(snapshot.roots())
            .as("owned-строка не становится самостоятельным root'ом дерева")
            .extracting(ExplorerSnapshot.Entry::type)
            .doesNotContain(NomAttributeValue.class);
    }

    @Test
    void explorerSnapshotSearchIndexMatchesTheCardForPilotTypes() {
        SnapshotWiring wiring = snapshotWiring();

        ExplorerSnapshot snapshot = snapshotAssembler(wiring, FacetResolver.none(), emptyStartupCheck())
            .explorerSnapshot();

        for (Class<?> type : List.of(Nomenclature.class, ReceivingDocument.class)) {
            EntitySummary card = snapshot.summaryOf(type).orElseThrow();
            for (EntitySummary.SectionRow row : card.tableSections()) {
                assertThat(snapshot.sectionsOf(type))
                    .as("у секции карточки есть тот же узел каталога: %s", row.rowClass())
                    .anyMatch(section -> section.rowSimpleName().equals(row.rowClass())
                        && section.label().value().equals(row.value().value()));
            }
            for (EntitySummary.FieldRow field : cardFields(card)) {
                assertThat(snapshot.searchTerms()).anySatisfy(term -> {
                    assertThat(term.rootType()).isEqualTo(type);
                    assertThat(term.kind()).isEqualTo(ExplorerSnapshot.SearchKind.FIELD);
                    assertThat(term.term()).isEqualTo(field.name().toLowerCase(Locale.ROOT));
                    assertThat(term.fieldName()).isEqualTo(field.name());
                    assertThat(term.sectionId()).isEmpty();
                });
            }
            Set<String> sectionIds = new LinkedHashSet<>();
            snapshot.sectionsOf(type).forEach(section -> sectionIds.add(section.id()));
            Set<String> fieldNames = new LinkedHashSet<>();
            cardFields(card).forEach(field -> fieldNames.add(field.name()));
            for (ExplorerSnapshot.SearchTerm term : snapshot.searchTerms()) {
                if (term.rootType() != type) {
                    continue;
                }
                if (term.kind() == ExplorerSnapshot.SearchKind.SECTION
                        || !term.sectionId().isEmpty()) {
                    assertThat(sectionIds)
                        .as("признак ведёт только к подтверждённой секции: %s", term.term())
                        .contains(term.sectionId());
                } else if (term.kind() == ExplorerSnapshot.SearchKind.FIELD) {
                    assertThat(fieldNames)
                        .as("признак ведёт только к полю самой карточки: %s", term.term())
                        .contains(term.fieldName());
                }
            }
        }

        // Owned-поля индексируются у секции владельца: своей карточки-корня у NomAttributeValue нет.
        List<ExplorerSnapshot.OwnedSection> owners = snapshot.ownersOf(NomAttributeValue.class);
        EntitySummary ownedCard = snapshot.summaryOf(NomAttributeValue.class).orElseThrow();
        String sectionId = owners.get(0).id();
        assertThat(snapshot.searchTerms())
            .filteredOn(term -> term.kind() == ExplorerSnapshot.SearchKind.FIELD
                && sectionId.equals(term.sectionId()))
            .extracting(ExplorerSnapshot.SearchTerm::fieldName)
            .containsAll(ownedCard.fieldsForm().stream().map(EntitySummary.FieldRow::name).toList());
    }

    @Test
    void explorerSnapshotMakesOneSummarizeAttemptPerInventoryTypeAndMeasuresCost() {
        SnapshotWiring wiring = snapshotWiring();
        List<FacetKey> resolutions = new ArrayList<>();
        CountingAssembler counting = countingSnapshotAssembler(wiring, key -> {
            resolutions.add(key);
            return Optional.empty();
        }, emptyStartupCheck());
        List<Class<?>> inventory = counting.entities().stream()
            .map(EntitySummaryAssembler.EntityRef::entityClass).toList();

        resolutions.clear();
        ExplorerSnapshot snapshot = counting.explorerSnapshot();

        assertThat(counting.attempts)
            .as("по одной попытке на каждый уникальный тип инвентаря")
            .containsExactlyInAnyOrderElementsOf(inventory)
            .doesNotHaveDuplicates();
        assertThat(snapshot.stats().typeCount()).isEqualTo(inventory.size());
        assertThat(snapshot.stats().summarizeAttempts()).isEqualTo(inventory.size());
        assertThat(snapshot.stats().summarizeFailures()).isZero();
        assertThat(snapshot.stats().buildNanos()).isPositive();

        System.out.println("[E3.2.2-9.1] types=" + snapshot.stats().typeCount()
            + " attempts=" + snapshot.stats().summarizeAttempts()
            + " failures=" + snapshot.stats().summarizeFailures()
            + " buildMs=" + snapshot.stats().buildNanos() / 1_000_000
            + " facetResolutions=" + resolutions.size()
            + " panels=0");
    }

    @Test
    void explorerSnapshotWithoutStartupCheckKeepsCountsUnknownInsteadOfZero() {
        SnapshotWiring wiring = snapshotWiring();

        ExplorerSnapshot snapshot = snapshotAssembler(wiring, FacetResolver.none(), null)
            .explorerSnapshot();

        assertThat(snapshot.diagnosticsAvailable()).isFalse();
        assertThat(snapshot.entries())
            .as("недоступная проверка не выдаётся за ноль ошибок")
            .allSatisfy(entry -> assertThat(entry.countsKnown()).isFalse());
        assertThat(snapshot.unassignedDiagnostics())
            .extracting(EntitySummary.DiagnosticRow::code)
            .containsExactly("DIAGNOSTICS_UNAVAILABLE");
    }

    @Test
    void uiSearchFiltersGroupingAndMenuReuseTheSnapshotWithoutResolvingFactsAgain() {
        SnapshotWiring wiring = snapshotWiring();
        MetadataResolver observed = org.mockito.Mockito.spy(wiring.metadata());
        wiring = new SnapshotWiring(observed, wiring.references(), wiring.numbering(), wiring.subsystems(),
            wiring.sections(), wiring.descriptors(), wiring.routes());
        List<FacetKey> resolutions = new ArrayList<>();
        CountingAssembler counting = countingSnapshotAssembler(wiring, key -> {
            resolutions.add(key);
            return Optional.empty();
        }, emptyStartupCheck());
        org.ip.views.admin.EntityExplorerAccess access = mock(org.ip.views.admin.EntityExplorerAccess.class);
        when(access.allows()).thenReturn(true);
        org.ipro.form.coordinator.FormNavigator navigator = mock(org.ipro.form.coordinator.FormNavigator.class);
        org.ip.views.admin.EntityExplorerView view = new org.ip.views.admin.EntityExplorerView(counting, navigator, access);
        when(access.allows()).thenReturn(false);
        view.init();
        assertThat(counting.attempts).isEmpty();
        when(access.allows()).thenReturn(true);
        long started = System.nanoTime();
        view.init();
        long firstUiNanos = System.nanoTime() - started;
        int attempts = counting.attempts.size();
        int facets = resolutions.size();
        int metadataCalls = org.mockito.Mockito.mockingDetails(observed).getInvocations().size();
        assertThat(((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(view, "panels")))
            .isEmpty();
        com.vaadin.flow.component.textfield.TextField search = uiComponents(view)
            .filter(com.vaadin.flow.component.textfield.TextField.class::isInstance)
            .map(com.vaadin.flow.component.textfield.TextField.class::cast).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        com.vaadin.flow.component.select.Select<EntityKind> filter =
            (com.vaadin.flow.component.select.Select<EntityKind>) uiComponents(view)
                .filter(component -> component instanceof com.vaadin.flow.component.select.Select<?> select
                    && "Вид".equals(select.getLabel())).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        com.vaadin.flow.component.select.Select<Object> grouping =
            (com.vaadin.flow.component.select.Select<Object>) uiComponents(view)
                .filter(component -> component instanceof com.vaadin.flow.component.select.Select<?> select
                    && "Группировка".equals(select.getLabel())).findFirst().orElseThrow();
        long repeatStarted = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            search.setValue(i % 2 == 0 ? "номенклатура" : "code");
            filter.setValue(EntityKind.CATALOG);
            filter.clear();
        }
        search.clear();
        Object subsystem = grouping.getListDataView().getItems()
            .filter(value -> "SUBSYSTEM".equals(value.toString())).findFirst().orElseThrow();
        grouping.setValue(subsystem);
        long repeatNanos = System.nanoTime() - repeatStarted;
        view.init(Nomenclature.class, "fields/table-sections");
        view.init(ReceivingDocument.class);
        view.applyMenuEntry();
        assertThat(counting.attempts).hasSize(attempts).doesNotHaveDuplicates();
        assertThat(resolutions).hasSize(facets);
        assertThat(org.mockito.Mockito.mockingDetails(observed).getInvocations()).hasSize(metadataCalls);
        assertThat(((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(view, "panels")))
            .hasSize(2);
        org.mockito.Mockito.verifyNoInteractions(navigator);
        System.out.println("[E3.2.2-9.3] types=" + attempts + " firstUiMs=" + firstUiNanos / 1_000_000
            + " repeatedOperations=62 repeatedMs=" + repeatNanos / 1_000_000
            + " extraSummaries=0 extraFacets=0 extraMetadata=0 panels=2");
    }

    private static Stream<com.vaadin.flow.component.Component> uiComponents(com.vaadin.flow.component.Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(EntitySummaryAssemblerTest::uiComponents));
    }

    private static MetadataConsistencyStartupCheck emptyStartupCheck() {
        MetadataConsistencyStartupCheck check = mock(MetadataConsistencyStartupCheck.class);
        when(check.diagnostics()).thenReturn(List.of());
        return check;
    }

    private static List<EntitySummary.FieldRow> cardFields(EntitySummary card) {
        return Stream.concat(card.fieldsForm().stream(), card.fieldsGrid().stream()).toList();
    }

    /** Коллабораторы снимка, собранные теми же владельцами, что и у сборщика сводки. */
    private record SnapshotWiring(MetadataResolver metadata, ReferenceIndex references,
                                  NumberingMetadataRegistry numbering, SubsystemRegistry subsystems,
                                  SectionMetadataRegistry sections, EntityDescriptorCatalog descriptors,
                                  FormRouteCatalog routes) {
    }

    private SnapshotWiring snapshotWiring() {
        MetadataResolver metadata = new MetadataResolver();
        ReferenceIndex references = new ReferenceIndex("org.ip");
        references.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry("org.ip");
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry("org.ip");
        subsystems.afterPropertiesSet();
        SectionMetadataRegistry sections = new SectionMetadataRegistry("org.ip", metadata);
        sections.afterPropertiesSet();
        Set<Class<?>> managedTypes = new LinkedHashSet<>(
            AnnotationClassScanner.scanAnnotated("org.ip", EntityMetadata.class));
        sections.all().forEach(section -> managedTypes.add(section.getRowClass()));
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(managedTypes);
        EntityDescriptorCatalog descriptors = new EntityDescriptorCatalog(managed, sections,
            metadata, List.of(), List.of());
        FormRouteCatalog routes = FormRouteCatalog.build(descriptors, formRegistry, List.of());
        return new SnapshotWiring(metadata, references, numbering, subsystems, sections,
            descriptors, routes);
    }

    private EntitySummaryAssembler snapshotAssembler(SnapshotWiring wiring, FacetResolver resolver,
                                                     MetadataConsistencyStartupCheck check) {
        return new EntitySummaryAssembler("org.ip", wiring.metadata(), formRegistry,
            wiring.references(), wiring.numbering(), wiring.subsystems(), resolver,
            wiring.descriptors(), wiring.routes(), new EntityLifecycleRegistry(List.of()),
            wiring.sections(), check);
    }

    private CountingAssembler countingSnapshotAssembler(SnapshotWiring wiring,
                                                        FacetResolver resolver,
                                                        MetadataConsistencyStartupCheck check) {
        return new CountingAssembler("org.ip", wiring.metadata(), formRegistry, wiring.references(),
            wiring.numbering(), wiring.subsystems(), resolver, wiring.descriptors(),
            wiring.routes(), new EntityLifecycleRegistry(List.of()), wiring.sections(), check);
    }

    /** Сборщик, считающий попытки сборки сводки: «одна попытка на тип» — проверяемый факт. */
    static final class CountingAssembler extends EntitySummaryAssembler {

        final List<Class<?>> attempts = new ArrayList<>();

        CountingAssembler(String basePackage, MetadataResolver metadataResolver,
                          FormRegistry formRegistry, ReferenceIndex referenceIndex,
                          NumberingMetadataRegistry numbering, SubsystemRegistry subsystems,
                          FacetResolver facetResolver, EntityDescriptorCatalog descriptors,
                          FormRouteCatalog routes, EntityLifecycleRegistry lifecycleRegistry,
                          SectionMetadataRegistry sections,
                          MetadataConsistencyStartupCheck startupCheck) {
            super(basePackage, metadataResolver, formRegistry, referenceIndex, numbering,
                subsystems, facetResolver, descriptors, routes, lifecycleRegistry, sections,
                startupCheck);
        }

        @Override
        public EntitySummary summarize(Class<?> entityClass) {
            attempts.add(entityClass);
            return super.summarize(entityClass);
        }
    }

    /** Handler без переопределённых хуков: «handler есть, правил нет» — отдельный факт. */
    static class IdleLifecycle implements EntityLifecycle<Nomenclature> {
        @Override
        public Class<Nomenclature> entityType() {
            return Nomenclature.class;
        }
    }

    @EntityMetadata(listFormTitle = "Унаследованный справочник")
    static class InheritedCatalogFixture extends org.ipro.crud.StandardCatalogEntity {
    }

    /** Синтетический тип вне identity-контракта: ветка «lifecycle не применим». */
    @EntityMetadata(listFormTitle = "Тип без identity")
    static class NoIdentityFixture {
    }
}

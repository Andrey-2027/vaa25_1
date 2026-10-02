package org.ipro.vaadin.explorer;

import org.ipro.data.EntityCapabilityOverride;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.data.EntityExposureOverride;
import org.ipro.data.FetchPlanInspection;
import org.ipro.fetch.ManagedEntityTypes;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.fetch.plan.FetchPlanRegistry;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.registry.FormFactory;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.2.0 шаг 2.4: строки сценариев чтения в сводке — сценарий, признак допуска, счётчик путей,
 * пути с причинами и происхождение набора.
 *
 * <p><b>Почему на платформенных носителях, а не на типах приложения.</b> Здесь проверяются ветви
 * таблицы §2.1 плана шага: набор от правила экспозиции, набор, объявленный приложением (в том
 * числе совпавший с правилом), отказ canonical path при существующем плане, сценарий без путей.
 * Прикладные пилоты ({@code AttributeValue}, {@code SklNomOpa}) — предмет шага 2.5: они проверяют
 * факт на реальном типе, а не ветви модели.</p>
 *
 * <p><b>Почему носители — пробы.</b> Платформенный модуль не знает приложения: тест, знающий
 * {@code org.ip}, был бы проверкой приложения внутри платформы.</p>
 */
class EntitySummaryReadPlanRowsTest {

    /** Пакет, в котором лежат пробы: тот же, что сканирует сборщик. */
    private static final String BASE_PACKAGE = "org.ipro.vaadin.explorer";

    private final MetadataResolver metadataResolver = new MetadataResolver();

    // === Ветви §2.1 плана шага ===

    /** Набор от правила экспозиции: «Источник» — платформенный, примечание называет правило. */
    @Test
    void ruleDerivedSetIsShownAsTheExposureRule() {
        EntitySummary summary = assembler(List.of(), List.of()).summarize(ReadPlanProbe.class);

        assertThat(summary.readPlanInspectionAvailable()).isTrue();
        assertThat(summary.readPlans()).isNotEmpty();
        assertThat(summary.readPlans()).allSatisfy(row ->
            assertThat(row.value().origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT));
        assertThat(summary.readPlans()).filteredOn(EntitySummary.ReadPlanRow::allowed)
            .allSatisfy(row -> assertThat(row.note())
                .isEqualTo("набор сценариев выведен из экспозиции типа (STANDARD_ROOT)"));
        assertThat(row(summary, "LIST").value().value()).isEqualTo("допущен");
        assertThat(row(summary, "LIST").allowed()).isTrue();
        assertThat(row(summary, "ROW").note())
            .as("ROW не допущен, но план есть — оговорка об отказе пути, а не скрытая строка")
            .isEqualTo("набор сценариев выведен из экспозиции типа (STANDARD_ROOT)"
                + "; canonical path сценарий не допускает");
    }

    /** Набор, объявленный приложением, читается объявлением — даже когда совпал с правилом. */
    @Test
    void declaredSetNamesTheApplicationReasonEvenWhenItMatchesTheRule() {
        EntitySummary summary = assembler(List.of(), List.of(new EntityCapabilityOverride(
            ReadPlanProbe.class, Set.of(FetchScenario.LIST, FetchScenario.DETAIL,
                FetchScenario.LOOKUP), Set.of(), "набор ведёт typed use case агрегата")))
            .summarize(ReadPlanProbe.class);

        assertThat(summary.readPlans()).allSatisfy(row -> {
            assertThat(row.value().origin())
                .as("набор, равный правилу, остаётся объявлением: override заменяет выведенное")
                .isEqualTo(FactOrigin.REGISTRATION);
            assertThat(row.value().symbol())
                .as("место объявления в ядре не разрешается — названная граница шага 2")
                .isEmpty();
        });
        assertThat(summary.readPlans()).filteredOn(EntitySummary.ReadPlanRow::allowed)
            .allSatisfy(row -> assertThat(row.note())
                .isEqualTo("набор сценариев объявлен приложением:"
                    + " набор ведёт typed use case агрегата"));
    }

    /** Сценарий не допущен, но план непуст: строка есть, и примечание говорит об отказе пути. */
    @Test
    void refusedScenarioWithPlanIsShownAndSaysThePathRefusesIt() {
        EntitySummary summary = assembler(List.of(), List.of(new EntityCapabilityOverride(
            ReadPlanProbe.class, Set.of(), Set.of(), "read закрыт прикладной policy")))
            .summarize(ReadPlanProbe.class);

        assertThat(summary.readPlans())
            .as("допущенных сценариев нет, но планы существуют — строки обязаны быть")
            .isNotEmpty();
        assertThat(summary.readPlans()).allSatisfy(row -> {
            assertThat(row.allowed()).isFalse();
            assertThat(row.value().value()).isEqualTo("не допущен");
            assertThat(row.note())
                .startsWith("набор сценариев объявлен приложением: read закрыт прикладной policy")
                .endsWith("; canonical path сценарий не допускает");
        });
    }

    /** Сценарий допущен, а путей нет: строка есть, счётчик нулевой — это факт, а не пустая ячейка. */
    @Test
    void allowedScenarioWithoutPathsKeepsItsRow() {
        EntitySummary summary = assembler(List.of(), List.of()).summarize(ReadPlanFlat.class);

        assertThat(summary.readPlans()).extracting(EntitySummary.ReadPlanRow::scenario)
            .as("ROW в набор правила не входит и плана не имеет — строки нет")
            .containsExactly("LIST", "DETAIL", "LOOKUP");
        assertThat(summary.readPlans()).allSatisfy(row -> {
            assertThat(row.allowed()).isTrue();
            assertThat(row.pathCount()).isZero();
            assertThat(row.paths()).isEmpty();
        });
    }

    /** Пути — свои строки-факты: адрес «сценарий/путь», причина из плана, происхождение DERIVED. */
    @Test
    void pathRowsCarryTheirOwnKeyAndThePlanReason() {
        EntitySummary summary = assembler(List.of(), List.of()).summarize(ReadPlanProbe.class);

        EntitySummary.ReadPlanRow list = row(summary, "LIST");
        assertThat(list.paths()).isNotEmpty();
        assertThat(list.pathCount()).isEqualTo(list.paths().size());

        EntitySummary.PathRow path = list.paths().get(0);
        assertThat(path.key().kind()).isEqualTo(FacetKind.FETCH_PLAN_PATH);
        assertThat(path.key().entityClass()).isEqualTo(ReadPlanProbe.class);
        assertThat(path.key().fieldName()).isEqualTo("LIST/" + path.attributePath());
        assertThat(path.key().variant()).isNull();
        assertThat(path.scenario()).isEqualTo("LIST");
        assertThat(path.reason()).isEqualTo("metadata:LIST");
        assertThat(path.value().origin()).isEqualTo(FactOrigin.DERIVED);
        assertThat(path.value().symbol()).isEmpty();
    }

    /** Ключ строки сценария — без варианта формы: пара ключа плана — «класс + сценарий». */
    @Test
    void scenarioKeysCarryNoFormVariant() {
        EntitySummary summary = assembler(List.of(), List.of()).summarize(ReadPlanProbe.class);

        for (EntitySummary.ReadPlanRow plan : summary.readPlans()) {
            assertThat(plan.key().kind()).isEqualTo(FacetKind.FETCH_PLAN);
            assertThat(plan.key().entityClass()).isEqualTo(ReadPlanProbe.class);
            assertThat(plan.key().fieldName()).isEqualTo(plan.scenario());
            assertThat(plan.key().variant())
                .as("вариант формы в ключ плана не входит: план не зависит от варианта")
                .isNull();
            assertThat(plan.kind()).isEqualTo(FacetKind.FETCH_PLAN);
        }
    }

    /** Owned-строка: набор берётся от правила экспозиции, прикладного объявления на ней нет. */
    @Test
    void ownedRowTakesItsScenarioFromTheExposureRule() {
        EntitySummary summary = assembler(List.of(new EntityExposureOverride(ReadPlanProbe.class,
            EntityExposure.OWNED_ROW, void.class, "структурная строка агрегата")), List.of())
            .summarize(ReadPlanProbe.class);

        assertThat(summary.readPlans()).allSatisfy(row ->
            assertThat(row.value().origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT));
        assertThat(summary.readPlans()).filteredOn(EntitySummary.ReadPlanRow::allowed)
            .singleElement()
            .as("допущен ровно один сценарий — ROW из правила OWNED_ROW")
            .satisfies(row -> {
                assertThat(row.scenario()).isEqualTo("ROW");
                assertThat(row.note())
                    .isEqualTo("набор сценариев выведен из экспозиции типа (OWNED_ROW)");
            });
    }

    /** Без инспекции строк нет, а её недоступность опубликована отдельно от нулевого плана. */
    @Test
    void withoutInspectionThereAreNoRows() {
        EntitySummary summary = withoutInspection().summarize(ReadPlanProbe.class);

        assertThat(summary.readPlanInspectionAvailable()).isFalse();
        assertThat(summary.readPlans()).isEmpty();
    }

    // === Вспомогательное ===

    private static EntitySummary.ReadPlanRow row(EntitySummary summary, String scenario) {
        return summary.readPlans().stream()
            .filter(candidate -> scenario.equals(candidate.scenario()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("нет строки сценария " + scenario
                + ", строки: " + summary.readPlans().stream()
                    .map(EntitySummary.ReadPlanRow::scenario).toList()));
    }

    /** Сборщик с настоящей инспекцией: реестр плана, дескрипторы типа и происхождение набора. */
    private EntitySummaryAssembler assembler(List<EntityExposureOverride> exposures,
                                              List<EntityCapabilityOverride> capabilities) {
        Set<Class<?>> types = Set.of(ReadPlanProbe.class, ReadPlanFlat.class, ReadPlanReference.class);
        MetadataResolver resolver = new MetadataResolver();
        SectionMetadataRegistry sections = new SectionMetadataRegistry(BASE_PACKAGE, resolver);
        sections.afterPropertiesSet();

        ManagedEntityCatalog managed = Mockito.mock(ManagedEntityCatalog.class);
        Mockito.when(managed.managedEntityClasses()).thenReturn(types);
        EntityDescriptorCatalog descriptors = new EntityDescriptorCatalog(managed, sections, resolver,
            exposures, capabilities);
        FetchPlanInspection inspection = new FetchPlanInspection(registryOver(types, resolver), descriptors);

        return assemblerWith(sections, descriptors, inspection);
    }

    private EntitySummaryAssembler withoutInspection() {
        MetadataResolver resolver = new MetadataResolver();
        SectionMetadataRegistry sections = new SectionMetadataRegistry(BASE_PACKAGE, resolver);
        sections.afterPropertiesSet();
        return assemblerWith(sections, null, null);
    }

    private EntitySummaryAssembler assemblerWith(SectionMetadataRegistry sections,
                                                  EntityDescriptorCatalog descriptors,
                                                  FetchPlanInspection inspection) {
        MetadataResolver resolver = new MetadataResolver();
        ReferenceIndex referenceIndex = new ReferenceIndex(BASE_PACKAGE);
        referenceIndex.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry(BASE_PACKAGE);
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry(BASE_PACKAGE);
        subsystems.afterPropertiesSet();
        FormRegistry forms = new FormRegistry();
        forms.registerListForm(ReadPlanProbe.class, "probe", Mockito.mock(FormFactory.class));

        return new EntitySummaryAssembler(BASE_PACKAGE, resolver, forms, referenceIndex, numbering,
            subsystems, FacetResolver.none(), descriptors, null, null, sections, null,
            null, null, null, inspection);
    }

    private static FetchPlanRegistry registryOver(Set<Class<?>> types, MetadataResolver resolver) {
        ManagedEntityCatalog managed = Mockito.mock(ManagedEntityCatalog.class);
        Mockito.when(managed.managedEntityClasses()).thenReturn(types);
        ManagedEntityTypes managedTypes = new ManagedEntityTypes(managed);
        return new FetchPlanRegistry(managedTypes, resolver,
            new InstanceNameResolver(managedTypes.all(), resolver));
    }

    // === Носители ===

    /** JPA-тип: policy описывает persistence type, поэтому проба — настоящая сущность. */
    @jakarta.persistence.Entity
    @EntityMetadata(listFormTitle = "Пробная сущность")
    static class ReadPlanProbe {

        @jakarta.persistence.Id
        private Long id;

        @FieldMetadata(label = "Ссылка", type = FieldType.ENTITY_REFERENCE)
        @GridColumn(order = 1)
        ReadPlanReference reference;
    }

    /** Тип без ссылок: сценарии допущены, а путей у их планов нет. */
    @jakarta.persistence.Entity
    @EntityMetadata(listFormTitle = "Плоская сущность")
    static class ReadPlanFlat {

        @jakarta.persistence.Id
        private Long id;

        @FieldMetadata(label = "Код")
        @GridColumn(order = 1)
        String code;
    }

    @jakarta.persistence.Entity
    @EntityMetadata(listFormTitle = "Ссылочная цель")
    static class ReadPlanReference {

        @jakarta.persistence.Id
        private Long id;

        @FieldMetadata(label = "Код")
        String code;
    }
}

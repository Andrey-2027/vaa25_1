package org.ipro.fetch.plan;

import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;
import org.ipro.data.EntityCapabilityOverride;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.data.EntityExposureOverride;
import org.ipro.data.FetchPlanInspection;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E3.2.0 шаг 2.3: инспекция чтения отдаёт факты владельца плана — сценарии типа и пути с
 * причинами — и не дорисовывает комбинаций, которых нет ни в наборе, ни в плане.
 *
 * <p>Тест живёт в пакете плана, потому что реестр над явным набором классов строится его
 * package-private конструктором (тот же приём, что у {@code FetchPlanRegistryTest}): проверяется
 * настоящий план из настоящей metadata, а не заглушка.</p>
 */
class FetchPlanInspectionTest {

    private final MetadataResolver metadataResolver = new MetadataResolver();

    /** Правило экспозиции: набор сценариев платформенный, план выводится из metadata. */
    @Test
    void pathsComeFromThePlanWithTheirReasons() {
        FetchPlanInspection inspection = inspectionOver(Set.of(MetadataRoot.class, Reference.class), List.of(), List.of());

        List<FetchPlanInspection.Scenario> rows = inspection.scenariosOf(MetadataRoot.class);

        assertThat(rows).extracting(FetchPlanInspection.Scenario::scenario)
            .as("порядок строк — порядок FetchScenario.values(), а не порядок вычисления")
            .containsExactly("LIST", "DETAIL", "LOOKUP", "ROW");
        assertThat(rows).filteredOn(FetchPlanInspection.Scenario::allowed)
            .extracting(FetchPlanInspection.Scenario::scenario)
            .as("допущены ровно три сценария правила STANDARD_ROOT")
            .containsExactly("LIST", "DETAIL", "LOOKUP");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
            assertThat(row.symbol()).isEmpty();
        });
        assertThat(rows.get(3).allowed())
            .as("ROW в набор правила не входит и появляется только своим планом")
            .isFalse();

        FetchPlanInspection.Scenario list = rows.get(0);
        assertThat(list.paths()).extracting(FetchPlanInspection.Path::attributePath)
            .containsExactly("reference");
        assertThat(list.paths().get(0).reason()).isEqualTo("metadata:LIST");
        assertThat(rows.get(1).paths().get(0).reason()).isEqualTo("metadata:DETAIL");
        assertThat(rows.get(3).paths().get(0).reason()).isEqualTo("metadata:ROW");
    }

    /** Число строк путей равно плану реестра — инвариант, который держит карточку. */
    @Test
    void pathCountEqualsTheRegistryPlan() {
        Set<Class<?>> types = Set.of(MetadataRoot.class, Reference.class);
        FetchPlanInspection inspection = inspectionOver(types, List.of(), List.of());
        FetchPlanRegistry registry = registryOver(types);

        for (FetchScenario scenario : FetchScenario.values()) {
            int planned = registry.plan(MetadataRoot.class, scenario).paths().size();
            List<FetchPlanInspection.Path> shown = inspection.pathsOf(MetadataRoot.class, scenario);
            assertThat(shown.size())
                .as("сценарий %s: строки не выдуманы и не потеряны", scenario)
                .isEqualTo(planned);
        }
    }

    /** Сценарий допущен, но план пуст: строка есть — иначе карточка соврала бы об отсутствии. */
    @Test
    void allowedScenarioWithEmptyPlanStillGetsARow() {
        FetchPlanInspection inspection = inspectionOver(Set.of(OwnedRow.class), List.of(
            new EntityExposureOverride(OwnedRow.class, EntityExposure.OWNED_ROW, MetadataRoot.class,
                "структурная строка агрегата")), List.of());

        List<FetchPlanInspection.Scenario> rows = inspection.scenariosOf(OwnedRow.class);

        assertThat(rows).extracting(FetchPlanInspection.Scenario::scenario).containsExactly("ROW");
        assertThat(rows.get(0).allowed()).isTrue();
        assertThat(rows.get(0).origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        assertThat(rows.get(0).pathCount()).isZero();
    }

    /** Сценарий не допущен, но план непуст: строка есть с признаком «не допущен». */
    @Test
    void scenarioRefusedByPolicyButWithPlanStillGetsARow() {
        FetchPlanInspection inspection = inspectionOver(Set.of(MetadataRoot.class, Reference.class),
            List.of(), List.of(new EntityCapabilityOverride(MetadataRoot.class, Set.of(), Set.of(),
                "read закрыт прикладной policy")));

        List<FetchPlanInspection.Scenario> rows = inspection.scenariosOf(MetadataRoot.class);

        assertThat(rows).extracting(FetchPlanInspection.Scenario::scenario)
            .as("допущенных сценариев нет, но планы есть — строки обязаны быть")
            .containsExactly("LIST", "DETAIL", "LOOKUP", "ROW");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.allowed()).isFalse();
            assertThat(row.origin()).as("набор объявлен приложением, даже когда он пуст")
                .isEqualTo(FactOrigin.REGISTRATION);
            assertThat(row.symbol()).isEmpty();
        });
        assertThat(rows.get(0).pathCount()).isEqualTo(1);
    }

    /** Тип без сценариев и без плана: строк нет — это факт, а не отсутствие данных. */
    @Test
    void typeWithoutScenariosHasNoRows() {
        FetchPlanInspection inspection = inspectionOver(Set.of(InternalStore.class), List.of(), List.of());

        assertThat(inspection.scenariosOf(InternalStore.class)).isEmpty();
    }

    /**
     * Тип вне каталога: набор пуст (платформенный отказ), но планы у сценариев есть — строки
     * появляются планом и несут «не допущен». Замер, а не замысел: план выводится из metadata и
     * управляемого состава не требует.
     */
    @Test
    void unclassifiedTypeShowsPlansWithoutAllowedScenario() {
        FetchPlanInspection inspection = inspectionOver(Set.of(), List.of(), List.of());

        List<FetchPlanInspection.Scenario> rows = inspection.scenariosOf(MetadataRoot.class);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.allowed()).isFalse();
            assertThat(row.origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        });
    }

    /** Зависимости обязательны: без реестра «плана нет» не отличить от «план не спросили». */
    @Test
    void inspectionRequiresBothCollaborators() {
        EntityDescriptorCatalog catalog = catalogOver(Set.of(MetadataRoot.class), List.of(), List.of());

        assertThatThrownBy(() -> new FetchPlanInspection(null, catalog))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FetchPlanInspection(registryOver(Set.of()), null))
            .isInstanceOf(NullPointerException.class);
    }

    // === Сборка ===

    private FetchPlanInspection inspectionOver(Set<Class<?>> types,
                                               List<EntityExposureOverride> exposures,
                                               List<EntityCapabilityOverride> capabilities) {
        return new FetchPlanInspection(registryOver(types), catalogOver(types, exposures, capabilities));
    }

    private FetchPlanRegistry registryOver(Set<Class<?>> types) {
        return new FetchPlanRegistry(types, metadataResolver, new InstanceNameResolver(types, metadataResolver));
    }

    private EntityDescriptorCatalog catalogOver(Set<Class<?>> types,
                                                List<EntityExposureOverride> exposures,
                                                List<EntityCapabilityOverride> capabilities) {
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(types);
        SectionMetadataRegistry sections = mock(SectionMetadataRegistry.class);
        for (Class<?> type : types) {
            when(sections.findByRow(type)).thenReturn(Optional.empty());
        }
        return new EntityDescriptorCatalog(managed, sections, metadataResolver, exposures, capabilities);
    }

    // === Фикстуры ===

    @Entity
    @EntityMetadata(listFormTitle = "Корень", itemFormTitle = "Корень")
    static class MetadataRoot extends BaseEntity {

        @FieldMetadata(label = "Ссылка", type = FieldType.ENTITY_REFERENCE)
        private Reference reference;
    }

    @Entity
    @EntityMetadata(listFormTitle = "Цель", itemFormTitle = "Цель")
    static class Reference extends BaseEntity {

        @FieldMetadata(label = "Код")
        private String code;
    }

    /** Хранилище без metadata: ни сценариев, ни плана. */
    @Entity
    static class InternalStore extends BaseEntity {
    }

    /** Owned-строка агрегата: сценарий `ROW` от правила, плана у generic-пути нет. */
    @Entity
    static class OwnedRow extends BaseEntity {
    }
}

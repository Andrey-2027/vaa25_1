package org.ipro.fetch.plan;

import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.ReceivingDocument;
import org.ip.model.UnitOfMeasurement;
import org.ipro.fetch.instance.InstanceName;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.Lookup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Collection;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C3.3: план выводится из metadata и деклараций, сценарии не смешиваются, а ошибочная
 * декларация падает на старте, а не в рантайме.
 */
class FetchPlanRegistryTest {

    private final MetadataResolver metadataResolver = new MetadataResolver();

    // === Фикстуры: таргет ссылки + два владельца (корректная и ошибочная декларация) ===

    @EntityMetadata(listFormTitle = "Цель выбора", itemFormTitle = "Цель выбора")
    public static class LookupTarget {

        @FieldMetadata(label = "Код")
        private String code;

        @FieldMetadata(label = "Единица", type = FieldType.ENTITY_REFERENCE)
        private UnitOfMeasurement unitOfMeasurement;

        public String getCode() {
            return code;
        }

        public UnitOfMeasurement getUnitOfMeasurement() {
            return unitOfMeasurement;
        }
    }

    @EntityMetadata(listFormTitle = "Владелец", itemFormTitle = "Владелец")
    public static class DeclaringHolder {

        @FieldMetadata(label = "Цель", type = FieldType.ENTITY_REFERENCE,
            lookup = @Lookup(entity = LookupTarget.class, fetch = {"unitOfMeasurement.code"}))
        private LookupTarget target;
    }

    @EntityMetadata(listFormTitle = "Другой владелец", itemFormTitle = "Другой владелец")
    public static class OtherDeclaringHolder {

        @FieldMetadata(label = "Цель", type = FieldType.ENTITY_REFERENCE,
            lookup = @Lookup(entity = LookupTarget.class, fetch = {"unitOfMeasurement.code"}))
        private LookupTarget target;
    }

    @EntityMetadata(listFormTitle = "Владелец", itemFormTitle = "Владелец")
    public static class BrokenHolder {

        @FieldMetadata(label = "Цель", type = FieldType.ENTITY_REFERENCE,
            lookup = @Lookup(entity = LookupTarget.class, fetch = {"noSuchAttribute"}))
        private LookupTarget target;
    }

    /** Ссылка видна в гриде, но скрыта в форме: сценарии должны остаться независимыми. */
    @EntityMetadata(listFormTitle = "Скрытая ссылка", itemFormTitle = "Скрытая ссылка")
    public static class HiddenFormReference {

        @FieldMetadata(label = "Единица", type = FieldType.ENTITY_REFERENCE, hidden = true,
            grid = @org.ipro.metadata.annotation.GridColumn(order = 1))
        private UnitOfMeasurement unitOfMeasurement;
    }

    @EntityMetadata(listFormTitle = "Имя", itemFormTitle = "Имя")
    @InstanceName({"nomenclature.unitOfMeasurement.code"})
    public static class NamedHolder {

        @FieldMetadata(label = "Номенклатура", type = FieldType.ENTITY_REFERENCE)
        private Nomenclature nomenclature;
    }

    /**
     * Ссылка на именованную сущность без {@code @FieldMetadata}: её нет ни в гриде, ни в
     * форме, то есть ни в одном сценарии. Такой путь может прийти только дополнительно —
     * ровно тот случай, который прежний {@code LookupService.entityGraph} оставлял
     * неуглублённым.
     */
    @EntityMetadata(listFormTitle = "Ссылка вне сценариев", itemFormTitle = "Ссылка вне сценариев")
    public static class UnscenarioedReference {

        /** JPA-ассоциация без {@code @FieldMetadata}: тип пути распознаётся, но в грид/форму
         * поле не входит. */
        @jakarta.persistence.ManyToOne
        @SuppressWarnings("unused")
        private NamedHolder named;
    }

    private FetchPlanRegistry registryOver(Class<?>... managedTypes) {
        return registryOver(List.of(managedTypes));
    }

    private FetchPlanRegistry registryOver(Collection<Class<?>> managedTypes) {
        InstanceNameResolver resolver = new InstanceNameResolver(managedTypes, metadataResolver);
        return new FetchPlanRegistry(managedTypes, metadataResolver, resolver);
    }

    @Test
    void declaredLookupDependencyBecomesPartOfTheTargetLookupPlan() {
        FetchPlan plan = registryOver(LookupTarget.class, DeclaringHolder.class)
            .plan(LookupTarget.class, FetchScenario.LOOKUP);

        assertThat(plan.paths()).contains("unitOfMeasurement.code");
        assertThat(plan.reasonFor("unitOfMeasurement.code"))
            .isEqualTo("lookup:DeclaringHolder.target");
    }

    @Test
    void declaredLookupDependencyDoesNotLeakIntoOtherScenarios() {
        FetchPlanRegistry registry = registryOver(LookupTarget.class, DeclaringHolder.class);

        assertThat(registry.plan(LookupTarget.class, FetchScenario.LOOKUP).paths())
            .contains("unitOfMeasurement.code");
        assertThat(registry.plan(LookupTarget.class, FetchScenario.DETAIL).paths())
            .as("зависимость сценария выбора не должна грузиться формой элемента")
            .doesNotContain("unitOfMeasurement.code");
    }

    @Test
    void invalidDeclaredLookupPathFailsAtStartup() {
        assertThatThrownBy(() -> registryOver(LookupTarget.class, BrokenHolder.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("@Lookup(fetch)")
            .hasMessageContaining("noSuchAttribute");
    }

    @Test
    void listAndDetailPlansAreIndependent() {
        FetchPlanRegistry registry = registryOver(UnitOfMeasurement.class, HiddenFormReference.class);

        assertThat(registry.paths(HiddenFormReference.class, FetchScenario.LIST))
            .contains("unitOfMeasurement");
        assertThat(registry.paths(HiddenFormReference.class, FetchScenario.DETAIL))
            .doesNotContain("unitOfMeasurement");
    }

    @Test
    void instanceNameDependenciesAreAddedToLookupAndDetail() {
        FetchPlanRegistry registry = registryOver(NamedHolder.class, Nomenclature.class);

        assertThat(NamedHolder.class.getAnnotation(InstanceName.class)).isNotNull();
        assertThat(registry.plan(NamedHolder.class, FetchScenario.DETAIL)
                .reasonFor("nomenclature.unitOfMeasurement"))
            .isEqualTo("instance-name");
        assertThat(registry.plan(NamedHolder.class, FetchScenario.LOOKUP)
                .reasonFor("nomenclature.unitOfMeasurement"))
            .isEqualTo("instance-name");
        assertThat(registry.plan(NamedHolder.class, FetchScenario.LIST)
                .reasonFor("nomenclature.unitOfMeasurement"))
            .as("в плане списка имя сущности не рендерится")
            .isNull();
    }

    @Test
    void listPlanComesFromGridMetadataAndPilotLookupPlanFromDeclarations() {
        FetchPlanRegistry registry = registryOver(ReceivingDocument.class, Nomenclature.class,
            PrdSpec.class, PrdSpecMtr.class, UnitOfMeasurement.class);

        assertThat(registry.plan(ReceivingDocument.class, FetchScenario.LIST).reasonFor("receivingWorkshop"))
            .isEqualTo("metadata:LIST");

        // Зависимости объявлены на полях PrdSpecMtr и попадают в план цели.
        assertThat(registry.paths(Nomenclature.class, FetchScenario.LOOKUP))
            .contains("unitOfMeasurement");
        assertThat(registry.paths(PrdSpec.class, FetchScenario.LOOKUP))
            .contains("nomenclature", "nomenclature.unitOfMeasurement");
    }

    @Test
    void plansOfDifferentScenariosAndEntitiesAreCachedSeparately() {
        FetchPlanRegistry registry = registryOver(ReceivingDocument.class, Nomenclature.class,
            PrdSpec.class, PrdSpecMtr.class, UnitOfMeasurement.class);

        FetchPlan list = registry.plan(PrdSpecMtr.class, FetchScenario.LIST);
        FetchPlan row = registry.plan(PrdSpecMtr.class, FetchScenario.ROW);
        FetchPlan lookup = registry.plan(Nomenclature.class, FetchScenario.LOOKUP);

        assertThat(registry.plan(PrdSpecMtr.class, FetchScenario.LIST)).isSameAs(list);
        assertThat(row.scenario()).isEqualTo(FetchScenario.ROW);
        assertThat(lookup.entityClass()).isEqualTo(Nomenclature.class);
        assertThat(list.entityClass()).isEqualTo(PrdSpecMtr.class);
    }

    /**
     * C4.1 (ADR-0007 §4): единое правило {@code scenario plan ∪ extras -> deepen once}.
     * Прежняя асимметрия: {@code LookupService.entityGraph} объединял план и extras, но не
     * углублял дополнительный путь, поэтому имя его цели могло остаться без вложенной
     * загрузки.\n     */
    @Test
    void additionalPathsAreDeepenedTogetherWithTheScenarioPlan() {
        FetchPlanRegistry registry = registryOver(UnscenarioedReference.class, NamedHolder.class,
            Nomenclature.class, UnitOfMeasurement.class);

        assertThat(registry.paths(UnscenarioedReference.class, FetchScenario.LIST))
            .as("ссылка без @FieldMetadata не входит ни в один сценарий")
            .doesNotContain("named");

        assertThat(registry.pathsWith(UnscenarioedReference.class, FetchScenario.LIST,
                List.of("named")))
            .as("дополнительный путь углубляется через состав имени цели")
            .containsExactly("named", "named.nomenclature.unitOfMeasurement");
    }

    /**
     * C4.1 (ADR-0007 §4): правило {@code union → validate → deepen}. Без проверки
     * неизвестный динамический путь оставался в объединении и падал позже, на построении
     * {@code EntityGraph}.
     */
    @Test
    void unknownAdditionalPathIsRejectedBeforeUnion() {
        FetchPlanRegistry registry = registryOver(UnscenarioedReference.class, NamedHolder.class,
            Nomenclature.class, UnitOfMeasurement.class);

        assertThatThrownBy(() -> registry.pathsWith(UnscenarioedReference.class,
                FetchScenario.LIST, List.of("noSuchField")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("noSuchField");
    }

    @Test
    void pathsWithEmptyExtrasEqualsTheScenarioPlan() {
        FetchPlanRegistry registry = registryOver(NamedHolder.class, Nomenclature.class,
            UnitOfMeasurement.class);

        assertThat(registry.pathsWith(NamedHolder.class, FetchScenario.LIST, List.of()))
            .isEqualTo(registry.paths(NamedHolder.class, FetchScenario.LIST));
        assertThat(registry.pathsWith(NamedHolder.class, FetchScenario.LIST, null))
            .isEqualTo(registry.paths(NamedHolder.class, FetchScenario.LIST));
    }

    @Test
    void lookupPlanOrderAndDuplicatePathReasonDoNotDependOnManagedTypeIterationOrder() {
        List<Class<?>> input = List.of(LookupTarget.class, OtherDeclaringHolder.class,
            DeclaringHolder.class);
        List<Class<?>> reversed = new ArrayList<>(input);
        java.util.Collections.reverse(reversed);

        FetchPlan first = registryOver(input).plan(LookupTarget.class, FetchScenario.LOOKUP);
        FetchPlan second = registryOver(reversed).plan(LookupTarget.class, FetchScenario.LOOKUP);

        assertThat(first.paths()).isEqualTo(second.paths());
        assertThat(first.reasonFor("unitOfMeasurement.code"))
            .isEqualTo(second.reasonFor("unitOfMeasurement.code"))
            .isEqualTo("lookup:DeclaringHolder.target");
    }
}

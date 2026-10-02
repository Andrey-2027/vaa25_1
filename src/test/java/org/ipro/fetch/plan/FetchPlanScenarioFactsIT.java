package org.ipro.fetch.plan;

import org.ip.config.DataInitializer;
import org.ip.model.AttributeValue;
import org.ip.model.Nomenclature;
import org.ip.model.SklNomOpa;
import org.ip.model.SklNomOpaValue;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.FetchPlanInspection;
import org.ipro.metadata.FactOrigin;
import org.ipro.ureport.dom.UreportTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.2.0 шаг 2.1: замер площадки сценариев чтения на настоящих типах приложения — до того, как
 * карточка начнёт их показывать.
 *
 * <p>Замер отвечает на вопросы, которые нельзя решить чтением кода: откуда приходит набор
 * сценариев у типов с прикладным объявлением и без него, какие пути у плана каждого сценария и
 * какие причины у этих путей, и что бы показала строка сценария там, где сценария нет. Числа
 * печатаются в отчёт теста: причины путей ASCII ({@code metadata:LIST}, {@code instance-name},
 * {@code lookup:Owner.field}, {@code reference-name…}), поэтому замер читается без UI.</p>
 *
 * <p>Факты берутся через {@link FetchPlanInspection} — публичный тип владельца плана (2.3):
 * замер проверяет не только числа, но и то, что наружу они выходят без {@code INTERNAL}-типов и
 * что строки не дорисовываются — у сценария, которого нет ни в наборе, ни в плане, строки нет.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
@Transactional
class FetchPlanScenarioFactsIT {

    /** Пилоты замера: два типа с прикладным объявлением, owned-строка, корень правила. */
    private static final List<Class<?>> PILOTS = List.of(AttributeValue.class, SklNomOpa.class,
        SklNomOpaValue.class, Nomenclature.class);

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private EntityDescriptorCatalog descriptorCatalog;

    @Autowired
    private FetchPlanRegistry fetchPlanRegistry;

    @Autowired
    private FetchPlanInspection inspection;

    /** Тип с прикладным объявлением набора: origin — регистрация, а не правило экспозиции. */
    @Test
    void declaredScenarioSetComesFromTheApplicationPolicy() {
        for (Class<?> declared : List.of(AttributeValue.class, SklNomOpa.class)) {
            EntityDescriptor descriptor = descriptorCatalog.descriptorOf(declared);
            assertThat(descriptor.capabilitiesOrigin())
                .as("%s: набор сценариев объявлен приложением", declared.getSimpleName())
                .isEqualTo(FactOrigin.REGISTRATION);
            assertThat(descriptor.capabilitiesSymbol())
                .as("%s: место объявления в ядре не разрешается — граница E3.2.0 шаг 2",
                    declared.getSimpleName())
                .isEmpty();
            assertThat(descriptor.capabilities().readScenarios())
                .containsExactlyInAnyOrder(FetchScenario.LIST, FetchScenario.DETAIL,
                    FetchScenario.LOOKUP);
            assertThat(descriptor.capabilities().reason()).isNotBlank();
            report(declared);
        }

        // Причина объявления — прикладная фраза, а не текст платформенного правила.
        assertThat(descriptorCatalog.descriptorOf(AttributeValue.class).capabilities().reason())
            .contains("бессмерт");
    }

    /**
     * Owned-строка: сценарий `ROW` приходит от правила экспозиции, а канонический план пуст —
     * состав ведёт typed use case агрегата, а не generic section machinery. Ветвь «сценарий
     * допущен, путей нет» — реальная: строка существует с нулём путей.
     */
    @Test
    void ownedRowTakesItsScenarioFromTheExposureRule() {
        EntityDescriptor descriptor = descriptorCatalog.descriptorOf(SklNomOpaValue.class);
        assertThat(descriptor.exposure()).isEqualTo(org.ipro.data.EntityExposure.OWNED_ROW);
        assertThat(descriptor.capabilities().readScenarios()).containsExactly(FetchScenario.ROW);
        assertThat(descriptor.capabilitiesOrigin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        assertThat(descriptor.capabilitiesSymbol()).isEmpty();
        report(SklNomOpaValue.class);

        assertThat(inspection.scenariosOf(SklNomOpaValue.class)).singleElement()
            .satisfies(row -> {
                assertThat(row.allowed()).isTrue();
                assertThat(row.pathCount()).isZero();
            });
    }

    /**
     * Стандартный корень: сценарий `ROW` не допущен, но **план у него есть** — ветвь «не допущен,
     * но план непуст» реальна. Такая строка обязана появиться с пояснением, что canonical path
     * сценарий не допускает: скрыть существующий план — та же ложь, что выдумать строку.
     */
    @Test
    void standardRootHasRowPlanWithoutRowScenario() {
        EntityDescriptor descriptor = descriptorCatalog.descriptorOf(Nomenclature.class);
        assertThat(descriptor.capabilities().readScenarios())
            .containsExactlyInAnyOrder(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP);
        assertThat(descriptor.capabilitiesOrigin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        report(Nomenclature.class);

        assertThat(inspection.scenariosOf(Nomenclature.class))
            .filteredOn(row -> "ROW".equals(row.scenario()))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.allowed()).as("ROW у стандартного корня не допущен").isFalse();
                assertThat(row.pathCount()).as("но план ROW непуст — строка не должна исчезнуть")
                    .isPositive();
            });
    }

    /** Тип без объявленных сценариев: показывать нечего, и это факт, а не отсутствие данных. */
    @Test
    void typeWithoutDeclaredScenariosHasNoPlanToShow() {
        EntityDescriptor descriptor = descriptorCatalog.descriptorOf(UreportTemplate.class);
        assertThat(descriptor.capabilities().readScenarios()).isEmpty();
        assertThat(descriptor.capabilitiesOrigin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        assertThat(inspection.scenariosOf(UreportTemplate.class)).isEmpty();
    }

    /**
     * Союз и счёт: набор строк равен «допущено ∪ план непуст», а число строк путей равно плану
     * реестра для каждого показанного сценария — на всех пилотах сразу.
     */
    @Test
    void rowsAreTheUnionAndPathCountEqualsThePlan() {
        for (Class<?> type : PILOTS) {
            Set<FetchScenario> allowed = descriptorCatalog.descriptorOf(type)
                .capabilities().readScenarios();

            List<String> expected = new ArrayList<>();
            for (FetchScenario scenario : FetchScenario.values()) {
                if (allowed.contains(scenario) || !fetchPlanRegistry.plan(type, scenario).isEmpty()) {
                    expected.add(scenario.name());
                }
            }

            List<FetchPlanInspection.Scenario> rows = inspection.scenariosOf(type);
            assertThat(rows).extracting(FetchPlanInspection.Scenario::scenario)
                .as("%s: строки — союз «допущено ∪ план непуст», порядок — FetchScenario.values()",
                    type.getSimpleName())
                .containsExactlyElementsOf(expected);
            for (FetchPlanInspection.Scenario row : rows) {
                FetchScenario scenario = FetchScenario.valueOf(row.scenario());
                assertThat(row.pathCount())
                    .as("%s/%s: число строк путей равно плану реестра", type.getSimpleName(), scenario)
                    .isEqualTo(fetchPlanRegistry.paths(type, scenario).size());
            }
        }
    }

    /**
     * Печать и проверка строк замера: порядок путей — как в плане, у каждого пути есть причина,
     * число путей равно плану реестра.
     */
    private void report(Class<?> type) {
        for (FetchPlanInspection.Scenario row : inspection.scenariosOf(type)) {
            System.out.println("[E3.2.0-2.1] " + type.getSimpleName() + " " + row.scenario()
                + " allowed=" + row.allowed() + " origin=" + row.origin()
                + " paths=" + row.pathCount() + " "
                + row.paths().stream().map(FetchPlanInspection.Path::attributePath).toList()
                + " reasons=" + row.paths().stream().map(FetchPlanInspection.Path::reason).toList());

            assertThat(row.paths()).extracting(FetchPlanInspection.Path::attributePath).isSorted();
            for (FetchPlanInspection.Path path : row.paths()) {
                assertThat(path.reason())
                    .as("%s/%s: у пути обязана быть причина", type.getSimpleName(), path.attributePath())
                    .isNotBlank();
            }
            assertThat(row.pathCount())
                .as("%s/%s: строки не выдуманы и не потеряны", type.getSimpleName(), row.scenario())
                .isEqualTo(fetchPlanRegistry.paths(type, FetchScenario.valueOf(row.scenario())).size());
        }
    }
}

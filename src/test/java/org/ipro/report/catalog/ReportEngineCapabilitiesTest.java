package org.ipro.report.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.ipro.report.catalog.ReportEngineCapabilities.OpenTarget.EDITOR;
import static org.ipro.report.catalog.ReportEngineCapabilities.OpenTarget.INFO;
import static org.ipro.report.catalog.ReportEngineType.JR;
import static org.ipro.report.catalog.ReportEngineType.UDR;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.ipro.report.catalog.ReportEngineCapabilities.Capabilities;
import org.junit.jupiter.api.Test;

/**
 * Матрица возможностей — это контракт каталога, снятый с кода до D3.6.
 *
 * <p>Таблица проверяется целиком, а не «хотя бы одно поле»: смысл матрицы в том,
 * чтобы решения о движке были данными в одном месте, поэтому тест ловит и
 * случайное изменение правила, и добавление движка без записи в таблице.</p>
 */
class ReportEngineCapabilitiesTest {

    @Test
    void everyEngineOfTheCatalogHasAnEntry() {
        for (ReportEngineType type : ReportEngineType.values()) {
            assertThat(ReportEngineCapabilities.of(type))
                    .as("движок %s обязан быть в матрице", type)
                    .isNotNull();
        }
    }

    @Test
    void capabilitiesMatchTheCatalogContractItemByItem() {
        assertThat(ReportEngineCapabilities.of(UDR))
                .as("UDR: встроенный редактор, копия и экспорт JSON, удаление не в каталоге")
                .isEqualTo(new Capabilities("UDR", EDITOR, true, true, false));
        assertThat(ReportEngineCapabilities.of(JR))
                .as("JR: только сведения, макет правится вне приложения, удаление в каталоге")
                .isEqualTo(new Capabilities("JR", INFO, false, false, true));
        assertThat(ReportEngineCapabilities.of(ReportEngineType.UNIDR))
                .isEqualTo(new Capabilities("UniDR", ReportEngineCapabilities.OpenTarget.UNIDR_EDITOR, false, false, false));
    }

    @Test
    void onlyUdrSupportsCopyAndJsonExport() {
        assertThat(enginesWith(Capabilities::copy))
                .as("копия и экспорт JSON существуют только у конструктора UDR — "
                        + "именно это сообщает каталог отказавшемуся пользователю")
                .containsExactly(UDR);
        assertThat(enginesWith(Capabilities::exportJson)).containsExactly(UDR);
    }

    @Test
    void recipeEditorsAreNotDeletableInsideTheCatalog() {
        assertThat(enginesWith(capabilities -> !capabilities.deleteInCatalog()))
                .as("UDR и UniDR не удаляются в каталоге")
                .containsExactlyInAnyOrder(UDR, ReportEngineType.UNIDR);
    }

    @Test
    void everyEngineOpensInItsOwnPlace() {
        Set<ReportEngineCapabilities.OpenTarget> targets = Arrays.stream(ReportEngineType.values())
                .map(type -> ReportEngineCapabilities.of(type).openTarget())
                .collect(Collectors.toSet());

        assertThat(targets)
                .as("цель открытия — свойство движка: совпадение целей означало бы, "
                        + "что матрица не описывает реальные различия")
                .hasSize(ReportEngineType.values().length)
                .containsExactlyInAnyOrder(EDITOR, INFO, ReportEngineCapabilities.OpenTarget.UNIDR_EDITOR);
    }

    @Test
    void missingEngineTypeIsRejected() {
        assertThatThrownBy(() -> ReportEngineCapabilities.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static Set<ReportEngineType> enginesWith(
            java.util.function.Predicate<Capabilities> rule) {
        return Arrays.stream(ReportEngineType.values())
                .filter(type -> rule.test(ReportEngineCapabilities.of(type)))
                .collect(Collectors.toSet());
    }
}

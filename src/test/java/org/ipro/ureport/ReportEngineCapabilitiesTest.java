package org.ipro.ureport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.ipro.ureport.catalog.ReportEngineCapabilities.OpenTarget.DESIGNER;
import static org.ipro.ureport.catalog.ReportEngineCapabilities.OpenTarget.EDITOR;
import static org.ipro.ureport.catalog.ReportEngineCapabilities.OpenTarget.INFO;
import static org.ipro.ureport.catalog.ReportEngineType.JR;
import static org.ipro.ureport.catalog.ReportEngineType.UDR;
import static org.ipro.ureport.catalog.ReportEngineType.UREPORT3;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.ipro.ureport.catalog.ReportEngineCapabilities;
import org.ipro.ureport.catalog.ReportEngineCapabilities.Capabilities;
import org.ipro.ureport.catalog.ReportEngineType;
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
        assertThat(ReportEngineCapabilities.of(UREPORT3))
                .as("UReport3: внешний дизайнер, правится в новой вкладке, удаление в каталоге")
                .isEqualTo(new Capabilities("UReport3", DESIGNER, false, false, true));
        assertThat(ReportEngineCapabilities.of(JR))
                .as("JR: только сведения, макет правится вне приложения, удаление в каталоге")
                .isEqualTo(new Capabilities("JR", INFO, false, false, true));
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
    void onlyUdrIsNotDeletableInsideTheCatalog() {
        assertThat(enginesWith(capabilities -> !capabilities.deleteInCatalog()))
                .as("UDR удаляется из редактора, остальные движки — подтверждением в каталоге")
                .containsExactly(UDR);
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
                .containsExactlyInAnyOrder(EDITOR, DESIGNER, INFO);
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

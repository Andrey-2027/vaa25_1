package org.ipro.report.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Сборка строки каталога — единственный узел (D3.6).
 *
 * <p>Фабрика знает только механическую сборку: правила движка (включая
 * {@code fileMissing}) остаются в его адаптере. UDR-строку никто не собирает
 * (R3.2), JR-строку собирает platform-jr.</p>
 */
class ReportCatalogItemFactoryTest {

    @Test
    void mechanicalRowKeepsEngineSpecificEnableFlag() {
        ReportCatalogItem item = ReportCatalogItemFactory.of(7L, ReportEngineType.UNIDR,
                "Остатки", "Склад", true, false);

        assertThat(item.id()).isEqualTo(7L);
        assertThat(item.type()).isEqualTo(ReportEngineType.UNIDR);
        assertThat(item.enabled()).isTrue();
    }
}

package org.ipro.report.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.junit.jupiter.api.Test;

/**
 * Сборка строки каталога — единственный узел (D3.6).
 *
 * <p>Правила, которые до D3.6 повторялись в шести местах и могли разойтись:
 * «включён» для UDR = PUBLISHED (у JR — собственный флаг), отсутствие макета —
 * признак {@code fileMissing}.</p>
 */
class ReportCatalogItemFactoryTest {

    @Test
    void udrRowIsEnabledOnlyWhenPublished() {
        ReportCatalogItem published = ReportCatalogItemFactory.of(
                udrTemplate(3L, "Остатки", "Склад", ReportTemplateState.PUBLISHED));
        ReportCatalogItem draft = ReportCatalogItemFactory.of(
                udrTemplate(4L, "Черновик", null, ReportTemplateState.DRAFT));

        assertThat(published.type()).isEqualTo(ReportEngineType.UDR);
        assertThat(published.id()).isEqualTo(3L);
        assertThat(published.name()).isEqualTo("Остатки");
        assertThat(published.description()).isEqualTo("Склад");
        assertThat(published.enabled()).as("UDR включён = опубликован").isTrue();
        assertThat(published.fileMissing()).as("у UDR нет внешнего файла").isFalse();
        assertThat(draft.enabled()).as("черновик не включён").isFalse();
    }

    @Test
    void jrRowReportsTheMissingLayout() {
        JrxmlTemplate template = new JrxmlTemplate();
        template.setId(4L);
        template.setName("Прайс лист");
        template.setDescription("Прайс");
        template.setFileName("price.jrxml");
        template.setEnabled(false);

        ReportCatalogItem item = ReportCatalogItemFactory.of(template, true);

        assertThat(item.type()).isEqualTo(ReportEngineType.JR);
        assertThat(item.enabled()).isFalse();
        assertThat(item.fileMissing()).isTrue();
    }

    @Test
    void missingEngineTemplateIsRejected() {
        assertThatThrownBy(() -> ReportCatalogItemFactory.of((ReportTemplate) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReportCatalogItemFactory.of((JrxmlTemplate) null, false))
                .isInstanceOf(NullPointerException.class);
    }

    private static ReportTemplate udrTemplate(long id, String name, String description,
                                              ReportTemplateState state) {
        ReportTemplate template = new ReportTemplate();
        template.setId(id);
        template.setName(name);
        template.setDescription(description);
        template.setState(state);
        return template;
    }
}

package org.ipro.ureport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.ipro.ureport.catalog.ReportCatalogItem;
import org.ipro.ureport.catalog.ReportCatalogItemFactory;
import org.ipro.ureport.catalog.ReportEngineType;
import org.ipro.ureport.dom.UreportTemplate;
import org.junit.jupiter.api.Test;

/**
 * Сборка строки каталога — единственный узел (D3.6).
 *
 * <p>Правила, которые до D3.6 повторялись в шести местах и могли разойтись:
 * «включён» для UDR = PUBLISHED (у UReport3/JR — собственный флаг), URL дизайнера
 * только у UReport3, отсутствие файла — признак {@code fileMissing}.</p>
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
        assertThat(published.designerUrl()).isNull();
        assertThat(published.fileMissing()).as("у UDR нет внешнего файла").isFalse();
        assertThat(draft.enabled()).as("черновик не включён").isFalse();
    }

    @Test
    void ureportRowCarriesTheDesignerUrlAndEngineOwnEnabledFlag() {
        UreportTemplate template = new UreportTemplate();
        template.setId(2L);
        template.setName("Азбука");
        template.setDescription("Печатная форма");
        template.setFileName("azbuka.ureport.xml");
        template.setEnabled(true);

        ReportCatalogItem item = ReportCatalogItemFactory.of(template, true);

        assertThat(item.type()).isEqualTo(ReportEngineType.UREPORT3);
        assertThat(item.description()).isEqualTo("Печатная форма");
        assertThat(item.enabled()).as("флаг движка, а не состояние публикации").isTrue();
        assertThat(item.designerUrl())
                .as("без URL дизайнера запись нельзя открыть из каталога")
                .isEqualTo(org.ipro.ureport.service.UreportTemplateService
                        .designerUrl("azbuka.ureport.xml"));
        assertThat(item.fileMissing()).as("XML-файла нет в хранилище").isTrue();
    }

    @Test
    void jrRowHasNoDesignerUrlAndReportsTheMissingLayout() {
        JrxmlTemplate template = new JrxmlTemplate();
        template.setId(4L);
        template.setName("Прайс лист");
        template.setDescription("Прайс");
        template.setFileName("price.jrxml");
        template.setEnabled(false);

        ReportCatalogItem item = ReportCatalogItemFactory.of(template, true);

        assertThat(item.type()).isEqualTo(ReportEngineType.JR);
        assertThat(item.enabled()).isFalse();
        assertThat(item.designerUrl()).as("макет правится в Jaspersoft Studio").isNull();
        assertThat(item.fileMissing()).isTrue();
    }

    @Test
    void missingEngineTemplateIsRejected() {
        assertThatThrownBy(() -> ReportCatalogItemFactory.of((ReportTemplate) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReportCatalogItemFactory.of((UreportTemplate) null, false))
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

package org.ip.views.reportstudio;

import org.ipro.reportstudio.dom.ReportBandKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportEditorViewTest {

    @Test
    void initializesEmptyTemplateMetadataAndMandatoryDetailBand() {
        ReportEditorView view = new ReportEditorView(null, null, null, null, null, null);

        assertThat(view.reportName()).isEmpty();
        assertThat(view.reportDescription()).isEmpty();
        assertThat(view.editedTemplate().getBands())
                .extracting(band -> band.getKind())
                .containsExactly(ReportBandKind.DETAIL);
    }

    @Test
    void opensExistingTemplateProvidedByCatalog() {
        ReportEditorView view = new ReportEditorView(null, null, null, null, null, null);
        org.ipro.reportstudio.dom.ReportTemplate template = new org.ipro.reportstudio.dom.ReportTemplate();
        template.setName("Остатки");
        template.setDescription("На складе");
        template.setJpql("select j from Journal j");

        view.editTemplate(template);

        assertThat(view.editedTemplate()).isSameAs(template);
        assertThat(view.reportName()).isEqualTo("Остатки");
        assertThat(view.reportDescription()).isEqualTo("На складе");
    }
}

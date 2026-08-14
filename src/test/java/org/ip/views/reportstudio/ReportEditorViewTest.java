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
}

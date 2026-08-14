package org.ip.views.reportstudio;

import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportStructureEditorTest {

    @Test
    void initializesMandatoryDetailBandForNewTemplate() {
        ReportStructureEditor editor = new ReportStructureEditor();
        ReportTemplate template = new ReportTemplate();

        editor.setTemplate(template);

        assertThat(editor.getTemplate()).isSameAs(template);
        assertThat(template.getBands())
                .extracting(band -> band.getKind())
                .containsExactly(ReportBandKind.DETAIL);
    }
}

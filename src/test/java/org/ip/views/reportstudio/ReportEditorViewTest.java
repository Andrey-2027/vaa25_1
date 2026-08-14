package org.ip.views.reportstudio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportEditorViewTest {

    @Test
    void initializesEmptyTemplateMetadata() {
        ReportEditorView view = new ReportEditorView(null, null);

        assertThat(view.reportName()).isEmpty();
        assertThat(view.reportDescription()).isEmpty();
    }
}

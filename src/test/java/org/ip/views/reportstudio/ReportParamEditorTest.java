package org.ip.views.reportstudio;

import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReportParamEditorTest {

    @Test
    void addsValidFormScalarDeclarationToTemplate() {
        ReportParamEditor editor = new ReportParamEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        AtomicInteger changes = new AtomicInteger();
        editor.setChangeListener(changes::incrementAndGet);

        editor.addParam();

        assertThat(editor.getTemplate()).isSameAs(template);
        assertThat(template.getParams()).hasSize(1);
        assertThat(changes).hasValue(1);
        assertThat(template.getParams().getFirst())
                .satisfies(param -> {
                    assertThat(param.getName()).isEqualTo("param1");
                    assertThat(param.getKind()).isEqualTo(ReportParamKind.SCALAR);
                    assertThat(param.getValueSource()).isEqualTo(ReportParamSource.FORM);
                    assertThat(param.isShowOnForm()).isTrue();
                });
    }
}

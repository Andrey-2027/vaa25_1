package org.ipro.reportstudio.query.editor;

import org.ip.form.SelectionFormAssembler;
import org.ip.metadata.annotation.FieldType;
import org.ip.service.LookupService;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReportQueryEditorTest {

    private ReportQueryEditor newEditor(ReportTemplate template) {
        ReportQueryEditor editor = new ReportQueryEditor(
                mock(QueryEditorAnalysisService.class),
                mock(QueryMetadataCatalogService.class),
                mock(ReportPreviewService.class),
                mock(LookupService.class),
                mock(SelectionFormAssembler.class));
        editor.setTemplate(template);
        return editor;
    }

    @Test
    void transferCreatesMissingScalarParamFromTestValue() {
        ReportTemplate template = new ReportTemplate();
        template.setJpql("select a.code from Nomenclature a where a.name = :parName");
        ReportQueryEditor editor = newEditor(template);

        editor.transferTestParamsToTemplate();

        assertThat(template.getParams()).singleElement().satisfies(param -> {
            assertThat(param.getName()).isEqualTo("parName");
            assertThat(param.getKind()).isEqualTo(ReportParamKind.SCALAR);
            assertThat(param.getValueSource()).isEqualTo(ReportParamSource.FORM);
            assertThat(param.isShowOnForm()).isTrue();
            assertThat(param.isRequired()).isFalse();
            assertThat(param.getPosition()).isZero();
        });
    }

    @Test
    void transferIsIdempotentAndKeepsExistingDeclarations() {
        ReportTemplate template = new ReportTemplate();
        template.setJpql("select a.code from Nomenclature a where a.name = :parName");
        ReportQueryEditor editor = newEditor(template);

        editor.transferTestParamsToTemplate();
        editor.transferTestParamsToTemplate();

        assertThat(template.getParams()).hasSize(1);
    }

    @Test
    void entityTestParamTransfersAsEntityWithClassName() {
        ReportTemplate template = new ReportTemplate();
        template.setJpql("select a.code from Nomenclature a where a.journal = :journal");
        ReportQueryEditor editor = newEditor(template);
        QueryTestParam journal = editor.testParams().get(0);
        journal.setType(FieldType.ENTITY_REFERENCE);
        journal.setClassName("org.ip.model.Journal");

        editor.transferTestParamsToTemplate();

        assertThat(template.getParams()).singleElement().satisfies(param -> {
            assertThat(param.getName()).isEqualTo("journal");
            assertThat(param.getKind()).isEqualTo(ReportParamKind.ENTITY);
            assertThat(param.getEntityClass()).isEqualTo("org.ip.model.Journal");
        });
    }

    @Test
    void typingInEditorUpdatesTemplateAndFiresChangeListener() {
        ReportTemplate template = new ReportTemplate();
        template.setJpql("select 1");
        ReportQueryEditor editor = newEditor(template);
        ReportTemplate[] changed = new ReportTemplate[1];
        editor.setChangeListener(value -> changed[0] = value);

        editor.applyJpqlText("select a.code from Nomenclature a");

        assertThat(template.getJpql()).isEqualTo("select a.code from Nomenclature a");
        assertThat(changed[0]).isSameAs(template);
        assertThat(editor.getJpql()).isEqualTo("select a.code from Nomenclature a");
    }
}

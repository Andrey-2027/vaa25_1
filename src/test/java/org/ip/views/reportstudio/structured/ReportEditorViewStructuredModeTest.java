package org.ip.views.reportstudio.structured;

import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.junit.jupiter.api.Test;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.crud.LookupService;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

class ReportEditorViewStructuredModeTest {

    @Test
    void startsInAdvancedMode() {
        ReportEditorViewStructured view = newView();

        assertThat(view.layoutMode()).isEqualTo(ReportEditorMode.ADVANCED);
        assertThat(view.layoutContent().getChildren().toList())
                .containsExactly(view.structureEditor());
    }

    @Test
    void switchingToUserModeDoesNotChangeTemplate() {
        ReportEditorViewStructured view = newView();
        ReportTemplate template = view.editedTemplate();
        int bandCount = template.getBands().size();

        view.setLayoutModeForTest(ReportEditorMode.USER);

        assertThat(view.layoutMode()).isEqualTo(ReportEditorMode.USER);
        assertThat(view.layoutContent().getChildren().toList()).doesNotContain(view.structureEditor());
        assertThat(template.getBands()).hasSize(bandCount);
        assertThat(template.getBands()).extracting(band -> band.getKind())
                .containsExactly(ReportBandKind.DETAIL);

        view.setLayoutModeForTest(ReportEditorMode.ADVANCED);
        assertThat(view.layoutContent().getChildren().toList())
                .containsExactly(view.structureEditor());
    }

    private static ReportEditorViewStructured newView() {
        QueryMetadataCatalogService catalog = mock(QueryMetadataCatalogService.class);
        when(catalog.entityOptions()).thenReturn(List.of());
        when(catalog.roots(any())).thenReturn(List.of());
        return new ReportEditorViewStructured(
                mock(ReportQueryGuard.class),
                mock(ReportPreviewService.class),
                mock(QueryEditorAnalysisService.class),
                catalog,
                mock(ReportTemplateService.class),
                mock(ReportExecutionService.class),
                mock(LookupService.class),
                mock(SelectionFormAssembler.class));
    }
}

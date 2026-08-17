package org.ip.views.reportstudio;

import org.ip.form.SelectionFormAssembler;
import org.ip.service.LookupService;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.reportstudio.transfer.ReportTemplateTransferService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportCatalogViewTest {

    @Test
    void loadsTemplateListOnInitialization() {
        ReportTemplateService service = mock(ReportTemplateService.class);
        QueryMetadataCatalogService catalog = mock(QueryMetadataCatalogService.class);
        when(service.search(any())).thenReturn(List.of());
        when(catalog.roots(any())).thenReturn(List.of());

        new ReportCatalogView(service, mock(ReportTemplateTransferService.class),
                mock(ReportQueryGuard.class), mock(ReportPreviewService.class),
                mock(QueryEditorAnalysisService.class), catalog,
                mock(ReportExecutionService.class),
                mock(LookupService.class), mock(SelectionFormAssembler.class));

        verify(service).search("");
    }
}

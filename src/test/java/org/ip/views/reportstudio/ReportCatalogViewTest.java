package org.ip.views.reportstudio;

import org.ipro.form.SelectionFormAssembler;
import org.ipro.crud.LookupService;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.reportstudio.transfer.ReportTemplateTransferService;
import org.ipro.ureport.catalog.ReportCatalogService;
import org.ipro.ureport.service.UreportTemplateService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportCatalogViewTest {

    @Test
    void loadsUnifiedCatalogOnInitialization() {
        ReportTemplateService service = mock(ReportTemplateService.class);
        QueryMetadataCatalogService catalog = mock(QueryMetadataCatalogService.class);
        when(service.search(any())).thenReturn(List.of());

        ReportCatalogService catalogService = mock(ReportCatalogService.class);
        when(catalogService.findAll(any(), anyBoolean())).thenReturn(List.of());

        new ReportCatalogView(service, mock(ReportTemplateTransferService.class),
                catalogService, mock(UreportTemplateService.class),
                mock(ReportQueryGuard.class), mock(ReportPreviewService.class),
                mock(QueryEditorAnalysisService.class), catalog,
                mock(ReportExecutionService.class),
                mock(LookupService.class), mock(SelectionFormAssembler.class));

        // единый каталог мёржит оба движка, а не только UDR
        verify(catalogService).findAll("", true);
    }
}

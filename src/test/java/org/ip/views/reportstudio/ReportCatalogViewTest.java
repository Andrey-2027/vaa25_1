package org.ip.views.reportstudio;

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
        when(service.search(any())).thenReturn(List.of());

        new ReportCatalogView(service, mock(ReportTemplateTransferService.class), null, null, null, null, null);

        verify(service).search("");
    }
}

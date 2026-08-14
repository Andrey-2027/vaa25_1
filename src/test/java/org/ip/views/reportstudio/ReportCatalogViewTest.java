package org.ip.views.reportstudio;

import org.ipro.reportstudio.service.ReportTemplateService;
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

        new ReportCatalogView(service, null, null, null, null, null);

        verify(service).search("");
    }
}

package org.ipro.reportstudio.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReportTemplateScopeTransferTest {

    @Test
    void exportsTargetEntityClass() {
        ReportTemplate template = new ReportTemplate();
        template.setName("Spec print");
        template.setJpql("select s.code as code from PrdSpec s");
        template.setTargetEntityClass("org.ip.model.PrdSpec");

        ReportTemplateTransferService service = new ReportTemplateTransferService(
                new ObjectMapper(), mock(ReportQueryGuard.class), mock(ReportTemplateService.class));

        String json = service.exportTemplate(template);

        assertThat(json).contains("\"targetEntityClass\" : \"org.ip.model.PrdSpec\"");
    }
}

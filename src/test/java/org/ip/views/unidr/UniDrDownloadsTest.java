package org.ipro.unidr.ui;

import org.ipro.unidr.render.UniDrRenderedReport;
import org.ipro.unidr.run.UniDrAccess;
import org.ipro.report.render.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrDownloadsTest {
    final AtomicBoolean enabled=new AtomicBoolean(true);
    final org.ipro.report.render.JasperExporter exporter=mock(org.ipro.report.render.JasperExporter.class);
    final net.sf.jasperreports.engine.JasperPrint print=new net.sf.jasperreports.engine.JasperPrint();
    final UniDrAccess access=new UniDrAccess(enabled::get,()->SecurityContextHolder.getContext().getAuthentication().getName());
    final UniDrDownloads downloads=new UniDrDownloads(new UniDrRenderedReport(print,"Отчёт","alice",access,exporter));
    void login(String user) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of())); }
    @BeforeEach void setup() {login("alice");when(exporter.export(print,ReportExportFormat.PDF)).thenReturn(new byte[]{1,2,3});}
    @AfterEach void cleanup() {SecurityContextHolder.clearContext();}
    @Test void resourceUsesOwnerCheckedCachedBytesAndBecomesGoneOnRevisionChange() throws Exception {
        assertThat(downloads.response(ReportExportFormat.PDF).getInputStream().readAllBytes()).containsExactly(1,2,3);
        downloads.invalidate();assertThat(downloads.response(ReportExportFormat.PDF).getError()).isEqualTo(410);verify(exporter,times(1)).export(print,ReportExportFormat.PDF);
    }
    @Test void copiedUrlCannotBeUsedByDifferentOwnerOrAfterPilotIsDisabled() {
        downloads.response(ReportExportFormat.PDF);login("bob");assertThat(downloads.response(ReportExportFormat.PDF).getError()).isEqualTo(403);
        login("alice");enabled.set(false);assertThat(downloads.response(ReportExportFormat.PDF).getError()).isEqualTo(403);
    }
    @Test void exporterFailureDoesNotExposeInternalMessage() {
        when(exporter.export(print,ReportExportFormat.PDF)).thenThrow(new IllegalStateException("SQL secret"));
        var response=downloads.response(ReportExportFormat.PDF);assertThat(response.getError()).isEqualTo(500);assertThat(response.getErrorMessage()).doesNotContain("secret");
    }
}

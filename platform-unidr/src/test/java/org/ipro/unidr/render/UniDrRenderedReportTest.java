package org.ipro.unidr.render;

import net.sf.jasperreports.engine.JasperPrint;
import org.ipro.unidr.run.*;
import org.ipro.report.render.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrRenderedReportTest {
    private final AtomicBoolean enabled=new AtomicBoolean(true);
    private final UniDrAccess access=new UniDrAccess(enabled::get,()->SecurityContextHolder.getContext().getAuthentication().getName());
    private final org.ipro.report.render.JasperExporter exporter=mock(org.ipro.report.render.JasperExporter.class);
    private final JasperPrint print=new JasperPrint();
    private final UniDrRenderedReport report=new UniDrRenderedReport(print,"Отчёт / накладные","alice",access,exporter);
    private void login(String name) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,List.of())); }
    @BeforeEach void setup() { login("alice"); when(exporter.export(print,ReportExportFormat.PDF)).thenReturn(new byte[]{1,2,3}); when(exporter.export(print,ReportExportFormat.XLSX)).thenReturn(new byte[]{4,5}); }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void previewAndExportsReuseOnePrintAndEachFormatOnce() {
        assertThat(report.preview().bytes()).containsExactly(1,2,3);
        assertThat(report.export(ReportExportFormat.PDF).bytes()).containsExactly(1,2,3);
        assertThat(report.export(ReportExportFormat.XLSX).bytes()).containsExactly(4,5);
        report.export(ReportExportFormat.XLSX);
        verify(exporter,times(1)).export(print,ReportExportFormat.PDF); verify(exporter,times(1)).export(print,ReportExportFormat.XLSX);
        assertThat(report.preview().mimeType()).isEqualTo("application/pdf"); assertThat(report.preview().fileName()).isEqualTo("Отчёт_накладные.pdf");
    }
    @Test void exportedArraysCannotMutateCachedFiles() {
        var download=report.preview(); byte[] bytes=download.bytes();bytes[0]=99;
        assertThat(download.bytes()).containsExactly(1,2,3); assertThat(report.preview().bytes()).containsExactly(1,2,3);
    }
    @Test void cachedExportsStillRequireOwnerAndFeatureFlag() {
        report.preview();login("bob"); assertThatThrownBy(report::preview).isInstanceOf(UniDrRunException.class);
        login("alice");enabled.set(false);assertThatThrownBy(report::preview).isInstanceOf(UniDrRunException.class);
        verify(exporter,times(1)).export(print,ReportExportFormat.PDF);
    }
    @Test void unsupportedFormatsAreRejectedBeforeExporter() {
        assertThatThrownBy(()->report.export(ReportExportFormat.CSV)).isInstanceOf(IllegalArgumentException.class); verifyNoInteractions(exporter);
    }
    @Test void failedExportHasSafeMessageAndCanBeRetried() {
        doThrow(new ReportRenderException("internal SQL/markup secret")).doReturn(new byte[]{7}).when(exporter).export(print,ReportExportFormat.PDF);
        assertThatThrownBy(report::preview).isInstanceOf(ReportRenderException.class).hasMessageNotContaining("secret");
        assertThat(report.preview().bytes()).containsExactly(7);
    }
}

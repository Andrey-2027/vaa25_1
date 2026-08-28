package org.ipro.reportstudio.render;

import net.sf.dynamicreports.report.builder.DynamicReports;
import net.sf.dynamicreports.report.builder.column.TextColumnBuilder;
import net.sf.dynamicreports.report.datasource.DRDataSource;
import net.sf.jasperreports.engine.JasperPrint;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Технический spike: проверяет замену COUNT_ROWS синтетической non-null колонкой. */
class CountRowsDynamicReportsSpikeTest {

    @Test
    void syntheticNonNullColumnCountsRowsInSummaryAndGroup() throws Exception {
        TextColumnBuilder<String> groupColumn = DynamicReports.col.column("Группа", "group", String.class);
        TextColumnBuilder<String> nullableColumn = DynamicReports.col.column("Значение", "value", String.class);
        TextColumnBuilder<Integer> rowMarker = DynamicReports.col.column("", "row_marker", Integer.class);
        var group = DynamicReports.grp.group(groupColumn);
        var report = DynamicReports.report()
                .columns(groupColumn, nullableColumn, rowMarker)
                .groupBy(group)
                .subtotalsAtGroupFooter(group, DynamicReports.sbt.count(rowMarker))
                .subtotalsAtSummary(DynamicReports.sbt.count(rowMarker));

        DRDataSource data = new DRDataSource("group", "value", "row_marker");
        data.add("A", "filled", 1);
        data.add("A", null, 1);
        data.add("B", null, 1);
        report.setDataSource(data);

        JasperPrint print = report.toJasperPrint();
        byte[] pdf = new JasperReportCompiler().export(print, ReportExportFormat.PDF);
        String text;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(document);
        }

        assertThat(text).contains("1").contains("2");

        byte[] xlsx = new JasperReportCompiler().export(print, ReportExportFormat.XLSX);
        assertThat(xlsx).isNotEmpty();
        byte[] csv = new JasperReportCompiler().export(print, ReportExportFormat.CSV);
        String csvText = new String(csv, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csvText).doesNotContain("Маркер");
    }
}

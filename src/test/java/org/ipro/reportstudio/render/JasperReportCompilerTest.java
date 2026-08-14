package org.ipro.reportstudio.render;

import net.sf.jasperreports.engine.JasperPrint;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.data.ReportRow;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JasperReportCompilerTest {

    @Test
    void compileGroupedReportToAllFormats() throws Exception {
        QueryField journal = new QueryField("journalCode", "", String.class, "Журнал", true, true, false);
        QueryField code = new QueryField("codeSpec", "", String.class, "Код спецификации", true, true, true);
        ReportRow row1 = new ReportRow(new QueryField[]{journal, code}, new Object[]{"A", "SPEC-1"});
        ReportRow row2 = new ReportRow(new QueryField[]{journal, code}, new Object[]{"A", "SPEC-2"});
        ReportDataset dataset = new ReportDataset(new QueryField[]{journal, code}, new ReportRow[]{row1, row2});

        ReportTemplate template = new ReportTemplate();
        template.setName("Тестовый отчёт");
        template.setState(ReportTemplateState.PUBLISHED);

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("journalCode", "Журнал", null));
        detail.addField(field("codeSpec", "Код спецификации", null));
        template.addBand(detail);

        ReportBand group = band(ReportBandKind.GROUP_HEADER, null, "journalCode");
        template.addBand(group);

        ReportBand groupFooter = band(ReportBandKind.GROUP_FOOTER, group, null);
        ReportField count = field("codeSpec", "Количество", null);
        count.setAggregation(ReportFieldAggregation.COUNT);
        groupFooter.addField(count);
        template.addBand(groupFooter);

        ReportBand reportFooter = band(ReportBandKind.REPORT_FOOTER, null, null);
        ReportField total = field("codeSpec", "Всего", null);
        total.setAggregation(ReportFieldAggregation.COUNT);
        reportFooter.addField(total);
        template.addBand(reportFooter);

        ReportCompiler compiler = new JasperReportCompiler();
        JasperPrint print = compiler.compile(template, dataset);
        for (var page : print.getPages()) {
            java.util.List<net.sf.jasperreports.engine.JRPrintElement> elements = page.getElements();
            System.out.println("=== PAGE: " + elements.size() + " elements");
            for (net.sf.jasperreports.engine.JRPrintElement e : elements) {
                String text = "";
                if (e instanceof net.sf.jasperreports.engine.JRPrintText t) {
                    text = " text=" + t.getValue();
                }
                System.out.println("  e=" + e.getClass().getSimpleName()
                    + " y=" + e.getY() + " h=" + e.getHeight() + text);
            }
        }

        byte[] pdf = compiler.export(print, ReportExportFormat.PDF);
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text)
            .contains("Тестовый отчёт")
            .contains("SPEC-1").contains("SPEC-2")
            .contains("Количество").contains("Всего");

        byte[] csv = compiler.export(print, ReportExportFormat.CSV);
        String csvText = new String(csv, StandardCharsets.UTF_8);
        assertThat(csvText).contains("SPEC-1").contains("SPEC-2");

        byte[] xlsx = compiler.export(print, ReportExportFormat.XLSX);
        assertThat(xlsx).isNotEmpty();
        byte[] docx = compiler.export(print, ReportExportFormat.DOCX);
        assertThat(docx).isNotEmpty();
    }

@Test
    void dr7ReferenceBuildWithMapDataSource() throws Exception {
        var column1 = net.sf.dynamicreports.report.builder.DynamicReports.col.column("Column1", "field1", String.class);
        var column3 = net.sf.dynamicreports.report.builder.DynamicReports.col.column("Column3", "field3", Integer.class);
        var group1 = net.sf.dynamicreports.report.builder.DynamicReports.grp.group(column1);
        var subtotal = net.sf.dynamicreports.report.builder.DynamicReports.sbt.sum(
            net.sf.dynamicreports.report.builder.DynamicReports.col.column("Column3", "field3", Integer.class));
        var builder = net.sf.dynamicreports.report.builder.DynamicReports.report()
            .setLocale(new java.util.Locale("ru", "RU"))
            .columns(column1, column3)
            .groupBy(group1)
            .subtotalsAtGroupFooter(group1, subtotal)
            .subtotalsAtSummary(net.sf.dynamicreports.report.builder.DynamicReports.sbt.sum(column3))
            .setDataSource(makeDataSource());
        var print = builder.toJasperPrint();
        for (var page : print.getPages()) {
            System.out.println("=== REF PAGE: " + page.getElements().size() + " elements");
            for (net.sf.jasperreports.engine.JRPrintElement e : page.getElements()) {
                String text = "";
                if (e instanceof net.sf.jasperreports.engine.JRPrintText t) {
                    text = " text=[" + t.getValue() + "]";
                }
                System.out.println("  e=" + e.getClass().getSimpleName()
                    + " y=" + e.getY() + " h=" + e.getHeight() + text);
            }
        }
    }

    @Test
    void dr7ExactForkTestReproduction() throws Exception {
        var col1 = net.sf.dynamicreports.report.builder.DynamicReports.col.column("Column1", "field1", String.class);
        var col2 = net.sf.dynamicreports.report.builder.DynamicReports.col.column("Column2", "field2", String.class);
        var col3 = net.sf.dynamicreports.report.builder.DynamicReports.col.column("Column3", "field3", Integer.class);
        var g1 = net.sf.dynamicreports.report.builder.DynamicReports.grp.group(col1);
        var g2 = net.sf.dynamicreports.report.builder.DynamicReports.grp.group(col2);
        var subtotal2 = net.sf.dynamicreports.report.builder.DynamicReports.sbt.sum(col3);
        var builder = net.sf.dynamicreports.report.builder.DynamicReports.report()
            .setLocale(java.util.Locale.ENGLISH)
            .columns(col1, col3)
            .groupBy(g1)
            .subtotalsAtGroupFooter(g1, subtotal2)
            .subtotalsAtSummary(net.sf.dynamicreports.report.builder.DynamicReports.sbt.sum(col3));
        net.sf.dynamicreports.report.datasource.DRDataSource ds =
            new net.sf.dynamicreports.report.datasource.DRDataSource("field1", "field2", "field3");
        int n = 1;
        for (int i = 0; i < 3; i++) { ds.add("group1", "group1_1", n++); }
        for (int i = 0; i < 3; i++) { ds.add("group1", "group1_2", n++); }
        for (int i = 0; i < 3; i++) { ds.add("group2", "group2_1", n++); }
        for (int i = 0; i < 3; i++) { ds.add("group2", "group2_2", n++); }
        builder.setDataSource(ds);
        var print = builder.toJasperPrint();
        for (var page : print.getPages()) {
            System.out.println("=== EXACT PAGE: " + page.getElements().size() + " elements");
            for (net.sf.jasperreports.engine.JRPrintElement e : page.getElements()) {
                String text = "";
                if (e instanceof net.sf.jasperreports.engine.JRPrintText t) {
                    text = " text=[" + t.getValue() + "]";
                }
                System.out.println("  e=" + e.getClass().getSimpleName()
                    + " y=" + e.getY() + " h=" + e.getHeight() + text);
            }
        }
    }

    private static net.sf.jasperreports.engine.JRDataSource makeDataSource() {
        net.sf.dynamicreports.report.datasource.DRDataSource ds =
            new net.sf.dynamicreports.report.datasource.DRDataSource("field1", "field3");
        for (int i = 1; i <= 12; i++) {
            ds.add("g1", i);
        }
        return ds;
    }

    private static ReportBand band(ReportBandKind kind, ReportBand parent, String groupField) {
        ReportBand band = new ReportBand();
        band.setKind(kind);
        band.setParent(parent);
        band.setGroupField(groupField);
        return band;
    }

    private static ReportField field(String queryField, String caption, Integer width) {
        ReportField field = new ReportField();
        field.setQueryField(queryField);
        field.setCaption(caption);
        field.setWidth(width);
        field.setVisible(true);
        return field;
    }
}
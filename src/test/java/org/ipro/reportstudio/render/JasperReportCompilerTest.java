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
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportFieldKind;
import org.ipro.reportstudio.dom.ReportPageOrientation;
import org.ipro.reportstudio.dom.ReportPageSize;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            .containsOnlyOnce("Код спецификации")
            .doesNotContain("Количество").doesNotContain("Всего");

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

    @Test
    void headerAndFooterTextBlocksAreRendered() throws Exception {
        QueryField journal = new QueryField("journalCode", "", String.class, "Журнал", true, true, false);
        QueryField code = new QueryField("codeSpec", "", String.class, "Код спецификации", true, true, true);
        ReportRow row1 = new ReportRow(new QueryField[]{journal, code}, new Object[]{"A", "SPEC-1"});
        ReportRow row2 = new ReportRow(new QueryField[]{journal, code}, new Object[]{"A", "SPEC-2"});
        ReportDataset dataset = new ReportDataset(new QueryField[]{journal, code}, new ReportRow[]{row1, row2});

        ReportTemplate template = new ReportTemplate();
        template.setName("Тестовый отчёт");

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("journalCode", "Журнал", null));
        detail.addField(field("codeSpec", "Код спецификации", null));
        template.addBand(detail);

        ReportBand group = band(ReportBandKind.GROUP_HEADER, null, "journalCode");
        template.addBand(group);

        ReportBand groupFooter = band(ReportBandKind.GROUP_FOOTER, group, null);
        groupFooter.addField(textField("Итог по журналу"));
        template.addBand(groupFooter);

        ReportBand header = band(ReportBandKind.REPORT_HEADER, null, null);
        header.addField(textField("Выписка за период"));
        template.addBand(header);

        ReportBand reportFooter = band(ReportBandKind.REPORT_FOOTER, null, null);
        reportFooter.addField(textField("Подпись исполнителя"));
        template.addBand(reportFooter);

        byte[] pdf = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(template, dataset), ReportExportFormat.PDF);
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text)
            .contains("Выписка за период")
            .contains("Итог по журналу")
            .contains("Подпись исполнителя")
            .contains("Тестовый отчёт");
    }

    @Test
    void subtotalDoesNotRepeatColumnHeader() throws Exception {
        QueryField amount = new QueryField("amount", "", java.math.BigDecimal.class, "Сумма",
                true, true, true);
        ReportRow row = new ReportRow(new QueryField[]{amount}, new Object[]{new java.math.BigDecimal("10.00")});
        ReportDataset dataset = new ReportDataset(new QueryField[]{amount}, new ReportRow[]{row});

        ReportTemplate template = new ReportTemplate();
        template.setName("Тест подытогов");

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("amount", "Сумма", null));
        template.addBand(detail);

        ReportBand reportFooter = band(ReportBandKind.REPORT_FOOTER, null, null);
        ReportField total = field("amount", "Сумма", null);
        total.setAggregation(ReportFieldAggregation.SUM);
        reportFooter.addField(total);
        template.addBand(reportFooter);

        byte[] pdf = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(template, dataset), ReportExportFormat.PDF);
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text).containsOnlyOnce("Сумма");
    }

    @Test
    void aggregateOnColumnNotDeclaredInDetailFailsCompilation() {
        QueryField journal = new QueryField("journalCode", "", String.class, "Журнал", true, true, false);
        ReportRow row = new ReportRow(new QueryField[]{journal}, new Object[]{"A"});
        ReportDataset dataset = new ReportDataset(new QueryField[]{journal}, new ReportRow[]{row});

        ReportTemplate template = new ReportTemplate();
        template.setName("Битый отчёт");

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("journalCode", "Журнал", null));
        template.addBand(detail);

        ReportBand reportFooter = band(ReportBandKind.REPORT_FOOTER, null, null);
        ReportField ghost = field("ghost", "Фантом", null);
        ghost.setAggregation(ReportFieldAggregation.SUM);
        reportFooter.addField(ghost);
        template.addBand(reportFooter);

        assertThatThrownBy(() -> new JasperReportCompiler().compile(template, dataset))
            .isInstanceOf(ReportRenderException.class)
            .hasMessageContaining("не объявлена в DETAIL");
    }

    @Test
    void appearanceSettingsAreHonoured() throws Exception {
        QueryField code = new QueryField("codeSpec", "", String.class, "Код спецификации", true, true, true);
        ReportRow row1 = new ReportRow(new QueryField[]{code}, new Object[]{"SPEC-1"});
        ReportDataset dataset = new ReportDataset(new QueryField[]{code}, new ReportRow[]{row1});

        ReportTemplate template = new ReportTemplate();
        template.setName("Оформленный отчёт");
        template.setPageSize(ReportPageSize.A3);
        template.setPageOrientation(ReportPageOrientation.LANDSCAPE);
        template.setGridEnabled(false);
        template.setStripeRows(true);
        template.setBaseFontSize(12);

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("codeSpec", "Код спецификации", null));
        template.addBand(detail);

        byte[] pdf = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(template, dataset), ReportExportFormat.PDF);
        assertThat(pdf.length).isGreaterThan(1_000);
    }

    @Test
    void legacyTemplateWithNullKindsAndUnparentedGroupFooterStillRenders() throws Exception {
        QueryField journal = new QueryField("journalCode", "", String.class, "Журнал", true, true, false);
        QueryField code = new QueryField("codeSpec", "", String.class, "Код спецификации", true, true, true);
        ReportRow row = new ReportRow(new QueryField[]{journal, code}, new Object[]{"A", "SPEC-1"});
        ReportDataset dataset = new ReportDataset(new QueryField[]{journal, code}, new ReportRow[]{row});

        ReportTemplate template = new ReportTemplate();
        template.setName("Легаси отчёт");

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("journalCode", "Журнал", null));
        detail.addField(field("codeSpec", "Код спецификации", null));
        template.addBand(detail);

        ReportBand header = band(ReportBandKind.REPORT_HEADER, null, null);
        header.addField(field("journalCode", "Старый заголовок-колонка", null));
        template.addBand(header);

        ReportBand groupHeader = band(ReportBandKind.GROUP_HEADER, null, "journalCode");
        template.addBand(groupHeader);

        ReportBand groupFooter = band(ReportBandKind.GROUP_FOOTER, null, "journalCode");
        ReportField legacy = field("journalCode", "Итого:", null);
        legacy.setAggregation(ReportFieldAggregation.NONE);
        groupFooter.addField(legacy);
        template.addBand(groupFooter);

        byte[] pdf = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(template, dataset), ReportExportFormat.PDF);
        assertThat(pdf.length).isGreaterThan(1_000);
    }

    @Test
    void perFieldBorderOverridesTemplateGridSetting() throws Exception {
        QueryField code = new QueryField("codeSpec", "", String.class, "Код спецификации", true, true, true);
        ReportRow row = new ReportRow(new QueryField[]{code}, new Object[]{"SPEC-1"});
        ReportDataset dataset = new ReportDataset(new QueryField[]{code}, new ReportRow[]{row});

        ReportTemplate template = new ReportTemplate();
        template.setName("Границы по полям");
        template.setGridEnabled(false);

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        ReportField bordered = field("codeSpec", "Код спецификации", null);
        bordered.setBorder(true);
        detail.addField(bordered);
        template.addBand(detail);

        byte[] pdf = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(template, dataset), ReportExportFormat.PDF);
        assertThat(pdf.length).isGreaterThan(1_000);

        ReportTemplate without = new ReportTemplate();
        without.setName("Без границ");
        without.setGridEnabled(true);
        ReportBand detail2 = band(ReportBandKind.DETAIL, null, null);
        ReportField plain = field("codeSpec", "Код спецификации", null);
        plain.setBorder(false);
        detail2.addField(plain);
        without.addBand(detail2);

        byte[] pdf2 = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(without, dataset), ReportExportFormat.PDF);
        assertThat(pdf2.length).isGreaterThan(1_000);
    }

    @Test
    void borderOnTextBlockCompiles() throws Exception {
        QueryField code = new QueryField("codeSpec", "", String.class, "Код спецификации", true, true, true);
        ReportRow row = new ReportRow(new QueryField[]{code}, new Object[]{"SPEC-1"});
        ReportDataset dataset = new ReportDataset(new QueryField[]{code}, new ReportRow[]{row});

        ReportTemplate template = new ReportTemplate();
        template.setName("Текст с границей");

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("codeSpec", "Код спецификации", null));
        template.addBand(detail);

        ReportBand header = band(ReportBandKind.REPORT_HEADER, null, null);
        ReportField text = textField("Выписка за период");
        text.setBorder(true);
        header.addField(text);
        template.addBand(header);

        byte[] pdf = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(template, dataset), ReportExportFormat.PDF);
        assertThat(pdf.length).isGreaterThan(1_000);
    }

    @Test
    void rowNumberColumnRendersCounter() throws Exception {
        QueryField code = new QueryField("codeSpec", "", String.class, "Код спецификации", true, true, true);
        ReportRow row1 = new ReportRow(new QueryField[]{code}, new Object[]{"SPEC-1"});
        ReportRow row2 = new ReportRow(new QueryField[]{code}, new Object[]{"SPEC-2"});
        ReportDataset dataset = new ReportDataset(new QueryField[]{code}, new ReportRow[]{row1, row2});

        ReportTemplate template = new ReportTemplate();
        template.setName("Счётчик строк");

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(rowNumber("№"));
        detail.addField(field("codeSpec", "Код спецификации", null));
        template.addBand(detail);

        byte[] pdf = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(template, dataset), ReportExportFormat.PDF);
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text).contains("1").contains("2").contains("SPEC-1").contains("SPEC-2");
    }

    @Test
    void noDataBandRenderedWhenDatasetEmpty() throws Exception {
        QueryField code = new QueryField("codeSpec", "", String.class, "Код спецификации", true, true, true);
        ReportDataset dataset = new ReportDataset(new QueryField[]{code}, new ReportRow[0]);

        ReportTemplate template = new ReportTemplate();
        template.setName("Пустой отчёт");

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("codeSpec", "Код спецификации", null));
        template.addBand(detail);

        ReportBand noData = band(ReportBandKind.NO_DATA, null, null);
        noData.addField(textField("Нет данных за период"));
        template.addBand(noData);

        byte[] pdf = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(template, dataset), ReportExportFormat.PDF);
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text).contains("Нет данных за период");
    }

    @Test
    void startNewPageGroupHeaderBreaksFirstPage() throws Exception {
        QueryField journal = new QueryField("journalCode", "", String.class, "Журнал", true, true, false);
        QueryField code = new QueryField("codeSpec", "", String.class, "Код спецификации", true, true, true);
        ReportRow row1 = new ReportRow(new QueryField[]{journal, code}, new Object[]{"A", "SPEC-1"});
        ReportRow row2 = new ReportRow(new QueryField[]{journal, code}, new Object[]{"B", "SPEC-2"});
        ReportDataset dataset = new ReportDataset(new QueryField[]{journal, code}, new ReportRow[]{row1, row2});

        ReportTemplate template = new ReportTemplate();
        template.setName("Группы с новой страницы");

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("journalCode", "Журнал", null));
        detail.addField(field("codeSpec", "Код спецификации", null));
        template.addBand(detail);

        ReportBand group = band(ReportBandKind.GROUP_HEADER, null, "journalCode");
        group.setStartNewPage(true);
        template.addBand(group);

        ReportBand groupFooter = band(ReportBandKind.GROUP_FOOTER, group, null);
        template.addBand(groupFooter);

        JasperPrint print = new JasperReportCompiler().compile(template, dataset);
        assertThat(print.getPages().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void expressionColumnRendersTemplateWithRowValues() throws Exception {
        QueryField item = new QueryField("item", "", String.class, "Товар", true, true, true);
        QueryField qty = new QueryField("qty", "", Integer.class, "Кол-во", true, true, true);
        ReportRow row1 = new ReportRow(new QueryField[]{item, qty}, new Object[]{"Деталь", 3});
        ReportRow row2 = new ReportRow(new QueryField[]{item, qty}, new Object[]{"Узел", 7});
        ReportDataset dataset = new ReportDataset(new QueryField[]{item, qty}, new ReportRow[]{row1, row2});

        ReportTemplate template = new ReportTemplate();
        template.setName("Выражения");

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("item", "Товар", null));
        ReportField expression = new ReportField();
        expression.setKind(ReportFieldKind.EXPRESSION);
        expression.setText("Продано: {item}, {qty} шт.");
        expression.setPosition(1);
        detail.addField(expression);
        template.addBand(detail);

        byte[] pdf = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(template, dataset), ReportExportFormat.PDF);
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text)
            .contains("Продано: Деталь, 3 шт.")
            .contains("Продано: Узел, 7 шт.");
    }

    @Test
    void formulaColumnRendersComputedArithmetic() throws Exception {
        QueryField qty = new QueryField("qty", "", Integer.class, "Кол-во", true, true, true);
        QueryField price = new QueryField("price", "", java.math.BigDecimal.class, "Цена", true, true, true);
        ReportRow row1 = new ReportRow(new QueryField[]{qty, price},
            new Object[]{4, new java.math.BigDecimal("5")});
        ReportRow row2 = new ReportRow(new QueryField[]{qty, price},
            new Object[]{10, new java.math.BigDecimal("2")});
        ReportDataset dataset = new ReportDataset(new QueryField[]{qty, price},
            new ReportRow[]{row1, row2});

        ReportTemplate template = new ReportTemplate();
        template.setName("Формулы");

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("qty", "Кол-во", null));
        detail.addField(field("price", "Цена", null));
        ReportField formula = new ReportField();
        formula.setKind(ReportFieldKind.FORMULA);
        formula.setCaption("Сумма");
        formula.setText("({qty} * {price}) + 6");
        formula.setAlignment(ReportFieldAlignment.RIGHT);
        formula.setPosition(2);
        detail.addField(formula);
        template.addBand(detail);

        byte[] pdf = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(template, dataset), ReportExportFormat.PDF);
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        // 4*5+6=26, 10*2+6=26
        assertThat(text).contains("26");
    }

    @Test
    void formulaColumnHonoursDecimalFormat() throws Exception {
        QueryField qty = new QueryField("qty", "", Integer.class, "Кол-во", true, true, true);
        QueryField price = new QueryField("price", "", java.math.BigDecimal.class, "Цена", true, true, true);
        ReportRow row = new ReportRow(new QueryField[]{qty, price},
            new Object[]{2, new java.math.BigDecimal("3.5")});
        ReportDataset dataset = new ReportDataset(new QueryField[]{qty, price}, new ReportRow[]{row});

        ReportTemplate template = new ReportTemplate();
        template.setName("Формат формулы");

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("qty", "Кол-во", null));
        detail.addField(field("price", "Цена", null));
        ReportField formula = new ReportField();
        formula.setKind(ReportFieldKind.FORMULA);
        formula.setCaption("Сумма");
        formula.setText("{qty} * {price}");
        formula.setFormat("#,##0.00");
        formula.setPosition(2);
        detail.addField(formula);
        template.addBand(detail);

        byte[] pdf = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(template, dataset), ReportExportFormat.PDF);
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text).contains("7,00");
    }

    @Test
    void formulaWithNullReferencedValueRendersEmpty() throws Exception {
        QueryField qty = new QueryField("qty", "", Integer.class, "Кол-во", true, true, false);
        ReportRow row = new ReportRow(new QueryField[]{qty}, new Object[]{null});
        ReportDataset dataset = new ReportDataset(new QueryField[]{qty}, new ReportRow[]{row});

        ReportTemplate template = new ReportTemplate();
        template.setName("Пустая формула");

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("qty", "Кол-во", null));
        ReportField formula = new ReportField();
        formula.setKind(ReportFieldKind.FORMULA);
        formula.setCaption("Удвоение");
        formula.setText("{qty} * 2");
        formula.setPosition(1);
        detail.addField(formula);
        template.addBand(detail);

        byte[] pdf = new JasperReportCompiler().export(
            new JasperReportCompiler().compile(template, dataset), ReportExportFormat.PDF);
        assertThat(pdf.length).isGreaterThan(1_000);
    }

    private static ReportField rowNumber(String caption) {
        ReportField field = new ReportField();
        field.setKind(ReportFieldKind.ROW_NUMBER);
        field.setCaption(caption);
        field.setVisible(true);
        return field;
    }

    private static ReportField textField(String text) {
        ReportField field = new ReportField();
        field.setKind(ReportFieldKind.TEXT);
        field.setText(text);
        field.setVisible(true);
        return field;
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
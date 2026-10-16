package org.ip.unidr.render;

import net.sf.jasperreports.engine.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.source.*;
import org.ipro.reportstudio.data.*;
import org.ipro.reportstudio.render.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.awt.Color;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;

class UniDrReportCompilerTest {
    private final UniDrSourceCatalog catalog = UniDrSourceCatalog.pilot();
    private final UniDrReportCompiler compiler = new UniDrReportCompiler(catalog, new UniDrDefinitionValidator(catalog));
    private final ReportCompiler exporter = new JasperReportCompiler();
    private final UniDrDefinition base = UniDrDefinitions.receivingItemsSummary();
    private static final QueryField[] FIELDS = {QueryField.scalar("rowId", Long.class), QueryField.scalar("date", LocalDate.class),
        QueryField.scalar("documentId", Long.class), QueryField.scalar("documentNumber", String.class),
        QueryField.scalar("receivingWorkshopId", Long.class), QueryField.scalar("receivingWorkshopName", String.class),
        QueryField.scalar("nomenclatureId", Long.class), QueryField.scalar("nomenclatureName", String.class),
        QueryField.scalar("quantity", BigDecimal.class)};
    private record Text(int x, int y, String text, JRPrintText element) {}
    private record Row(String label, List<String> cells) {}
    private UniDrDefinition definition(int depth, Mode mode, Aggregation aggregation, boolean grand, Appearance appearance) {
        return new UniDrDefinition(1,base.sourceId(),1,"Отчёт: позиции накладных",List.of(new Column("document","Документ"),new Column("date",null),new Column("quantity","Объём")),
            List.of(new Measure("quantity",aggregation,"Показатель"),new Measure("rowCount",Aggregation.COUNT_ROWS,"Записей")),
            List.of("receivingWorkshop","nomenclature","date").subList(0,depth),List.of(),base.parameters(),base.sorts(),mode,grand,appearance);
    }
    private ReportRow row(long id, Long workshop, String workshopName, long item, String itemName, String quantity) {
        return new ReportRow(FIELDS,new Object[]{id,LocalDate.of(2026,10,item==11?1:2),id,"Накладная "+id,workshop,workshopName,item,itemName,quantity==null?null:new BigDecimal(quantity)});
    }
    private ReportDataset fixture() { return new ReportDataset(FIELDS,new ReportRow[]{row(1,1L,"Цех A",11,"Деталь X","2"),row(2,1L,"Цех A",11,"Деталь X","4"),
        row(3,1L,"Цех A",12,"Деталь Y","9"),row(4,2L,"Цех B",11,"Деталь X","5"),row(5,2L,"Цех B",11,"Деталь X","10"),row(6,2L,"Цех B",12,"Деталь Y","6")}); }

    @Test void completedQueryReadRendersWithNativeFilterOnAnUnprojectedField() throws Exception {
        var source=org.ip.unidr.query.UniDrQuerySourceService.metadata(List.of(QueryField.scalar("code",String.class),QueryField.scalar("name",String.class)));
        var tree=new org.ipro.filtergrid.filter.FilterConditionNode(new org.ipro.filtergrid.filter.FilterCondition("name",org.ipro.filtergrid.filter.FilterOperator.CONTAINS,"X",null,org.ipro.filtergrid.filter.FilterDataType.TEXT));
        var query=new UniDrQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.MANUAL,"select n.code as code,n.name as name from Nomenclature n",null,List.of());
        var recipe=new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,"query",1,"Отобранные данные",List.of(new Column("code",null)),List.of(),List.of(),List.of(),List.of(),List.of(),Mode.DETAILS,true).withQuery(query).withFilterTree(tree);
        var fields=new QueryField[]{QueryField.scalar("code",String.class)};
        var data=new ReportDataset(fields,new ReportRow[]{new ReportRow(fields,new Object[]{"X"})});
        var print=compiler.compile(recipe,data,source);
        try(var pdf=Loader.loadPDF(exporter.export(print,ReportExportFormat.PDF))){assertThat(new PDFTextStripper().getText(pdf)).contains("Отобранные данные","X");}
        assertThat(recipe.filterTree()).isSameAs(tree);
    }

    @Test void computedNumbersAndTextReachGroupedTotalsPdfAndTypedXlsx() throws Exception {
        var computed=List.of(new ComputedColumn("calc_amount","Стоимость",Computation.FORMULA,"{quantity} * 10"),
            new ComputedColumn("calc_text","Описание",Computation.TEXT,"{nomenclature} / {document}: {quantity}"));
        var recipe=new UniDrDefinition(1,base.sourceId(),1,"Вычисления",List.of(new Column("calc_text",null),new Column("calc_amount",null)),
            List.of(new Measure("calc_amount",Aggregation.SUM,null)),List.of("receivingWorkshop"),List.of(),base.parameters(),List.of(),Mode.DETAILS,true)
            .withComputedColumns(computed).withRowNumbers(true);
        var print=compiler.compile(recipe,fixture());
        assertThat(texts(print)).extracting(Text::text).contains("Деталь X / Накладная 1: 2","360,00","№");
        assertThat(rows(print)).anyMatch(line->line.label().equals("Итого: Цех A") && line.cells().contains("150,00"));
        try(var pdf=Loader.loadPDF(exporter.export(print,ReportExportFormat.PDF))) {assertThat(new PDFTextStripper().getText(pdf)).contains("360,00","Накладная 1");}
        assertThat(zipText(exporter.export(print,ReportExportFormat.XLSX),"xl/worksheets/sheet1.xml")).containsPattern("<c[^>]*t=\"n\"[^>]*><v>360(?:\\.0+)?</v>");
        var summary=new UniDrDefinition(1,base.sourceId(),1,"Вычисления",recipe.columns(),recipe.measures(),recipe.groups(),List.of(),base.parameters(),List.of(),Mode.SUMMARY,true).withComputedColumns(computed).withRowNumbers(true);
        var summaryPrint=compiler.compile(summary,fixture());
        assertThat(texts(summaryPrint)).extracting(Text::text).contains("360,00").doesNotContain("№","Описание");
    }
    @Test void rowNumbersAreContinuousAcrossGroupsAndPages() {
        var many=java.util.stream.LongStream.rangeClosed(1,95).mapToObj(id->row(id,id<50?1L:2L,"Цех "+(id<50?"A":"B"),11,"X","1")).toArray(ReportRow[]::new);
        var print=compiler.compile(definition(1,Mode.DETAILS,Aggregation.SUM,true,Appearance.defaults()).withRowNumbers(true),new ReportDataset(FIELDS,many));
        assertThat(print.getPages()).hasSizeGreaterThan(1);
        int numberX=texts(print).stream().filter(text->text.text().equals("№")).findFirst().orElseThrow().x();
        assertThat(texts(print).stream().filter(text->text.x()==numberX && text.text().matches("[0-9]+")).map(Text::text).toList())
            .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,95).mapToObj(Integer::toString).toList());
    }
    @Test void computedNullAndDivisionByZeroStayEmptyAndNeverMutateSource() {
        var recipe=base.withComputedColumns(List.of(new ComputedColumn("calc_a","A",Computation.FORMULA,"{quantity} / 0"),new ComputedColumn("calc_text","Текст",Computation.TEXT,"[{quantity}] {date}")));
        var original=new ReportDataset(FIELDS,new ReportRow[]{row(1,1L,"A",11,"X",null)});
        var data=UniDrComputedColumns.evaluate(recipe,catalog.find(base.sourceId()).orElseThrow(),original);
        assertThat(data.rows()[0].value("calc_a")).isNull();assertThat(data.rows()[0].value("calc_text")).isEqualTo("[] 01.10.2026");
        assertThat(original.columnCount()).isEqualTo(FIELDS.length);
    }
    @ParameterizedTest @CsvSource({"true,false","false,true"})
    void perColumnBorderOverridesReportForTitleDetailAndTotals(boolean global,boolean override) {
        var recipe=definition(0,Mode.DETAILS,Aggregation.SUM,true,new Appearance(global,global,false)).withFieldStyles(Map.of("quantity",new FieldStyle(null,null,null,override)));
        var print=compiler.compile(recipe,fixture());
        for(String value:List.of("Объём","2,00","36,00")) {
            var cell=texts(print).stream().filter(text->text.text().equals(value)).findFirst().orElseThrow().element();
            assertThat(cell.getLineBox().getTopPen().getLineWidth()>0).isEqualTo(override);
            assertThat(cell.getLineBox().getLeftPen().getLineWidth()>0).isEqualTo(override);
            assertThat(cell.getLineBox().getBottomPen().getLineWidth()>0).isEqualTo(override);
            assertThat(cell.getLineBox().getRightPen().getLineWidth()>0).isEqualTo(override);
        }
    }
    @ParameterizedTest @CsvSource({"0,DETAILS","1,DETAILS","2,DETAILS","3,DETAILS","0,SUMMARY","1,SUMMARY","2,SUMMARY","3,SUMMARY"})
    void sumsCountsAndVisibleColumnsAtEveryDepth(int depth, Mode mode) {
        JasperPrint print=compiler.compile(definition(depth,mode,Aggregation.SUM,true,Appearance.defaults()),fixture());
        List<Row> lines=rows(print);
        assertThat(lines).filteredOn(line->line.label().equals("Всего")).singleElement()
            .satisfies(line->assertThat(line.cells()).contains("36,00","6"));
        assertThat(texts(print)).extracting(Text::text).filteredOn(text->text.startsWith("Накладная ")).hasSize(mode==Mode.DETAILS?6:0);
        if(depth>0) assertThat(lines).anyMatch(line->line.label().equals((mode==Mode.DETAILS?"Итого: ":"Цех-приёмщик: ")+"Цех A") && line.cells().contains("15,00") && line.cells().contains("3"));
        if(depth>1) assertThat(lines).filteredOn(line->line.label().equals((mode==Mode.DETAILS?"Итого: ":"Номенклатура: ")+"Деталь X"))
            .extracting(Row::cells).anySatisfy(cells->assertThat(cells).contains("6,00","2")).anySatisfy(cells->assertThat(cells).contains("15,00","2"));
        assertThat(texts(print)).extracting(Text::text).doesNotContain("rowId","rowCount","receivingWorkshopId","__reportstudio_row_marker");
        if(mode==Mode.SUMMARY) assertThat(texts(print)).extracting(Text::text).doesNotContain("Документ","Объём");
        else assertThat(texts(print)).extracting(Text::text).contains("Документ","Объём");
    }
    @ParameterizedTest @CsvSource({"AVG,6.00,5.00,7.00","MIN,2.00,2.00,5.00","MAX,10.00,9.00,10.00"})
    void aggregatesUseOriginalRowsRatherThanChildTotals(Aggregation aggregation,String overall,String first,String second) {
        var print=compiler.compile(definition(2,Mode.SUMMARY,aggregation,true,Appearance.defaults()),fixture());
        var lines=rows(print);
        assertThat(lines).filteredOn(line->line.label().equals("Всего")).singleElement().satisfies(line->assertThat(line.cells()).contains(overall.replace('.',','),"6"));
        assertThat(lines).anyMatch(line->line.label().equals("Цех-приёмщик: Цех A") && line.cells().contains(first.replace('.',',')));
        assertThat(lines).anyMatch(line->line.label().equals("Цех-приёмщик: Цех B") && line.cells().contains(second.replace('.',',')));
    }
    @Test void countRowsIncludesNullAndNumericAggregationsIgnoreNull() {
        var data=new ReportDataset(FIELDS,new ReportRow[]{row(1,null,null,11,"Деталь",null),row(2,null,null,11,"Деталь","0"),row(3,null,null,11,"Деталь","6")});
        var print=compiler.compile(definition(2,Mode.SUMMARY,Aggregation.AVG,true,Appearance.defaults()),data);
        assertThat(rows(print)).anyMatch(line->line.label().equals("Цех-приёмщик: Не заполнено") && line.cells().contains("3,00") && line.cells().contains("3"));
        var allNull=compiler.compile(definition(1,Mode.SUMMARY,Aggregation.SUM,true,Appearance.defaults()),new ReportDataset(FIELDS,new ReportRow[]{data.rows()[0]}));
        assertThat(rows(allNull)).anyMatch(line->line.label().equals("Всего") && line.cells().contains("1"));
    }
    @Test void sameCaptionWithDifferentIdsProducesSeparateGroupsAndHiddenFieldsStayHidden() {
        var data=new ReportDataset(FIELDS,new ReportRow[]{row(1,919191L,"Одинаковая подпись",11,"X","2"),row(2,929292L,"Одинаковая подпись",11,"X","4")});
        var print=compiler.compile(definition(1,Mode.SUMMARY,Aggregation.SUM,false,Appearance.defaults()),data);
        assertThat(rows(print)).filteredOn(line->line.label().equals("Цех-приёмщик: Одинаковая подпись")).hasSize(2);
        assertThat(texts(print)).extracting(Text::text).doesNotContain("919191","929292","Всего");
    }
    @Test void unsortedGroupsFailRatherThanProduceSplitTotals() {
        var original=fixture().rows(); var data=new ReportDataset(FIELDS,new ReportRow[]{original[0],original[3],original[1]});
        assertThatThrownBy(()->compiler.compile(definition(1,Mode.SUMMARY,Aggregation.SUM,true,Appearance.defaults()),data))
            .isInstanceOf(ReportRenderException.class).hasMessageContaining("порядок групп");
    }
    @Test void emptySelectionRendersReadableDocumentWithoutFalseTotals() throws Exception {
        var print=compiler.compile(definition(2,Mode.SUMMARY,Aggregation.SUM,true,Appearance.defaults()),new ReportDataset(FIELDS,new ReportRow[0]));
        assertThat(texts(print)).extracting(Text::text).contains("Отчёт: позиции накладных","По выбранным условиям данных нет").doesNotContain("Всего");
        try(var pdf=Loader.loadPDF(exporter.export(print,ReportExportFormat.PDF))) { assertThat(new PDFTextStripper().getText(pdf)).contains("данных нет"); }
    }
    @ParameterizedTest @CsvSource({"true,true,true","true,false,false","false,true,false","false,false,false"})
    void bordersCanBeSwitchedIndependentlyInDetailsAndSummary(boolean horizontal,boolean vertical,boolean stripes) {
        for(Mode mode:Mode.values()) {
            var print=compiler.compile(definition(1,mode,Aggregation.SUM,true,new Appearance(horizontal,vertical,stripes)),fixture());
            JRPrintText total=texts(print).stream().filter(text->text.text().equals("Всего")).findFirst().orElseThrow().element();
            assertThat(total.getLineBox().getTopPen().getLineWidth()>0).isEqualTo(horizontal);
            assertThat(total.getLineBox().getLeftPen().getLineWidth()>0).isEqualTo(vertical);
            assertThat(total.getLineBox().getBottomPen().getLineWidth()>0).isEqualTo(horizontal);
            assertThat(total.getLineBox().getRightPen().getLineWidth()>0).isEqualTo(vertical);
        }
    }
    @Test void summaryStripeFollowsVisibleGroupRowsAndCanBeDisabled() {
        for(boolean enabled:List.of(true,false)) {
            var print=compiler.compile(definition(2,Mode.SUMMARY,Aggregation.SUM,true,new Appearance(true,true,enabled)),fixture());
            var groups=texts(print).stream().filter(text->text.text().startsWith("Номенклатура:")).toList();
            assertThat(groups).isNotEmpty();
            assertThat(groups.stream().anyMatch(text->new Color(0xF2,0xF5,0xF8).equals(text.element().getBackcolor()))).isEqualTo(enabled);
        }
    }
    @Test void onePrintProvidesCyrillicPdfAndNumericXlsxAndVisualExamples() throws Exception {
        for(Mode mode:Mode.values()) {
            var print=compiler.compile(definition(2,mode,Aggregation.SUM,true,Appearance.defaults()),fixture());
            byte[] pdf=exporter.export(print,ReportExportFormat.PDF),xlsx=exporter.export(print,ReportExportFormat.XLSX);
            try(var document=Loader.loadPDF(pdf)) { assertThat(new PDFTextStripper().getText(document)).contains("позиции накладных","36,00"); }
            assertThat(new String(pdf,StandardCharsets.ISO_8859_1)).contains("/FontFile2");
            String worksheet=zipText(xlsx,"xl/worksheets/sheet1.xml");
            assertThat(worksheet).containsPattern("<c[^>]*t=\"n\"[^>]*><v>36(?:\\.0+)?</v>").doesNotContain("<v>36,00</v>");
            save(mode.name().toLowerCase(Locale.ROOT),print,pdf,xlsx);
        }
    }
    @Test void pageBreaksPreserveEveryDetailAndExactlyOneGrandTotal() throws Exception {
        ReportRow[] rows=java.util.stream.LongStream.rangeClosed(1,95).mapToObj(id->row(id,1L,"Цех A",11,"Деталь X","1")).toArray(ReportRow[]::new);
        var print=compiler.compile(definition(2,Mode.DETAILS,Aggregation.SUM,true,Appearance.defaults()),new ReportDataset(FIELDS,rows));
        assertThat(print.getPages()).hasSizeGreaterThan(1);
        assertThat(texts(print)).extracting(Text::text).filteredOn(text->text.startsWith("Накладная ")).hasSize(95);
        assertThat(rows(print)).filteredOn(row->row.label().equals("Всего")).singleElement().satisfies(row->assertThat(row.cells()).contains("95,00","95"));
        save("multipage",print,exporter.export(print,ReportExportFormat.PDF),exporter.export(print,ReportExportFormat.XLSX));
    }
    @Test void noGroupsAndNoGrandTotalStillProducesOneSummary() {
        var print=compiler.compile(definition(0,Mode.SUMMARY,Aggregation.SUM,false,Appearance.defaults()),fixture());
        assertThat(texts(print)).extracting(Text::text).contains("Итог").doesNotContain("Группа");
        assertThat(rows(print)).filteredOn(row->row.label().equals("Итог")).singleElement().satisfies(row->assertThat(row.cells()).contains("36,00","6"));
    }
    @Test void detailTableStartsWithSelectedFieldsEvenWhenGrouped() {
        for (int depth : List.of(0, 2)) {
            var print=compiler.compile(definition(depth,Mode.DETAILS,Aggregation.SUM,true,Appearance.defaults()).withRowNumbers(true),fixture());
            var cells=texts(print);
            assertThat(cells).extracting(Text::text).doesNotContain("Группа");
            var number=cells.stream().filter(cell->cell.text().equals("№")).findFirst().orElseThrow();
            assertThat(number.x()).isEqualTo(print.getLeftMargin());
            assertThat(rows(print)).filteredOn(row->row.label().equals("Всего")).singleElement()
                .satisfies(row->assertThat(row.cells()).contains("36,00","6"));
        }
    }
    @Test void numericOnlyTablesKeepTotalsWithoutAnEmptyLabelColumn() throws Exception {
        for (Mode mode : Mode.values()) {
            var recipe=new UniDrDefinition(1,base.sourceId(),1,"Только числа",List.of(new Column("quantity","Объём")),
                List.of(new Measure("quantity",Aggregation.SUM,"Объём")),List.of(),List.of(),base.parameters(),List.of(),mode,true);
            var print=compiler.compile(recipe,fixture());
            var cells=texts(print);
            assertThat(cells).extracting(Text::text).contains("Всего","36,00").doesNotContain("Группа");
            var heading=cells.stream().filter(cell->cell.text().equals("Объём")).findFirst().orElseThrow();
            assertThat(heading.x()).isEqualTo(print.getLeftMargin());
            assertThat(heading.element().getWidth()).isEqualTo(print.getPageWidth()-print.getLeftMargin()-print.getRightMargin());
            var label=cells.stream().filter(cell->cell.text().equals("Всего")).findFirst().orElseThrow();
            var total=cells.stream().filter(cell->cell.text().equals("36,00")).findFirst().orElseThrow();
            assertThat(label.y()).isEqualTo(total.y());
            assertThat(label.x()).isLessThan(total.x());
            assertThat(zipText(exporter.export(print,ReportExportFormat.XLSX),"xl/worksheets/sheet1.xml"))
                .containsPattern("<c[^>]*t=\"n\"[^>]*><v>36(?:\\.0+)?</v>");
            save("no-groups-"+mode.name().toLowerCase(Locale.ROOT),print,exporter.export(print,ReportExportFormat.PDF),exporter.export(print,ReportExportFormat.XLSX));
        }
    }
    @Test void numericOnlyGroupFootersRetainEachPreviousGroupCaption() {
        var recipe=new UniDrDefinition(1,base.sourceId(),1,"Только числа",List.of(new Column("quantity","Объём")),
            List.of(new Measure("quantity",Aggregation.SUM,null)),List.of("receivingWorkshop"),List.of(),base.parameters(),List.of(),Mode.DETAILS,true);
        var print=compiler.compile(recipe,fixture());
        assertThat(texts(print)).extracting(Text::text).contains("Итого: Цех A","Итого: Цех B","15,00","21,00","Всего","36,00").doesNotContain("Группа");
        var cells=texts(print);
        for (var entry : Map.of("Итого: Цех A","15,00","Итого: Цех B","21,00").entrySet()) {
            var caption=cells.stream().filter(cell->cell.text().equals(entry.getKey())).findFirst().orElseThrow();
            var total=cells.stream().filter(cell->cell.text().equals(entry.getValue())).findFirst().orElseThrow();
            assertThat(caption.y()).isEqualTo(total.y());
        }
    }
    @Test void summaryGroupColumnHasCompactWidthEvenWithOnlyOneMeasure() {
        var recipe=new UniDrDefinition(1,base.sourceId(),1,base.title(),List.of(),List.of(new Measure("rowCount",Aggregation.COUNT_ROWS,"Записей")),
            List.of("receivingWorkshop"),List.of(),base.parameters(),List.of(),Mode.SUMMARY,true);
        var print=compiler.compile(recipe,fixture());
        var header=texts(print).stream().filter(cell->cell.text().equals("Группа")).findFirst().orElseThrow();
        assertThat(header.element().getWidth()).isLessThanOrEqualTo(180);
        assertThat(header.element().getWidth()).isLessThan((print.getPageWidth()-print.getLeftMargin()-print.getRightMargin())/2);
        assertThat(rows(print)).anyMatch(row->row.label().equals("Цех-приёмщик: Цех A") && row.cells().contains("3"));
    }
    @Test void totalLabelInsideNumericCellRespectsItsBorderOverride() {
        var recipe=new UniDrDefinition(1,base.sourceId(),1,"Только числа",List.of(new Column("quantity",null)),
            List.of(new Measure("quantity",Aggregation.SUM,null)),List.of(),List.of(),base.parameters(),List.of(),Mode.DETAILS,true)
            .withFieldStyles(Map.of("quantity",new FieldStyle(null,null,null,false)));
        var print=compiler.compile(recipe,fixture());
        for(String value:List.of("Всего","36,00")) {
            var cell=texts(print).stream().filter(text->text.text().equals(value)).findFirst().orElseThrow().element();
            assertThat(cell.getLineBox().getTopPen().getLineWidth()).isZero();
            assertThat(cell.getLineBox().getLeftPen().getLineWidth()).isZero();
        }
    }
    @Test void countOnlyAndDetailsWithoutMeasuresAreValid() {
        var count=new UniDrDefinition(1,base.sourceId(),1,base.title(),List.of(),List.of(new Measure("rowCount",Aggregation.COUNT_ROWS,null)),base.groups(),List.of(),base.parameters(),base.sorts(),Mode.SUMMARY,true);
        assertThat(rows(compiler.compile(count,fixture()))).filteredOn(row->row.label().equals("Всего")).singleElement().satisfies(row->assertThat(row.cells()).contains("6"));
        var details=new UniDrDefinition(1,base.sourceId(),1,base.title(),List.of(new Column("document",null)),List.of(),List.of(),List.of(),base.parameters(),List.of(),Mode.DETAILS,false);
        assertThat(texts(compiler.compile(details,fixture()))).extracting(Text::text).filteredOn(text->text.startsWith("Накладная ")).hasSize(6);
    }
    @Test void renderingCannotRetainCallingTransaction() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(()->compiler.compile(definition(1,Mode.SUMMARY,Aggregation.SUM,true,Appearance.defaults()),fixture()))
                .isInstanceOf(ReportRenderException.class).hasMessageContaining("завершённой транзакции");
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    @Test void fieldFormatWidthAndAlignmentReachDetailsSubtotalsPdfAndNumericXlsx() throws Exception {
        var recipe=definition(0,Mode.DETAILS,Aggregation.SUM,true,Appearance.defaults()).withFieldStyles(Map.of(
            "quantity",new FieldStyle(300,Alignment.CENTER,"#,##0.000"),"date",new FieldStyle(70,Alignment.RIGHT,"yyyy-MM-dd")));
        var print=compiler.compile(recipe,fixture());var cells=texts(print);
        var total=cells.stream().filter(text->text.text().equals("36,000")).findFirst().orElseThrow().element();
        var date=cells.stream().filter(text->text.text().equals("2026-10-01")).findFirst().orElseThrow().element();
        assertThat(total.getHorizontalTextAlign()).isEqualTo(net.sf.jasperreports.engine.type.HorizontalTextAlignEnum.CENTER);
        assertThat(date.getHorizontalTextAlign()).isEqualTo(net.sf.jasperreports.engine.type.HorizontalTextAlignEnum.RIGHT);
        assertThat(total.getWidth()).isGreaterThan(date.getWidth());
        try(var document=Loader.loadPDF(exporter.export(print,ReportExportFormat.PDF))) {assertThat(new PDFTextStripper().getText(document)).contains("36,000","2026-10-01");}
        var xlsx=exporter.export(print,ReportExportFormat.XLSX);
        assertThat(zipText(xlsx,"xl/worksheets/sheet1.xml")).containsPattern("<c[^>]*t=\"n\"[^>]*><v>36(?:\\.0+)?</v>");
        assertThat(zipText(xlsx,"xl/styles.xml")).contains("0.000");
        var summary=compiler.compile(definition(1,Mode.SUMMARY,Aggregation.SUM,true,Appearance.defaults()).withFieldStyles(recipe.fieldStyles()),fixture());
        assertThat(texts(summary)).filteredOn(text->text.text().equals("36,000")).singleElement().satisfies(text->assertThat(text.element().getHorizontalTextAlign()).isEqualTo(net.sf.jasperreports.engine.type.HorizontalTextAlignEnum.CENTER));
    }
    @Test void longGroupCaptionWrapsAndIsPrintedCompletely() throws Exception {
        String caption="Цех обработки длинных изделий с несколькими производственными участками и дополнительным описанием КонецПодписи";
        var data=new ReportDataset(FIELDS,new ReportRow[]{row(1,1L,caption,11,"X","2")});
        for (Mode mode : Mode.values()) {
            var print=compiler.compile(definition(1,mode,Aggregation.SUM,true,Appearance.defaults()),data);
            var labels=texts(print).stream().filter(text->text.text().contains("КонецПодписи")).toList();
            assertThat(labels).hasSize(mode==Mode.DETAILS?2:1)
                .allSatisfy(label->assertThat(label.element().getHeight()).isGreaterThan(30));
            byte[] pdf=exporter.export(print,ReportExportFormat.PDF);
            try(var document=Loader.loadPDF(pdf)) {
                assertThat(new PDFTextStripper().getText(document)).contains("КонецПодписи");
                ImageIO.write(new PDFRenderer(document).renderImageWithDPI(0,110),"PNG",Path.of("outputs/unidr-u3/long-caption-"+mode.name().toLowerCase(Locale.ROOT)+".png").toFile());
            }
        }
    }
    private String zipText(byte[] bytes,String path) throws Exception {
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; while((entry=zip.getNextEntry())!=null) if(entry.getName().equals(path)) return new String(zip.readAllBytes(),StandardCharsets.UTF_8);
        } throw new AssertionError("Missing "+path);
    }
    private void save(String name,JasperPrint print,byte[] pdf,byte[] xlsx) throws Exception {
        Path dir=Path.of("outputs/unidr-u3"); Files.createDirectories(dir); Files.write(dir.resolve(name+".pdf"),pdf); Files.write(dir.resolve(name+".xlsx"),xlsx);
        Files.writeString(dir.resolve(name+"-values.txt"),String.join("\n",rows(print).stream().map(row->String.join(" | ",row.cells())).toList()));
        try(var document=Loader.loadPDF(pdf)) {
            PDFRenderer renderer=new PDFRenderer(document);
            for(int page=0;page<document.getNumberOfPages();page++) ImageIO.write(renderer.renderImageWithDPI(page,110),"PNG",dir.resolve(name+"-page-"+(page+1)+".png").toFile());
        }
    }
    private List<Text> texts(JasperPrint print) {
        List<Text> result=new ArrayList<>(); int page=0;
        for(JRPrintPage value:print.getPages()) { collect(value.getElements(),0,page*print.getPageHeight(),result);page++; } return result;
    }
    private void collect(List<JRPrintElement> elements,int dx,int dy,List<Text> result) {
        for(JRPrintElement element:elements) {
            if(element instanceof JRPrintText text && text.getFullText()!=null && !text.getFullText().isBlank())
                result.add(new Text(dx+element.getX(),dy+element.getY(),text.getFullText().replace('\u00a0',' ').replace('\u202f',' ').strip(),text));
            if(element instanceof JRPrintFrame frame) collect(frame.getElements(),dx+frame.getX(),dy+frame.getY(),result);
        }
    }
    private List<Row> rows(JasperPrint print) {
        Map<Integer,List<Text>> byY=new TreeMap<>(); texts(print).forEach(text->byY.computeIfAbsent(text.y(),ignored->new ArrayList<>()).add(text));
        return byY.values().stream().map(values->{values.sort(Comparator.comparingInt(Text::x));return new Row(values.getFirst().text(),values.stream().map(Text::text).toList());}).toList();
    }
}

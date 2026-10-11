package org.ip.unidr.spike;

import net.sf.dynamicreports.report.base.expression.AbstractSimpleExpression;
import net.sf.dynamicreports.report.builder.DynamicReports;
import net.sf.dynamicreports.report.builder.column.TextColumnBuilder;
import net.sf.dynamicreports.report.builder.group.CustomGroupBuilder;
import net.sf.dynamicreports.report.builder.subtotal.SubtotalBuilder;
import net.sf.dynamicreports.report.constant.Calculation;
import net.sf.dynamicreports.report.constant.GroupHeaderLayout;
import net.sf.dynamicreports.report.datasource.DRDataSource;
import net.sf.dynamicreports.report.definition.ReportParameters;
import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.export.HtmlExporter;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.export.*;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Test-only U0 experiment. Native DR groups and subtotals, no production compiler changes. */
final class UniDrRenderSpike {
    record Input(Long workshopId, String workshop, Long itemId, String item,
                 Long dayId, String day, String detail, BigDecimal quantity) {}
    record Line(String label, List<String> cells) {}
    private record Positioned(int x, int y, String text) {}

    static List<Input> fixture() {
        return List.of(
            row(1L,"Цех A",11L,"Деталь X",1L,"01.10.2026","Позиция 1","2"),
            row(1L,"Цех A",11L,"Деталь X",1L,"01.10.2026","Позиция 2","4"),
            row(1L,"Цех A",12L,"Деталь Y",2L,"02.10.2026","Позиция 3","9"),
            row(2L,"Цех B",11L,"Деталь X",1L,"01.10.2026","Позиция 4","5"),
            row(2L,"Цех B",11L,"Деталь X",2L,"02.10.2026","Позиция 5","10"),
            row(2L,"Цех B",12L,"Деталь Y",2L,"02.10.2026","Позиция 6","6"));
    }

    static Input row(Long workshopId,String workshop,Long itemId,String item,Long dayId,
                     String day,String detail,String quantity) {
        return new Input(workshopId,workshop,itemId,item,dayId,day,detail,
            quantity==null?null:new BigDecimal(quantity));
    }

    static JasperPrint compile(List<Input> rows, int depth, boolean details) throws Exception {
        if (depth<0 || depth>3) throw new IllegalArgumentException("depth must be 0..3");
        var label = DynamicReports.col.column("Группа / запись", "detail", String.class).setWidth(205);
        var quantity = DynamicReports.col.column("Количество", "quantity", BigDecimal.class)
            .setPattern("0.00").setWidth(85);
        var average = DynamicReports.col.column("Среднее", DynamicReports.exp.value("")).setWidth(65);
        var minimum = DynamicReports.col.column("Минимум", DynamicReports.exp.value("")).setWidth(65);
        var maximum = DynamicReports.col.column("Максимум", DynamicReports.exp.value("")).setWidth(65);
        var count = DynamicReports.col.column("Записей", DynamicReports.exp.value("")).setWidth(60);
        var style = DynamicReports.stl.style().setFontName("DejaVu Sans").setFontSize(10).setPadding(4);
        var report = DynamicReports.report().setLocale(Locale.forLanguageTag("ru-RU"))
            .setColumnStyle(style).setSubtotalStyle(style)
            .setColumnTitleStyle(DynamicReports.stl.style(style).bold())
            .title(DynamicReports.cmp.text("UniDR U0: позиции накладных")
                .setStyle(DynamicReports.stl.style(style).bold().setFontSize(15)),
                DynamicReports.cmp.text(details ? "Детальные записи" : "Только итоги").setStyle(style))
            .columns(label,quantity,average,minimum,maximum,count)
            .setDetailPrintWhenExpression(DynamicReports.exp.value(details));
        for (int level=0; level<depth; level++) {
            report.fields(DynamicReports.field("caption"+level,String.class));
            CustomGroupBuilder group = DynamicReports.grp.group("level"+level,"key"+level,Long.class)
                .setHeaderLayout(GroupHeaderLayout.EMPTY).setPadding(0);
            report.groupBy(group);
            SubtotalBuilder<?,?>[] totals = subtotals(new Caption("caption"+level,level,
                details?"Итого: ":""), label,quantity,average,minimum,maximum,count);
            if (details) {
                group.header(DynamicReports.cmp.text(new Caption("caption"+level,level,""))
                    .setStyle(DynamicReports.stl.style(style).bold()));
                report.subtotalsAtGroupFooter(group,totals);
            } else report.subtotalsAtGroupHeader(group,totals);
        }
        report.subtotalsAtSummary(subtotals(new Caption(null,0,"Всего"),
            label,quantity,average,minimum,maximum,count));
        DRDataSource source = new DRDataSource("key0","caption0","key1","caption1",
            "key2","caption2","detail","quantity");
        for (Input row:rows) source.add(row.workshopId(),row.workshop(),row.itemId(),row.item(),
            row.dayId(),row.day(),row.detail(),row.quantity());
        return report.setDataSource(source).toJasperPrint();
    }

    private static SubtotalBuilder<?,?>[] subtotals(Caption caption,TextColumnBuilder<?> label,
            TextColumnBuilder<BigDecimal> quantity,TextColumnBuilder<?> average,
            TextColumnBuilder<?> minimum,TextColumnBuilder<?> maximum,TextColumnBuilder<?> count) {
        return new SubtotalBuilder<?,?>[]{
            DynamicReports.sbt.aggregate(caption,label,Calculation.NOTHING),
            DynamicReports.sbt.sum("quantity",BigDecimal.class,quantity).setPattern("0.00"),
            DynamicReports.sbt.avg("quantity",BigDecimal.class,average).setPattern("0.00"),
            DynamicReports.sbt.min("quantity",BigDecimal.class,minimum).setPattern("0.00"),
            DynamicReports.sbt.max("quantity",BigDecimal.class,maximum).setPattern("0.00"),
            DynamicReports.sbt.count(DynamicReports.exp.value(1),count)};
    }

    private static final class Caption extends AbstractSimpleExpression<String> {
        private final String field;
        private final int depth;
        private final String prefix;
        Caption(String field,int depth,String prefix) {this.field=field;this.depth=depth;this.prefix=prefix;}
        @Override public String evaluate(ReportParameters parameters) {
            return "    ".repeat(depth)+prefix+(field==null?"":Objects.toString(parameters.getFieldValue(field),"Не заполнено"));
        }
    }

    static List<Line> lines(JasperPrint print) {
        List<Line> result=new ArrayList<>();
        for (JRPrintPage page:print.getPages()) {
            List<Positioned> texts=new ArrayList<>();
            collect(page.getElements(),0,0,texts);
            Map<Integer,List<Positioned>> byY=new TreeMap<>();
            for(Positioned text:texts) byY.computeIfAbsent(text.y(),ignored->new ArrayList<>()).add(text);
            for(List<Positioned> line:byY.values()) {
                line.sort(Comparator.comparingInt(Positioned::x));
                result.add(new Line(line.getFirst().text().strip(),line.stream().map(t->t.text().strip()).toList()));
            }
        }
        return result;
    }

    private static void collect(List<JRPrintElement> elements,int dx,int dy,List<Positioned> result) {
        for(JRPrintElement element:elements) {
            if(element instanceof JRPrintText text && text.getFullText()!=null && !text.getFullText().isBlank())
                result.add(new Positioned(dx+element.getX(),dy+element.getY(),text.getFullText()));
            if(element instanceof JRPrintFrame frame) collect(frame.getElements(),dx+frame.getX(),dy+frame.getY(),result);
        }
    }

    static String html(JasperPrint print) throws Exception {
        StringWriter writer=new StringWriter();
        HtmlExporter exporter=new HtmlExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleHtmlExporterOutput(writer));
        SimpleHtmlReportConfiguration configuration=new SimpleHtmlReportConfiguration();
        exporter.setConfiguration(configuration);
        exporter.exportReport();
        return writer.toString();
    }

    static byte[] xlsx(JasperPrint print) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        JRXlsxExporter exporter=new JRXlsxExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(output));
        SimpleXlsxReportConfiguration configuration=new SimpleXlsxReportConfiguration();
        configuration.setDetectCellType(true);
        configuration.setRemoveEmptySpaceBetweenRows(true);
        exporter.setConfiguration(configuration);
        exporter.exportReport();
        return output.toByteArray();
    }

    static void artifacts(String name,JasperPrint print) throws Exception {
        Path directory=Path.of("outputs/unidr-u0");
        Files.createDirectories(directory);
        Files.write(directory.resolve(name+".pdf"),JasperExportManager.exportReportToPdf(print));
        Files.write(directory.resolve(name+".xlsx"),xlsx(print));
        Files.writeString(directory.resolve(name+".html"),html(print),StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(name+"-values.txt"),String.join("\n",lines(print).stream()
            .map(line->String.join(" | ",line.cells())).toList()),StandardCharsets.UTF_8);
    }
}

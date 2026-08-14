package org.ipro.reportstudio.render;

import net.sf.dynamicreports.jasper.builder.JasperReportBuilder;
import net.sf.dynamicreports.report.builder.DynamicReports;
import net.sf.dynamicreports.report.builder.column.ColumnBuilder;
import net.sf.dynamicreports.report.builder.column.TextColumnBuilder;
import net.sf.dynamicreports.report.builder.column.ValueColumnBuilder;
import net.sf.dynamicreports.report.builder.component.ComponentBuilder;
import net.sf.dynamicreports.report.builder.group.ColumnGroupBuilder;
import net.sf.dynamicreports.report.builder.style.ReportStyleBuilder;
import net.sf.dynamicreports.report.builder.subtotal.SubtotalBuilder;
import net.sf.dynamicreports.report.datasource.DRDataSource;
import net.sf.dynamicreports.report.constant.HorizontalAlignment;
import net.sf.dynamicreports.report.constant.PageOrientation;
import net.sf.dynamicreports.report.constant.PageType;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.export.JRCsvExporter;
import net.sf.jasperreports.engine.export.ooxml.JRDocxExporter;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.export.Exporter;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimpleWriterExporterOutput;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.data.ReportRow;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportTemplate;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Компилятор отчёта на DynamicReports (форк 7.0.0-SNAPSHOT) + JasperReports 7.
 * Бэнд-модель ({@link ReportTemplate}) → JasperPrint:
 * <ul>
 *   <li>REPORT_HEADER → title (имя шаблона);</li>
 *   <li>DETAIL → detail-колонки (caption/width/format/alignment);</li>
 *   <li>GROUP_HEADER → groupBy (внешняя группа первой, вложенность по parent);</li>
 *   <li>GROUP_FOOTER → subtotalsAtGroupFooter, REPORT_FOOTER → subtotalsAtSummary
 *       (плюс текстовые поля футера — в summary);</li>
 *   <li>данные — Collection of Map: сущностные колонки строками (caption),
 *       скалярные — исходными значениями (агрегаты считают по числам).</li>
 * </ul>
 */
public class JasperReportCompiler implements ReportCompiler {

    private static final Locale REPORT_LOCALE = new Locale("ru", "RU");

    static {
        System.setProperty("net.sf.jasperreports.default.fontname", "DejaVu Sans");
        System.setProperty("net.sf.jasperreports.default.fontsize", "10");
        System.setProperty("net.sf.jasperreports.pdf.embedded", "true");
    }

    @Override
    public JasperPrint compile(ReportTemplate template, ReportDataset dataset) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(dataset, "dataset");
        try {
            JasperReportBuilder report = DynamicReports.report()
                .setLocale(REPORT_LOCALE)
                .setPageFormat(PageType.A4, PageOrientation.PORTRAIT);

            ReportBand header = bandOf(template, ReportBandKind.REPORT_HEADER);
            if (template.getName() != null && !template.getName().isBlank()) {
                report.setTitleStyle(titleStyle());
                report.title(DynamicReports.cmp.text(template.getName()));
            }

            List<TextColumnBuilder<?>> detailColumns = new ArrayList<>();
            Map<String, TextColumnBuilder<?>> columnsByField = new LinkedHashMap<>();
            for (ReportField field : visibleFields(bandOf(template, ReportBandKind.DETAIL))) {
                detailColumns.add(buildColumn(field, dataset, columnsByField));
            }
            if (!detailColumns.isEmpty()) {
                report.columns(detailColumns.toArray(new ColumnBuilder[0]));
            }

            List<GroupBinding> groupBindings = buildGroups(template, dataset, columnsByField);
            if (!groupBindings.isEmpty()) {
                ColumnGroupBuilder[] groupArray = groupBindings.stream()
                    .map(GroupBinding::builder)
                    .toArray(ColumnGroupBuilder[]::new);
                report.groupBy(groupArray);
            }

            for (ReportBand groupFooter : footerBands(template)) {
                List<SubtotalBuilder<?, ?>> subtotals =
                    buildSubtotals(groupFooter, dataset, columnsByField);
                if (!subtotals.isEmpty()) {
                    ColumnGroupBuilder group = groupOf(groupBindings, groupFooter);
                    report.subtotalsAtGroupFooter(group,
                        subtotals.toArray(new SubtotalBuilder[0]));
                }
            }

            ReportBand reportFooter = bandOf(template, ReportBandKind.REPORT_FOOTER);
            if (reportFooter != null) {
                List<SubtotalBuilder<?, ?>> subtotals =
                    buildSubtotals(reportFooter, dataset, columnsByField);
                List<ComponentBuilder<?, ?>> texts = new ArrayList<>();
                for (ReportField field : visibleFields(reportFooter)) {
                    if (field.getAggregation() == null
                            || field.getAggregation() == ReportFieldAggregation.NONE) {
                        String caption = captionOf(field, dataset);
                        if (caption != null && !caption.isBlank()) {
                            texts.add(DynamicReports.cmp.text(caption));
                        }
                    }
                }
                if (!texts.isEmpty()) {
                    report.summary(texts.toArray(new ComponentBuilder[0]));
                }
                if (!subtotals.isEmpty()) {
                    report.subtotalsAtSummary(subtotals.toArray(new SubtotalBuilder[0]));
                }
            }

            report.setDataSource(rowsAsDataSource(dataset));
            return report.toJasperPrint();
        } catch (ReportRenderException e) {
            throw e;
        } catch (Exception e) {
            throw new ReportRenderException("Компиляция отчёта не удалась: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] export(JasperPrint print, ReportExportFormat format) {
        Objects.requireNonNull(print, "print");
        Objects.requireNonNull(format, "format");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            switch (format) {
                case PDF -> JasperExportManager.exportReportToPdfStream(print, out);
                case XLSX -> exportWith(new JRXlsxExporter(), print, out);
                case DOCX -> exportWith(new JRDocxExporter(), print, out);
                case CSV -> exportCsv(print, out);
            }
            return out.toByteArray();
        } catch (JRException e) {
            throw new ReportRenderException("Экспорт в " + format + " не удался: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void exportWith(Exporter exporter, JasperPrint print, OutputStream out)
            throws JRException {
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
        exporter.exportReport();
    }

    private static void exportCsv(JasperPrint print, OutputStream out) throws JRException {
        JRCsvExporter exporter = new JRCsvExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleWriterExporterOutput(
            new OutputStreamWriter(out, StandardCharsets.UTF_8)));
        exporter.exportReport();
    }

    // ---------------------------------------------------------------- модель

    private static ReportBand bandOf(ReportTemplate template, ReportBandKind kind) {
        for (ReportBand band : template.getBands()) {
            if (band.getKind() == kind) {
                return band;
            }
        }
        return null;
    }

    private static List<ReportField> visibleFields(ReportBand band) {
        List<ReportField> result = new ArrayList<>();
        if (band != null) {
            for (ReportField field : band.getFields()) {
                if (field.isVisible()) {
                    result.add(field);
                }
            }
        }
        return result;
    }

    private static List<ReportBand> footerBands(ReportTemplate template) {
        List<ReportBand> result = new ArrayList<>();
        for (ReportBand band : template.getBands()) {
            if (band.getKind() == ReportBandKind.GROUP_FOOTER) {
                result.add(band);
            }
        }
        return result;
    }

    /** Иерархия групп: внешняя (parent == null) первой, вложенные после, по position. */
    private static List<GroupBinding> buildGroups(ReportTemplate template,
            ReportDataset dataset, Map<String, TextColumnBuilder<?>> columnsByField) {
        List<ReportBand> headers = new ArrayList<>();
        for (ReportBand band : template.getBands()) {
            if (band.getKind() == ReportBandKind.GROUP_HEADER) {
                headers.add(band);
            }
        }
        List<ReportBand> ordered = new ArrayList<>(headers.size());
        for (ReportBand root : sortedOf(headers, b -> b.getParent() == null)) {
            walkChildren(root, headers, ordered);
        }
        for (ReportBand leftover : sortedOf(headers, b -> !ordered.contains(b))) {
            walkChildren(leftover, headers, ordered);
        }

        List<GroupBinding> bindings = new ArrayList<>(ordered.size());
        for (ReportBand header : ordered) {
            QueryField groupField = dataset.field(header.getGroupField());
            if (groupField == null) {
                throw new ReportRenderException("Группировка по неизвестному полю: "
                    + header.getGroupField());
            }
            TextColumnBuilder<?> column = columnOrNew(groupField, columnsByField);
            ColumnGroupBuilder group = DynamicReports.grp.group(column);
            boolean inDetail = columnsByField.containsKey(header.getGroupField());
            if (!inDetail) {
                group.setHideColumn(true);
            }
            bindings.add(new GroupBinding(header, group));
        }
        return bindings;
    }

    private static void walkChildren(ReportBand band, List<ReportBand> all, List<ReportBand> out) {
        if (out.contains(band)) {
            return;
        }
        out.add(band);
        for (ReportBand child : sortedOf(all, b -> b.getParent() == band)) {
            walkChildren(child, all, out);
        }
    }

    private static List<ReportBand> sortedOf(List<ReportBand> bands,
            java.util.function.Predicate<ReportBand> filter) {
        return bands.stream()
            .filter(filter)
            .sorted(Comparator.comparingInt(ReportBand::getPosition))
            .toList();
    }

    private static ColumnGroupBuilder groupOf(List<GroupBinding> bindings, ReportBand footer) {
        ReportBand parent = footer.getParent();
        if (parent == null) {
            throw new ReportRenderException("GROUP_FOOTER без родительской группы (position="
                + footer.getPosition() + ")");
        }
        for (GroupBinding binding : bindings) {
            if (binding.header().equals(parent)) {
                return binding.builder();
            }
        }
        throw new ReportRenderException("GROUP_FOOTER не соответствует ни одной группе: "
            + parent.getGroupField());
    }

    private static List<SubtotalBuilder<?, ?>> buildSubtotals(ReportBand footer,
            ReportDataset dataset, Map<String, TextColumnBuilder<?>> columnsByField) {
        List<SubtotalBuilder<?, ?>> subtotals = new ArrayList<>();
        for (ReportField field : visibleFields(footer)) {
            ReportFieldAggregation aggregation = field.getAggregation();
            if (aggregation == null || aggregation == ReportFieldAggregation.NONE) {
                continue;
            }
            QueryField qf = dataset.field(field.getQueryField());
            if (qf == null) {
                throw new ReportRenderException("Агрегация по неизвестному полю: "
                    + field.getQueryField());
            }
            subtotals.add(mapAggregation(aggregation, columnOrNew(qf, columnsByField))
                .setLabel(captionOf(field, dataset)));
        }
        return subtotals;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SubtotalBuilder<?, ?> mapAggregation(ReportFieldAggregation aggregation,
            TextColumnBuilder<?> column) {
        return switch (aggregation) {
            case SUM -> DynamicReports.sbt.sum((ValueColumnBuilder) column);
            case COUNT -> DynamicReports.sbt.count(column);
            case AVG -> DynamicReports.sbt.avg((ValueColumnBuilder) column);
            case MIN -> DynamicReports.sbt.min((ValueColumnBuilder) column);
            case MAX -> DynamicReports.sbt.max((ValueColumnBuilder) column);
            case NONE -> throw new IllegalStateException("NONE обрабатывается выше");
        };
    }

    private static TextColumnBuilder<?> buildColumn(ReportField field, ReportDataset dataset,
            Map<String, TextColumnBuilder<?>> columnsByField) {
        QueryField qf = dataset.field(field.getQueryField());
        if (qf == null) {
            throw new ReportRenderException("Неизвестное поле: " + field.getQueryField());
        }
        TextColumnBuilder<?> column = columnOrNew(qf, columnsByField);
        if (field.getWidth() != null && field.getWidth() > 0) {
            column.setFixedWidth(field.getWidth());
        }
        if (field.getFormat() != null && !field.getFormat().isBlank()
                && (QueryField.isNumber(qf.javaType()) || isTemporal(qf.javaType()))) {
            column.setPattern(field.getFormat());
        }
        if (field.getAlignment() != null) {
            column.setHorizontalAlignment(mapAlignment(field.getAlignment()));
        }
        return column;
    }

    private static String captionOf(ReportField field, ReportDataset dataset) {
        QueryField qf = dataset.field(field.getQueryField());
        if (qf == null) {
            return field.getCaption();
        }
        return field.getCaption() != null && !field.getCaption().isBlank()
            ? field.getCaption() : qf.caption();
    }

    private static TextColumnBuilder<?> columnOrNew(QueryField qf,
            Map<String, TextColumnBuilder<?>> columnsByField) {
        return columnsByField.computeIfAbsent(qf.name(), name -> columnOf(qf));
    }

    private static TextColumnBuilder<?> columnOf(QueryField qf) {
        Class<?> type = columnType(qf.javaType());
        return DynamicReports.col.column(qf.caption(), qf.name(), type);
    }

    private static Class<?> columnType(Class<?> javaType) {
        return isScalar(javaType) ? javaType : String.class;
    }

    private static boolean isScalar(Class<?> type) {
        if (type == null || type == Object.class) {
            return false;
        }
        return String.class.isAssignableFrom(type)
            || CharSequence.class.isAssignableFrom(type)
            || Number.class.isAssignableFrom(type)
            || type.isPrimitive()
            || Boolean.class.isAssignableFrom(type)
            || isTemporal(type)
            || java.util.Date.class.isAssignableFrom(type)
            || java.util.Calendar.class.isAssignableFrom(type);
    }

    private static boolean isTemporal(Class<?> type) {
        return type != null && java.time.temporal.Temporal.class.isAssignableFrom(type);
    }

    private static HorizontalAlignment mapAlignment(ReportFieldAlignment alignment) {
        return switch (alignment) {
            case LEFT -> HorizontalAlignment.LEFT;
            case RIGHT -> HorizontalAlignment.RIGHT;
            case CENTER -> HorizontalAlignment.CENTER;
        };
    }

    private static ReportStyleBuilder titleStyle() {
        return DynamicReports.stl.style()
            .bold()
            .setFontSize(14)
            .setHorizontalAlignment(HorizontalAlignment.CENTER)
            .setTopPadding(8)
            .setBottomPadding(8);
    }

    private static DRDataSource rowsAsDataSource(ReportDataset dataset) {
        String[] fieldNames = new String[dataset.fields().length];
        for (int index = 0; index < dataset.fields().length; index++) {
            fieldNames[index] = dataset.fields()[index].name();
        }
        DRDataSource dataSource = new DRDataSource(fieldNames);
        for (ReportRow row : dataset.rows()) {
            Object[] values = new Object[fieldNames.length];
            for (int index = 0; index < fieldNames.length; index++) {
                QueryField field = dataset.fields()[index];
                values[index] = isScalar(field.javaType())
                    ? row.value(field.name())
                    : row.displayValue(field.name());
            }
            dataSource.add(values);
        }
        return dataSource;
    }
    private record GroupBinding(ReportBand header, ColumnGroupBuilder builder) {
    }
}
package org.ipro.reportstudio.render;

import net.sf.dynamicreports.jasper.builder.JasperReportBuilder;
import net.sf.dynamicreports.report.builder.DynamicReports;
import net.sf.dynamicreports.report.builder.column.ColumnBuilder;
import net.sf.dynamicreports.report.builder.column.TextColumnBuilder;
import net.sf.dynamicreports.report.builder.column.ValueColumnBuilder;
import net.sf.dynamicreports.report.builder.component.ComponentBuilder;
import net.sf.dynamicreports.report.builder.component.TextFieldBuilder;
import net.sf.dynamicreports.report.builder.group.ColumnGroupBuilder;
import net.sf.dynamicreports.report.builder.style.ReportStyleBuilder;
import net.sf.dynamicreports.report.builder.style.StyleBuilder;
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
import org.ipro.reportstudio.dom.ReportFieldKind;
import org.ipro.reportstudio.dom.ReportPageOrientation;
import org.ipro.reportstudio.dom.ReportPageSize;
import org.ipro.reportstudio.dom.ReportTemplate;

import java.awt.Color;
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
 *   <li>REPORT_HEADER → title: имя шаблона + текстовые блоки (kind=TEXT);</li>
 *   <li>DETAIL → detail-колонки (caption/width/format/alignment);</li>
 *   <li>GROUP_HEADER → groupBy (внешняя группа первой, вложенность по parent);</li>
 *   <li>GROUP_FOOTER → subtotalsAtGroupFooter + текстовые блоки (footer группы);</li>
 *   <li>REPORT_FOOTER → subtotalsAtSummary + текстовые блоки (summary);</li>
 *   <li>оформление из шаблона: формат/ориентация страницы, базовый шрифт,
 *       сетка (границы колонок), полосатость строк;</li>
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
                .setPageFormat(pageType(template.pageSizeOrDefault()),
                    pageOrientation(template.pageOrientationOrDefault()));

            int fontSize = template.baseFontSizeOrDefault();
            boolean grid = template.isGridEnabled();
            ReportBand header = bandOf(template, ReportBandKind.REPORT_HEADER);

            List<ComponentBuilder<?, ?>> titles = new ArrayList<>();
            if (template.getName() != null && !template.getName().isBlank()) {
                titles.add(DynamicReports.cmp.text(template.getName()).setStyle(titleStyle()));
            }
            titles.addAll(textBlocks(header, fontSize));
            if (!titles.isEmpty()) {
                report.setTitleStyle(titleStyle());
                report.title(titles.toArray(new ComponentBuilder[0]));
            }
            applyTableStyle(report, fontSize, grid);
            report.setHighlightDetailEvenRows(template.isStripeRows());

            List<TextColumnBuilder<?>> detailColumns = new ArrayList<>();
            Map<String, TextColumnBuilder<?>> columnsByField = new LinkedHashMap<>();
            List<ComputedSpec> computed = new ArrayList<>();
            for (ReportField field : visibleFields(bandOf(template, ReportBandKind.DETAIL))) {
                detailColumns.add(buildColumn(field, dataset, columnsByField, fontSize, computed));
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
                ColumnGroupBuilder group = groupOf(groupBindings, groupFooter);
                List<SubtotalBuilder<?, ?>> subtotals =
                    buildSubtotals(groupFooter, columnsByField, fontSize, grid);
                List<ComponentBuilder<?, ?>> texts = textBlocks(groupFooter, fontSize);
                if (!texts.isEmpty()) {
                    group.footer(texts.toArray(new ComponentBuilder[0]));
                }
                if (!subtotals.isEmpty()) {
                    report.subtotalsAtGroupFooter(group,
                        subtotals.toArray(new SubtotalBuilder[0]));
                }
            }

            ReportBand reportFooter = bandOf(template, ReportBandKind.REPORT_FOOTER);
            if (reportFooter != null) {
                List<SubtotalBuilder<?, ?>> subtotals =
                    buildSubtotals(reportFooter, columnsByField, fontSize, grid);
                List<ComponentBuilder<?, ?>> texts = textBlocks(reportFooter, fontSize);
                if (!texts.isEmpty()) {
                    report.summary(texts.toArray(new ComponentBuilder[0]));
                }
                if (!subtotals.isEmpty()) {
                    report.subtotalsAtSummary(subtotals.toArray(new SubtotalBuilder[0]));
                }
            }

            ReportBand noData = bandOf(template, ReportBandKind.NO_DATA);
            if (noData != null) {
                List<ComponentBuilder<?, ?>> texts = textBlocks(noData, fontSize);
                if (!texts.isEmpty()) {
                    report.noData(texts.toArray(new ComponentBuilder[0]));
                }
            }

            report.setDataSource(rowsAsDataSource(dataset, computed));
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
            if (header.isStartNewPage()) {
                group.setStartInNewPage(true);
            }
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
            String groupField = footer.getGroupField();
            for (GroupBinding binding : bindings) {
                if (groupField != null && groupField.equals(binding.header().getGroupField())) {
                    return binding.builder();
                }
            }
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

    /**
     * Собирает подытоги footer-бэнда. Поле COLUMN с aggregation != NONE обязано
     * ссылаться на колонку, уже объявленную в DETAIL ({@code columnsByField}) —
     * иначе «повисший» столбец, которого не видит ни layout, ни данные.
     */
    private static List<SubtotalBuilder<?, ?>> buildSubtotals(ReportBand footer,
            Map<String, TextColumnBuilder<?>> columnsByField, int fontSize, boolean grid) {
        List<SubtotalBuilder<?, ?>> subtotals = new ArrayList<>();
        StyleBuilder valueStyle = grid ? bodyStyle(fontSize) : null;
        StyleBuilder labelStyle = grid ? columnTitleStyle(fontSize) : null;
        for (ReportField field : visibleFields(footer)) {
            if (field.isText()) {
                continue;
            }
            ReportFieldAggregation aggregation = field.getAggregation();
            if (aggregation == null || aggregation == ReportFieldAggregation.NONE) {
                continue;
            }
            TextColumnBuilder<?> column = columnsByField.get(field.getQueryField());
            if (column == null) {
                throw new ReportRenderException("Агрегат по колонке «" + field.getQueryField()
                    + "», которая не объявлена в DETAIL");
            }
            SubtotalBuilder<?, ?> subtotal = mapAggregation(aggregation, column);
            // Без label: заголовок колонки уже напечатан в шапке таблицы, дублировать его
            // в строке подытога не нужно — остаётся только агрегированное значение.
            if (valueStyle != null) {
                subtotal.setStyle(valueStyle);
            }
            if (labelStyle != null) {
                subtotal.setLabelStyle(labelStyle);
            }
            subtotals.add(subtotal);
        }
        return subtotals;
    }

    /** Текстовые блоки (kind=TEXT) бэнда: только text и выравнивание. */
    private static List<ComponentBuilder<?, ?>> textBlocks(ReportBand band, int fontSize) {
        List<ComponentBuilder<?, ?>> texts = new ArrayList<>();
        if (band == null) {
            return texts;
        }
        StyleBuilder base = baseStyle(fontSize);
        for (ReportField field : visibleFields(band)) {
            if (!field.isText() || isBlank(field.getText())) {
                continue;
            }
            TextFieldBuilder<String> text = DynamicReports.cmp.text(field.getText())
                .setStyle(base);
            if (field.getBorder() != null) {
                text.setStyle(bodyStyle(fontSize, field.getBorder()));
            }
            if (field.getAlignment() != null) {
                text.setHorizontalAlignment(mapAlignment(field.getAlignment()));
            }
            texts.add(text);
        }
        return texts;
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
            Map<String, TextColumnBuilder<?>> columnsByField, int fontSize,
            List<ComputedSpec> computed) {
        ReportFieldKind kind = field.kindOrDefault();
        TextColumnBuilder<?> column;
        if (kind == ReportFieldKind.ROW_NUMBER) {
            column = DynamicReports.col.reportRowNumberColumn(
                isBlank(field.getCaption()) ? "№" : field.getCaption());
        } else if (kind == ReportFieldKind.EXPRESSION || kind == ReportFieldKind.FORMULA) {
            String synthetic = "cf" + (computed.size() + 1);
            computed.add(new ComputedSpec(synthetic, field));
            Class<?> type = kind == ReportFieldKind.FORMULA ? java.math.BigDecimal.class : String.class;
            column = DynamicReports.col.column(
                isBlank(field.getCaption()) ? "" : field.getCaption(), synthetic, type);
        } else {
            QueryField qf = dataset.field(field.getQueryField());
            if (qf == null) {
                throw new ReportRenderException("Неизвестное поле: " + field.getQueryField());
            }
            column = columnOrNew(qf, columnsByField);
        }
        if (field.getBorder() != null) {
            column.setStyle(bodyStyle(fontSize, field.getBorder()));
        }
        if (field.getWidth() != null && field.getWidth() > 0) {
            column.setFixedWidth(field.getWidth());
        }
        if (field.getFormat() != null && !field.getFormat().isBlank()
                && (kind == ReportFieldKind.ROW_NUMBER || kind == ReportFieldKind.FORMULA
                    || isFormatApplicable(field, dataset))) {
            column.setPattern(field.getFormat());
        }
        if (field.getAlignment() != null) {
            column.setHorizontalAlignment(mapAlignment(field.getAlignment()));
        }
        return column;
    }

    /** Ссылка вычисляемой колонки: синтетическое имя поля в данных + источник. */
    private record ComputedSpec(String syntheticName, ReportField field) {
    }

    /** Формат применим к числовым/датовым колонкам (row number — число всегда). */
    private static boolean isFormatApplicable(ReportField field, ReportDataset dataset) {
        QueryField qf = dataset.field(field.getQueryField());
        return qf != null && (QueryField.isNumber(qf.javaType()) || isTemporal(qf.javaType()));
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

    // ---------------------------------------------------------------- оформление

    private static void applyTableStyle(JasperReportBuilder report, int fontSize, boolean grid) {
        report.setColumnStyle(bodyStyle(fontSize, grid));
        report.setColumnTitleStyle(columnTitleStyle(fontSize, grid));
        StyleBuilder base = baseStyle(fontSize);
        report.setTextStyle(base);
    }

    private static StyleBuilder baseStyle(int fontSize) {
        return DynamicReports.stl.style()
            .setFontName("DejaVu Sans")
            .setFontSize(fontSize);
    }

    /** Стиль ячейки колонки (и подытога): базовый шрифт, при сетке — рамка. */
    private static StyleBuilder bodyStyle(int fontSize, boolean grid) {
        StyleBuilder style = baseStyle(fontSize).setPadding(2);
        if (grid) {
            style.setBorder(DynamicReports.stl.penThin());
        }
        return style;
    }

    /** Стиль ячейки с явной границей поля (без учёта сетки шаблона). */
    private static StyleBuilder bodyStyle(int fontSize, Boolean border) {
        return bodyStyle(fontSize, border != null && border);
    }

    private static StyleBuilder bodyStyle(int fontSize) {
        return bodyStyle(fontSize, true);
    }

    /** Стиль заголовка колонки: жирный, фон, при сетке — рамка. */
    private static StyleBuilder columnTitleStyle(int fontSize, boolean grid) {
        StyleBuilder style = baseStyle(fontSize)
            .bold()
            .setBackgroundColor(new Color(0xEC, 0xEC, 0xEC))
            .setHorizontalAlignment(HorizontalAlignment.CENTER)
            .setPadding(3);
        if (grid) {
            style.setBorder(DynamicReports.stl.penThin());
        }
        return style;
    }

    private static StyleBuilder columnTitleStyle(int fontSize) {
        return columnTitleStyle(fontSize, true);
    }

    private static ReportStyleBuilder titleStyle() {
        return DynamicReports.stl.style()
            .bold()
            .setFontSize(14)
            .setHorizontalAlignment(HorizontalAlignment.CENTER)
            .setTopPadding(8)
            .setBottomPadding(8);
    }

    private static PageType pageType(ReportPageSize size) {
        return switch (size) {
            case A4 -> PageType.A4;
            case A5 -> PageType.A5;
            case A3 -> PageType.A3;
            case LETTER -> PageType.LETTER;
            case LEGAL -> PageType.LEGAL;
        };
    }

    private static PageOrientation pageOrientation(ReportPageOrientation orientation) {
        return switch (orientation) {
            case PORTRAIT -> PageOrientation.PORTRAIT;
            case LANDSCAPE -> PageOrientation.LANDSCAPE;
        };
    }

    private static DRDataSource rowsAsDataSource(ReportDataset dataset, List<ComputedSpec> computed) {
        String[] baseNames = fieldNamesOf(dataset);
        int baseCount = baseNames.length;
        String[] allNames = new String[baseCount + computed.size()];
        for (int index = 0; index < baseNames.length; index++) {
            allNames[index] = baseNames[index];
        }
        for (int index = 0; index < computed.size(); index++) {
            allNames[baseCount + index] = computed.get(index).syntheticName();
        }
        DRDataSource dataSource = new DRDataSource(allNames);
        for (ReportRow row : dataset.rows()) {
            Object[] values = new Object[allNames.length];
            for (int index = 0; index < baseCount; index++) {
                QueryField field = dataset.fields()[index];
                values[index] = isScalar(field.javaType())
                    ? row.value(field.name())
                    : row.displayValue(field.name());
            }
            for (int index = 0; index < computed.size(); index++) {
                values[baseCount + index] = computedValue(computed.get(index).field(), row);
            }
            dataSource.add(values);
        }
        return dataSource;
    }

    private static String[] fieldNamesOf(ReportDataset dataset) {
        String[] fieldNames = new String[dataset.fields().length];
        for (int index = 0; index < dataset.fields().length; index++) {
            fieldNames[index] = dataset.fields()[index].name();
        }
        return fieldNames;
    }

    /** Значение вычисляемой колонки для строки: EXPRESSION — шаблон, FORMULA — арифметика. */
    private static Object computedValue(ReportField field, ReportRow row) {
        if (field.kindOrDefault() == ReportFieldKind.EXPRESSION) {
            return renderTemplate(field, row);
        }
        try {
            return FormulaEvaluator.evaluate(field.getText(),
                alias -> FormulaEvaluator.toBigDecimal(row.value(alias)));
        } catch (IllegalArgumentException unknownOrBroken) {
            return null;
        }
    }

    /** Подставляет {@code {alias}} значениями колонок строки (числа/даты — с format колонки). */
    private static String renderTemplate(ReportField field, ReportRow row) {
        String template = field.getText();
        if (template == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(template.length());
        int index = 0;
        while (index < template.length()) {
            int open = template.indexOf('{', index);
            if (open < 0) {
                out.append(template, index, template.length());
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                out.append(template, index, template.length());
                break;
            }
            out.append(template, index, open);
            String alias = template.substring(open + 1, close).trim();
            out.append(aliasValue(alias, field.getFormat(), row));
            index = close + 1;
        }
        return out.toString();
    }

    private static String aliasValue(String alias, String format, ReportRow row) {
        Object value;
        try {
            value = row.value(alias);
        } catch (IllegalArgumentException unknown) {
            return "";
        }
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            if (!isBlank(format)) {
                try {
                    return new java.text.DecimalFormat(format,
                            java.text.DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru-RU")))
                            .format(number);
                } catch (IllegalArgumentException badPattern) {
                    return number.toString();
                }
            }
            return number instanceof java.math.BigDecimal decimal
                ? decimal.toPlainString() : number.toString();
        }
        if (isTemporal(value.getClass())) {
            if (!isBlank(format)) {
                try {
                    return java.time.format.DateTimeFormatter.ofPattern(format).format((java.time.temporal.Temporal) value);
                } catch (RuntimeException badPattern) {
                    return value.toString();
                }
            }
        }
        return value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record GroupBinding(ReportBand header, ColumnGroupBuilder builder) {
    }
}

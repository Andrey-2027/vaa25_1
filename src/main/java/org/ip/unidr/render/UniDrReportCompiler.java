package org.ip.unidr.render;

import net.sf.dynamicreports.report.base.expression.AbstractSimpleExpression;
import net.sf.dynamicreports.report.builder.DynamicReports;
import net.sf.dynamicreports.report.builder.column.TextColumnBuilder;
import net.sf.dynamicreports.report.builder.group.CustomGroupBuilder;
import net.sf.dynamicreports.report.builder.style.StyleBuilder;
import net.sf.dynamicreports.report.builder.subtotal.SubtotalBuilder;
import net.sf.dynamicreports.report.constant.*;
import net.sf.dynamicreports.report.datasource.DRDataSource;
import net.sf.dynamicreports.report.definition.ReportParameters;
import net.sf.dynamicreports.report.definition.expression.DRIExpression;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JRElementGroup;
import net.sf.jasperreports.engine.design.JRDesignTextField;
import net.sf.jasperreports.engine.type.EvaluationTimeEnum;
import net.sf.jasperreports.export.XlsReportConfiguration;
import org.ip.unidr.model.UniDrDefinition;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.model.UniDrDefinitionValidator;
import org.ip.unidr.model.UniDrComputedColumns;
import org.ip.unidr.source.*;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.data.ReportRow;
import org.ipro.reportstudio.render.ReportRenderException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.awt.Color;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Native DR groups/subtotals over a detached dataset; no UDR bands or database access. */
public final class UniDrReportCompiler {
    private static final Color STRIPE = new Color(0xF2, 0xF5, 0xF8);
    private static final Color HEADER = new Color(0xE2, 0xEA, 0xF2);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private final UniDrSourceCatalog catalog;
    private final UniDrDefinitionValidator validator;

    public UniDrReportCompiler(UniDrSourceCatalog catalog, UniDrDefinitionValidator validator) {
        this.catalog = Objects.requireNonNull(catalog); this.validator = Objects.requireNonNull(validator);
    }

    public JasperPrint compile(UniDrDefinition definition, ReportDataset dataset) {
        validator.requireValid(definition);
        return compile(definition,dataset,catalog.resolve(definition).orElseThrow());
    }
    /** Metadata from the completed server read avoids repeating query analysis/access reads during rendering. */
    public JasperPrint compile(UniDrDefinition definition,ReportDataset dataset,UniDrSourceDefinition source) {
        // The source plan and predicates were validated before the completed server read.
        // Rendering validates layout against its detached schema, without resolving SQL fields again.
        new UniDrDefinitionValidator(new UniDrSourceCatalog(List.of(source))).requireValid(definition.withQuery(null).withFilterTree(null));Objects.requireNonNull(dataset);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new ReportRenderException("Формирование макета требует завершённой транзакции чтения");
        try {
            dataset=UniDrComputedColumns.evaluate(definition,source,dataset);
            Map<String, UniDrSourceDefinition.Field> fields = new LinkedHashMap<>();
            source.fields().forEach(field -> fields.put(field.id(), field));
            definition.computedColumns().forEach(column->fields.put(column.id(),UniDrComputedColumns.field(column)));
            verifyFields(definition, dataset, fields);
            List<UniDrSourceDefinition.Field> groups = definition.groups().stream().map(fields::get).toList();
            Map<List<Object>, Integer> ordinals = groupOrdinals(dataset, groups);
            var base = baseStyle();
            var table = cellStyle(definition.appearance());
            var report = DynamicReports.report().setLocale(Locale.forLanguageTag("ru-RU"))
                .setPageFormat(PageType.A4, columnCount(definition) > 6 ? PageOrientation.LANDSCAPE : PageOrientation.PORTRAIT)
                .setColumnStyle(table).setSubtotalStyle(table)
                .setColumnTitleStyle(DynamicReports.stl.style(table).bold().setBackgroundColor(HEADER))
                .setTextStyle(base)
                .title(DynamicReports.cmp.text(definition.title()).setStyle(DynamicReports.stl.style(base).bold().setFontSize(15)))
                .pageFooter(DynamicReports.cmp.pageXofY().setFormatExpression("Страница {0} из {1}").setStyle(base))
                .setDetailPrintWhenExpression(DynamicReports.exp.value(definition.mode() == Mode.DETAILS))
                .setHighlightDetailEvenRows(definition.appearance().stripeRows())
                .setDetailEvenRowStyle(DynamicReports.stl.simpleStyle().setBackgroundColor(STRIPE))
                .setWhenNoDataType(WhenNoDataType.NO_DATA_SECTION)
                .noData(DynamicReports.cmp.text(definition.title()).setStyle(DynamicReports.stl.style(base).bold().setFontSize(15)),
                    DynamicReports.cmp.text("По выбранным условиям данных нет").setStyle(base));

            // Register original typed fields even when hidden from the visible table.
            for (QueryField field : dataset.fields()) report.fields(DynamicReports.field(field.name(), field.javaType()));
            TextColumnBuilder<String> label = null;
            // Details already have full-width group headers. A summary needs a
            // group column only when there are actual groups, never just for "Всего".
            if (definition.mode() == Mode.SUMMARY && !groups.isEmpty()) {
                label = DynamicReports.col.column("Группа", DynamicReports.exp.value("")).setFixedWidth(180);
                label.setTextAdjust(TextAdjust.STRETCH_HEIGHT);
                report.columns(label);
            }
            Map<String, TextColumnBuilder<?>> columns = new LinkedHashMap<>();
            if(definition.mode()==Mode.DETAILS && definition.rowNumbers())
                report.columns(DynamicReports.col.reportRowNumberColumn("№").setWidth(35).setPattern("0"));
            if (definition.mode() == Mode.DETAILS) for (Column column : definition.columns()) {
                var field = fields.get(column.fieldId());
                var builder = column(field, caption(column.title(), field.caption()), dataset,definition.fieldStyles().getOrDefault(field.id(),FieldStyle.defaults()));
                columns.put(field.id(), builder); report.columns(builder);
            }
            for (Measure measure : definition.measures()) if (!columns.containsKey(measure.fieldId())) {
                var field = fields.get(measure.fieldId());
                String title = caption(measure.title(), measureCaption(field, measure.aggregation()));
                TextColumnBuilder<?> builder;
                var presentation=definition.fieldStyles().getOrDefault(field.id(),FieldStyle.defaults());
                if (definition.mode() == Mode.DETAILS && field.valueAlias() != null) builder = column(field, title, dataset,presentation);
                else builder = DynamicReports.col.column(title, DynamicReports.exp.value("")).setWidth(100);
                if (definition.mode() == Mode.DETAILS && measure.aggregation() == Aggregation.COUNT_ROWS)
                    builder = DynamicReports.col.column(title, DynamicReports.exp.value(1)).setWidth(80);
                builder.setHorizontalTextAlignment(HorizontalTextAlignment.RIGHT);
                applyStyle(builder,presentation);
                columns.put(field.id(), builder); report.columns(builder);
            }
            TextColumnBuilder<?> labelTarget = labelTarget(definition, fields, columns, label);
            for (int level = 0; level < groups.size(); level++) {
                var field = groups.get(level);
                CustomGroupBuilder group = DynamicReports.grp.group("unidrGroup" + level, field.groupKeyAlias(), dataset.field(field.groupKeyAlias()).javaType())
                    .setHeaderLayout(GroupHeaderLayout.EMPTY).setPadding(0);
                report.groupBy(group);
                var footerCaption = new Caption(field.valueAlias(), field.caption(), level, definition.mode() == Mode.DETAILS ? "Итого: " : "");
                var footerStyle = rowStyle(definition.appearance(), groups, level, ordinals);
                DRIExpression<String> totalCaption = footerCaption;
                if (definition.mode() == Mode.DETAILS && labelTarget == null) {
                    // Keep the previous group's caption when the source has
                    // already advanced to the first row of the next group.
                    var previousCaption = DynamicReports.<String>variable(footerCaption, Calculation.NOTHING).setResetGroup(group);
                    report.variables(previousCaption);
                    totalCaption = previousCaption.getVariable();
                }
                var totals = totals(definition, fields, columns, labelTarget, totalCaption, footerStyle);
                if (definition.mode() == Mode.DETAILS) {
                    group.header(DynamicReports.cmp.text(new Caption(field.valueAlias(), field.caption(), level, ""))
                        .setStyle(DynamicReports.stl.style(base).bold()));
                    report.subtotalsAtGroupFooter(group, totals);
                } else report.subtotalsAtGroupHeader(group, totals);
            }
            if (definition.grandTotal() || definition.mode() == Mode.SUMMARY && groups.isEmpty()) {
                var grandStyle = cellStyle(definition.appearance()).bold();
                if (definition.appearance().stripeRows() && ordinals.size() % 2 == 1) grandStyle.setBackgroundColor(STRIPE);
                var grandCaption = new Caption(null, "", 0, definition.grandTotal() ? "Всего" : "Итог");
                report.subtotalsAtSummary(totals(definition, fields, columns, labelTarget, grandCaption, grandStyle));
            }
            DRDataSource data = new DRDataSource(Arrays.stream(dataset.fields()).map(QueryField::name).toArray(String[]::new));
            for (ReportRow row : dataset.rows())
                data.add(Arrays.stream(dataset.fields()).map(field -> row.value(field.name())).toArray());
            report.setDataSource(data);
            // DR defers every group-header subtotal to GROUP, including non-aggregate
            // captions. Evaluate these captions now so Jasper can measure/wrap them.
            if (label != null) {
                String captionKey = "subtotalGroupHeader.column_" + label.getColumn().getName() + ".subtotal";
                for (var group : report.toJasperDesign().getGroups())
                    for (var band : group.getGroupHeaderSection().getBands())
                        evaluateCaptionNow(band, captionKey);
            }
            JasperPrint print = report.toJasperPrint();
            // Reuse the UDR exporters while retaining numeric cells and compact rows in XLSX.
            print.setProperty(XlsReportConfiguration.PROPERTY_DETECT_CELL_TYPE, "true");
            print.setProperty(XlsReportConfiguration.PROPERTY_REMOVE_EMPTY_SPACE_BETWEEN_ROWS, "true");
            print.setProperty(XlsReportConfiguration.PROPERTY_WHITE_PAGE_BACKGROUND, "false");
            return print;
        } catch (ReportRenderException known) { throw known; }
        catch (Exception failed) { throw new ReportRenderException("Не удалось сформировать макет отчёта", failed); }
    }

    private int columnCount(UniDrDefinition definition) {
        Set<String> visible = new HashSet<>();
        if (definition.mode() == Mode.DETAILS) definition.columns().forEach(column -> visible.add(column.fieldId()));
        definition.measures().forEach(measure -> visible.add(measure.fieldId()));
        return visible.size() + (definition.mode() == Mode.SUMMARY ? (definition.groups().isEmpty() ? 0 : 1) : (definition.rowNumbers() ? 1 : 0));
    }
    private TextColumnBuilder<?> labelTarget(UniDrDefinition definition, Map<String, UniDrSourceDefinition.Field> fields,
            Map<String, TextColumnBuilder<?>> columns, TextColumnBuilder<?> label) {
        if (label != null || definition.mode() == Mode.SUMMARY) return label;
        // Prefer a descriptive cell; never replace a numeric value with a caption.
        for (ValueType type : List.of(ValueType.TEXT, ValueType.REFERENCE, ValueType.DATE))
            for (Column column : definition.columns())
                if (fields.get(column.fieldId()).type() == type && definition.measures().stream().noneMatch(measure -> measure.fieldId().equals(column.fieldId())))
                    return columns.get(column.fieldId());
        return definition.measures().isEmpty() ? columns.values().stream().findFirst().orElse(null) : null;
    }
    private void evaluateCaptionNow(JRElementGroup container, String key) {
        for (var element : container.getElements()) {
            if (element instanceof JRDesignTextField text && text.getKey() != null && text.getKey().startsWith(key))
                text.setEvaluationTime(EvaluationTimeEnum.NOW);
            if (element instanceof JRElementGroup nested) evaluateCaptionNow(nested, key);
        }
    }
    private StyleBuilder baseStyle() {
        return DynamicReports.stl.style().setFontName("DejaVu Sans").setFontSize(10)
            .setPdfEncoding("Identity-H").setPdfEmbedded(true).setMarkup(Markup.NONE).setPadding(4);
    }
    private StyleBuilder cellStyle(Appearance appearance) {
        var style = baseStyle(); var pen = DynamicReports.stl.penThin().setLineColor(new Color(0xC7, 0xD0, 0xDA));
        if (appearance.rowBorders()) style.setTopBorder(pen).setBottomBorder(pen);
        if (appearance.columnBorders()) style.setLeftBorder(pen).setRightBorder(pen);
        return style;
    }
    private StyleBuilder rowStyle(Appearance appearance, List<UniDrSourceDefinition.Field> groups, int level, Map<List<Object>, Integer> ordinals) {
        var style = cellStyle(appearance).bold();
        if (appearance.stripeRows()) style.conditionalStyles(DynamicReports.stl.conditionalStyle(new Stripe(groups, level, ordinals)).setBackgroundColor(STRIPE));
        return style;
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private TextColumnBuilder<?> column(UniDrSourceDefinition.Field field, String title, ReportDataset dataset,FieldStyle presentation) {
        if (field.type() == ValueType.DATE)
            return applyStyle(DynamicReports.col.column(title, new DateCaption(field.valueAlias(),presentation.format())).setWidth(90),presentation);
        TextColumnBuilder<?> column = DynamicReports.col.column(title, field.valueAlias(), (Class) dataset.field(field.valueAlias()).javaType());
        column.setWidth(field.type() == ValueType.DECIMAL ? 100 : 145);
        column.setTextAdjust(TextAdjust.STRETCH_HEIGHT);
        if (field.type() == ValueType.DECIMAL) column.setPattern("#,##0.00");
        if (field.type() == ValueType.DECIMAL) column.setHorizontalTextAlignment(HorizontalTextAlignment.RIGHT);
        return applyStyle(column,presentation);
    }
    private <T> TextColumnBuilder<T> applyStyle(TextColumnBuilder<T> column,FieldStyle presentation) {
        if(presentation.border()!=null) {
            column.setStyle(border(DynamicReports.stl.style(),presentation));
            column.setTitleStyle(border(DynamicReports.stl.style().bold().setBackgroundColor(HEADER),presentation));
        }
        if(presentation.width()!=null)column.setWidth(presentation.width());
        if(presentation.format()!=null)column.setPattern(presentation.format());
        if(presentation.alignment()!=Alignment.AUTO)column.setHorizontalTextAlignment(alignment(presentation.alignment()));
        return column;
    }
    private StyleBuilder border(StyleBuilder style,FieldStyle presentation) {
        if(presentation.border()!=null)style.setBorder(presentation.border()?DynamicReports.stl.penThin().setLineColor(new Color(0xC7,0xD0,0xDA)):DynamicReports.stl.pen().setLineWidth(0f));
        return style;
    }
    private HorizontalTextAlignment alignment(Alignment alignment) {
        return switch(alignment){case LEFT->HorizontalTextAlignment.LEFT;case CENTER->HorizontalTextAlignment.CENTER;case RIGHT,AUTO->HorizontalTextAlignment.RIGHT;};
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private SubtotalBuilder<?, ?>[] totals(UniDrDefinition definition, Map<String, UniDrSourceDefinition.Field> fields,
            Map<String, TextColumnBuilder<?>> columns, TextColumnBuilder<?> label, DRIExpression<String> caption, StyleBuilder style) {
        List<SubtotalBuilder<?, ?>> result = new ArrayList<>();
        Set<TextColumnBuilder<?>> occupied = new HashSet<>();
        TextColumnBuilder<?> labelTarget = label;
        if (labelTarget != null) {
            var target=labelTarget;
            var labelStyle=columns.entrySet().stream().filter(entry->entry.getValue()==target).findFirst()
                .map(entry->border(DynamicReports.stl.style(style),definition.fieldStyles().getOrDefault(entry.getKey(),FieldStyle.defaults()))).orElse(style);
            // A footer must retain the previous group's caption: at the break the
            // current source fields already belong to the next group.
            SubtotalBuilder<?, ?> subtotal = definition.mode() == Mode.DETAILS
                ? DynamicReports.sbt.aggregate(caption, labelTarget, Calculation.NOTHING).setStyle(labelStyle)
                : DynamicReports.sbt.customValue(caption, labelTarget).setStyle(labelStyle);
            subtotal.setDataType(DynamicReports.type.stringType());
            result.add(subtotal);
            occupied.add(labelTarget);
        }
        for (Measure measure : definition.measures()) {
            SubtotalBuilder total = measure.aggregation() == Aggregation.COUNT_ROWS
                ? DynamicReports.sbt.count(DynamicReports.exp.value(1), columns.get(measure.fieldId())).setPattern("#,##0")
                : DynamicReports.sbt.aggregate(fields.get(measure.fieldId()).valueAlias(), java.math.BigDecimal.class,
                    columns.get(measure.fieldId()), switch (measure.aggregation()) {
                        case SUM -> Calculation.SUM; case AVG -> Calculation.AVERAGE;
                        case MIN -> Calculation.LOWEST; case MAX -> Calculation.HIGHEST;
                        default -> throw new IllegalArgumentException("Неподдерживаемая агрегация");
                    }).setPattern("#,##0.00");
            var presentation=definition.fieldStyles().getOrDefault(measure.fieldId(),FieldStyle.defaults());
            if(presentation.format()!=null)total.setPattern(presentation.format());
            total.setStyle(border(DynamicReports.stl.style(style).setHorizontalTextAlignment(alignment(presentation.alignment())),presentation)); result.add(total);
            occupied.add(columns.get(measure.fieldId()));
        }
        // The caption belongs to the total cell, not to an empty column extending
        // through every detail row. Native subtotal labels preserve typed numbers.
        if (labelTarget == null && !result.isEmpty()) {
            var presentation = definition.fieldStyles().getOrDefault(definition.measures().getFirst().fieldId(), FieldStyle.defaults());
            result.getFirst().setLabel(caption).setLabelPosition(Position.LEFT)
                .setLabelStyle(border(DynamicReports.stl.style(style).setHorizontalTextAlignment(HorizontalTextAlignment.LEFT), presentation));
        }
        // Empty subtotal cells retain the same grid/background as the label and values.
        for (var entry : columns.entrySet()) if (!occupied.contains(entry.getValue()))
            result.add(DynamicReports.sbt.aggregate(DynamicReports.exp.value(""), entry.getValue(), Calculation.NOTHING)
                .setDataType(DynamicReports.type.stringType()).setStyle(border(DynamicReports.stl.style(style),definition.fieldStyles().getOrDefault(entry.getKey(),FieldStyle.defaults()))));
        // A details recipe may intentionally have no measures; group footers still carry their label.
        return result.toArray(SubtotalBuilder[]::new);
    }
    private String caption(String custom, String standard) { return custom == null ? standard : custom; }
    private String measureCaption(UniDrSourceDefinition.Field field, Aggregation aggregation) {
        return aggregation == Aggregation.COUNT_ROWS ? field.caption() : field.caption() + " · " + switch (aggregation) {
            case SUM -> "Сумма"; case AVG -> "Среднее"; case MIN -> "Минимум"; case MAX -> "Максимум"; default -> throw new IllegalArgumentException();
        };
    }
    private void verifyFields(UniDrDefinition definition, ReportDataset dataset, Map<String, UniDrSourceDefinition.Field> fields) {
        Set<String> required = new HashSet<>();
        definition.columns().forEach(column -> required.add(fields.get(column.fieldId()).valueAlias()));
        definition.measures().stream().filter(measure -> measure.aggregation() != Aggregation.COUNT_ROWS).forEach(measure -> required.add(fields.get(measure.fieldId()).valueAlias()));
        definition.groups().forEach(id -> { required.add(fields.get(id).groupKeyAlias()); required.add(fields.get(id).valueAlias()); });
        for (String alias : required) {
            var field = dataset.field(alias);
            if (field.javaType() != String.class && field.javaType() != Long.class && field.javaType() != LocalDate.class && field.javaType() != java.math.BigDecimal.class)
                throw new ReportRenderException("Источник содержит неподдерживаемый тип поля");
        }
        for (String id : definition.groups()) {
            var field = fields.get(id);
            Class<?> expected = switch(field.type()){case DATE->LocalDate.class;case DECIMAL->java.math.BigDecimal.class;case TEXT->String.class;default->Long.class;};
            if (dataset.field(field.groupKeyAlias()).javaType() != expected) throw new ReportRenderException("Тип ключа группы изменился");
        }
        for (Measure measure : definition.measures()) if (measure.aggregation() != Aggregation.COUNT_ROWS && dataset.field(fields.get(measure.fieldId()).valueAlias()).javaType() != java.math.BigDecimal.class)
            throw new ReportRenderException("Количество должно иметь тип BigDecimal");
    }
    private Map<List<Object>, Integer> groupOrdinals(ReportDataset data, List<UniDrSourceDefinition.Field> groups) {
        Map<List<Object>, Integer> result = new LinkedHashMap<>();
        List<List<Object>> previous = new ArrayList<>(Collections.nCopies(groups.size(), null));
        for (ReportRow row : data.rows()) for (int level = 0; level < groups.size(); level++) {
            List<Object> key = new ArrayList<>();
            for (int index = 0; index <= level; index++) key.add(row.value(groups.get(index).groupKeyAlias()));
            if (!Objects.equals(previous.get(level), key)) {
                if (result.containsKey(key)) throw new ReportRenderException("Нарушен порядок групп источника; формирование остановлено");
                result.put(key, result.size()); previous.set(level, key);
            }
        }
        return result;
    }
    private static final class Caption extends AbstractSimpleExpression<String> {
        private final String alias, fieldCaption, prefix;
        private final int level;
        Caption(String alias, String fieldCaption, int level, String prefix) { this.alias = alias; this.fieldCaption = fieldCaption; this.level = level; this.prefix = prefix; }
        @Override public String evaluate(ReportParameters parameters) {
            if (alias == null) return prefix;
            Object value = parameters.getFieldValue(alias);
            String text = value instanceof LocalDate date ? DATE.format(date) : Objects.toString(value, "Не заполнено");
            return "    ".repeat(level) + prefix + (prefix.isEmpty() ? fieldCaption + ": " : "") + text;
        }
    }
    private static final class DateCaption extends AbstractSimpleExpression<String> {
        private final String alias,pattern;
        DateCaption(String alias,String pattern) { this.alias = alias;this.pattern=pattern; }
        @Override public String evaluate(ReportParameters parameters) { LocalDate date = parameters.getFieldValue(alias); return date == null ? "" : (pattern==null?DATE:DateTimeFormatter.ofPattern(pattern,Locale.forLanguageTag("ru-RU"))).format(date); }
    }
    private static final class Stripe extends AbstractSimpleExpression<Boolean> {
        private final List<String> aliases;
        private final Map<List<Object>, Integer> ordinals;
        Stripe(List<UniDrSourceDefinition.Field> groups, int level, Map<List<Object>, Integer> ordinals) {
            aliases = groups.subList(0, level + 1).stream().map(UniDrSourceDefinition.Field::groupKeyAlias).toList(); this.ordinals = ordinals;
        }
        @Override public Boolean evaluate(ReportParameters parameters) {
            List<Object> key = new ArrayList<>(); aliases.forEach(alias -> key.add(parameters.getFieldValue(alias)));
            return ordinals.getOrDefault(key, 0) % 2 == 1;
        }
    }
}

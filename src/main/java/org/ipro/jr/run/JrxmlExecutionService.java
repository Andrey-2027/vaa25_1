package org.ipro.jr.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.jr.service.JrxmlTemplateService;
import org.springframework.transaction.annotation.Transactional;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRElement;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRGroup;
import net.sf.jasperreports.engine.JRSection;
import net.sf.jasperreports.engine.JRSubreport;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;

/**
 * Компиляция и выполнение .jrxml-шаблонов (движок JR каталога).
 *
 * <p>Источник данных — JPQL после маркера {@code jpql:} в queryString:
 * выполняется через {@link JpqlRunService} (guard + RLS + лимиты) под текущим
 * пользователем, строки передаются движку JR как {@link JRMapCollectionDataSource}.
 * Имена полей jrxml = алиасы SELECT с заменой {@code .} на {@code _}
 * (конвенция UReport, Р3 плана).</p>
 *
 * <p>Граница V1: один датасет на отчёт, без суб-репортов/вложенных датасетов
 * с отдельным queryString (план ReportJR-Jpql-Plan.md, принцип 4) — нарушение
 * даёт явный отказ до fill, а не падение посреди выполнения.</p>
 */
public class JrxmlExecutionService {

    /** Маркер JPQL-источника в начале queryString. */
    public static final String JPQL_MARKER = "jpql:";

    static {
        // Самодостаточность движка JR по шрифтам: не зависим от того, выполнялся ли
        // статический блок JasperReportCompiler (UDR). DejaVu покрывает кириллицу,
        // встраивание сабсета в PDF обязательно (см. ReportJR-Jpql-Plan.md, Р6).
        System.setProperty("net.sf.jasperreports.default.fontname", "DejaVu Sans");
        System.setProperty("net.sf.jasperreports.default.fontsize", "10");
        System.setProperty("net.sf.jasperreports.pdf.embedded", "true");
    }

    private final JrxmlTemplateService templateService;
    private final JpqlDatasetRunner jpqlDatasetRunner;

    /** Расширенный лимит колонок JR-отчётов (Ф1.2 плана; pixel-perfect шире ad-hoc). */
    private final int maxColumns;

    /** Кэш по fileName: правка файла в Jaspersoft Studio инвалидирует запись по mtime. */
    private final Map<String, CompiledTemplate> compileCache = new ConcurrentHashMap<>();

    private record CompiledTemplate(long modifiedAt, JasperReport report) {
    }

    public JrxmlExecutionService(JrxmlTemplateService templateService,
                                 JpqlDatasetRunner jpqlDatasetRunner, int maxColumns) {
        this.templateService = templateService;
        this.jpqlDatasetRunner = jpqlDatasetRunner;
        this.maxColumns = maxColumns;
    }

    /**
     * Компилирует (с кэшем по mtime) и заполняет шаблон.
     *
     * @param parameters значения параметров jrxml по именам ($P{}), скалярные типы
     */
    @Transactional(readOnly = true)
    public JasperPrint run(JrxmlTemplate template, Map<String, Object> parameters) {
        if (!template.isEnabled()) {
            throw new IllegalStateException("Отчёт отключён: " + template.getName());
        }
        String fileName = template.getFileName();
        if (!templateService.fileExists(fileName)) {
            throw new IllegalStateException("Файл шаблона отсутствует: " + fileName);
        }
        JasperReport report = compiledReport(fileName);
        checkSingleDatasetV1(report);

        Map<String, Object> safeParams = parameters == null ? Map.of() : parameters;
        try {
            Map<String, Object> fillParams = new HashMap<>(safeParams);
            fillParams.put("REPORT_DATA_SOURCE", dataSourceOf(report, safeParams));
            return JasperFillManager.fillReport(report, fillParams);
        } catch (JRException e) {
            throw new IllegalStateException(
                    "Не удалось выполнить отчёт JR «" + template.getName() + "»: "
                            + rootMessage(e), e);
        } catch (RuntimeException pipelineError) {
            throw new IllegalStateException(
                    "Отчёт JR «" + template.getName() + "\": "
                            + pipelineError.getMessage(), pipelineError);
        }
    }

    /**
     * Источник данных по тексту запроса. {@code jpql:} — выполнение через конвейер
     * guard+RLS; без маркера — отказ: пользовательские SQL-запросы не проходят RLS,
     * поэтому сознательно запрещены (план, принцип 1).
     */
    private JRDataSource dataSourceOf(JasperReport report, Map<String, Object> parameters)
            throws JRException {
        String queryText = report.getQuery() == null ? null : report.getQuery().getText();
        String jpql = extractJpql(queryText);
        if (jpql == null) {
            throw new IllegalStateException("Шаблон не содержит источник \"jpql:\" — "
                    + "выполнение SQL мимо конвейера guard/RLS запрещено. Укажите язык jpql "
                    + "и текст запроса после маркера \"jpql:\".");
        }
        var dataset = jpqlDatasetRunner.run(jpql, parameters, maxColumns);
        var fields = dataset.fields();
        Collection<Map<String, ?>> rows = new ArrayList<>();
        for (var row : dataset.rows()) {
            Map<String, Object> map = new HashMap<>(fields.length * 2);
            for (int i = 0; i < fields.length; i++) {
                // Р3: точки в алиасах заменяются на '_' — имена полей jrxml должны совпадать
                map.put(fields[i].name().replace('.', '_'),
                        JpqlDatasetRunner.displayValue(row.value(i)));
            }
            rows.add(map);
        }
        return new JRMapCollectionDataSource(rows);
    }

    /** Текст после маркера {@code jpql:} или null, если маркера нет. */
    static String extractJpql(String queryText) {
        if (queryText == null) {
            return null;
        }
        String trimmed = queryText.strip();
        if (!trimmed.toLowerCase().startsWith(JPQL_MARKER)) {
            return null;
        }
        return trimmed.substring(JPQL_MARKER.length()).strip();
    }

    /**
     * Спецификация пользовательского параметра jrxml для формы запуска.
     *
     * @param defaultValueLiteral текст defaultValueExpression без обрамляющих кавычек
     *                            (только литералы; выражения — null)
     */
    public record JrxmlParamSpec(String name, String valueClassName, String defaultValueLiteral) {
    }

    /**
     * Пользовательские параметры шаблона для диалога запуска: системные
     * ({@code isSystemDefined}) отфильтрованы; литеральные значения по умолчанию
     * распознаны ('строка', число, true/false). Компиляция — через общий кэш.
     */
    @Transactional(readOnly = true)
    public List<JrxmlParamSpec> parameterSpecs(JrxmlTemplate template) {
        JasperReport report = compiledReport(template.getFileName());
        List<JrxmlParamSpec> specs = new ArrayList<>();
        for (net.sf.jasperreports.engine.JRParameter parameter : report.getParameters()) {
            if (parameter.isSystemDefined()) {
                continue;
            }
            specs.add(new JrxmlParamSpec(parameter.getName(), parameter.getValueClassName(),
                    literalOf(parameter.getDefaultValueExpression())));
        }
        return specs;
    }

    private static String literalOf(net.sf.jasperreports.engine.JRExpression expression) {
        if (expression == null || expression.getText() == null) {
            return null;
        }
        String text = expression.getText().strip();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text.matches("\\d+(\\.\\d+)?|true|false") ? text : null;
    }

    /** Граница V1: один главный датасет, без вложенных датасетов/суб-репортов. */
    void checkSingleDatasetV1(JasperReport report) {
        if (report.getDatasets() != null && report.getDatasets().length > 0) {
            throw new IllegalStateException("В V1 поддерживается один датасет на отчёт: "
                    + "уберите subDataset'ы (найдено " + report.getDatasets().length + ")");
        }
        for (var band : allBands(report)) {
            for (JRElement element : band.getElements()) {
                if (element instanceof JRSubreport) {
                    throw new IllegalStateException(
                            "В V1 суб-репорты не поддерживаются: удалите subreport из макета");
                }
            }
        }
    }

    private List<net.sf.jasperreports.engine.JRBand> allBands(JasperReport report) {
        List<net.sf.jasperreports.engine.JRBand> bands = new ArrayList<>();
        addBand(bands, report.getTitle());
        addBand(bands, report.getPageHeader());
        addBand(bands, report.getColumnHeader());
        addSection(bands, report.getDetailSection());
        addBand(bands, report.getColumnFooter());
        addBand(bands, report.getPageFooter());
        addBand(bands, report.getSummary());
        if (report.getGroups() != null) {
            for (JRGroup group : report.getGroups()) {
                addSection(bands, group.getGroupHeaderSection());
                addSection(bands, group.getGroupFooterSection());
            }
        }
        return bands;
    }

    private void addSection(List<net.sf.jasperreports.engine.JRBand> bands, JRSection section) {
        if (section == null) {
            return;
        }
        for (net.sf.jasperreports.engine.JRBand band : section.getBands()) {
            addBand(bands, band);
        }
    }

    private void addBand(List<net.sf.jasperreports.engine.JRBand> bands,
                         net.sf.jasperreports.engine.JRBand band) {
        if (band != null) {
            bands.add(band);
        }
    }

    private JasperReport compiledReport(String fileName) {
        Path path = templateService.resolve(fileName);
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            CompiledTemplate cached = compileCache.get(fileName);
            if (cached != null && cached.modifiedAt() == mtime) {
                return cached.report();
            }
            synchronized (compileCache) {
                cached = compileCache.get(fileName);
                if (cached != null && cached.modifiedAt() == mtime) {
                    return cached.report();
                }
                try (var in = Files.newInputStream(path)) {
                    JasperReport report = JasperCompileManager.compileReport(in);
                    compileCache.put(fileName, new CompiledTemplate(mtime, report));
                    return report;
                }
            }
        } catch (Exception e) {
            compileCache.remove(fileName);
            throw new IllegalStateException(
                    "Не удалось скомпилировать шаблон JR: " + fileName + " — " + rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName()
                : cause.getMessage();
    }
}

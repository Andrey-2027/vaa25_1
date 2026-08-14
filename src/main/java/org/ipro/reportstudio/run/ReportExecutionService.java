package org.ipro.reportstudio.run;

import net.sf.jasperreports.engine.JasperPrint;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.param.ReportParamResolver;
import org.ipro.reportstudio.param.ResolvedParams;
import org.ipro.reportstudio.query.GuardResult;
import org.ipro.reportstudio.query.OrderByApplier;
import org.ipro.reportstudio.query.ReportQueryExecutor;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.render.ReportCompiler;
import org.ipro.reportstudio.render.ReportExportFormat;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pipeline запуска отчёта (Фаза 4): guard → resolve параметров → выполнение
 * запроса (с авто ORDER BY групповых полей — иначе соседние группы склеиваются)
 * → компиляция в JasperPrint → run-кэш артефакта (render once / export many).
 * Читающий транзакционный контекст: данные уже в JasperPrint — к моменту
 * экспорта транзакция не нужна.
 */
public class ReportExecutionService {

    private final ReportQueryGuard guard;
    private final ReportQueryExecutor executor;
    private final ReportParamResolver resolver;
    private final ReportCompiler compiler;
    private final ReportArtifactCache cache;

    public ReportExecutionService(ReportQueryGuard guard, ReportQueryExecutor executor,
                                  ReportParamResolver resolver, ReportCompiler compiler,
                                  ReportArtifactCache cache) {
        this.guard = guard;
        this.executor = executor;
        this.resolver = resolver;
        this.compiler = compiler;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public ReportRunResult run(ReportTemplate template, ReportContext context,
                               Map<String, Object> formValues, String localeTag, String zoneId) {
        Set<String> paramNames = ReportQueryGuard.parameterNamesOf(template.getParams());
        GuardResult guardResult = guard.guard(template.getJpql(), paramNames);
        if (!guardResult.allowed()) {
            throw new ReportRunException("Отказ: " + String.join("; ", guardResult.errors()));
        }

        ResolvedParams params = resolver.resolve(template.getParams(), context, formValues);
        if (!params.ok()) {
            throw new ReportRunException("Не удалось заполнить параметры: "
                + String.join("; ", params.errors()));
        }

        String jpql = OrderByApplier.withGroupOrderBy(template.getJpql(), groupFieldsOf(template));
        ReportDataset dataset = executor.execute(jpql, params.bindings(),
            guardResult.selectFields(),
            template.getMaxRows() > 0 ? template.getMaxRows() : ReportTemplate.DEFAULT_MAX_ROWS,
            template.getTimeoutMs() > 0 ? template.getTimeoutMs() : ReportTemplate.DEFAULT_TIMEOUT_MS);

        String key = ReportArtifactCache.key(template, params, context, localeTag, zoneId);
        JasperPrint print = compiler.compile(template, dataset);
        cache.put(key, print);
        return new ReportRunResult(print, key);
    }

    public byte[] export(ReportRunResult result, ReportExportFormat format) {
        return compiler.export(result.print(), format);
    }

    public Optional<JasperPrint> cached(String key) {
        return cache.get(key);
    }

    /**
     * Групповые поля в порядке DR-групп (внешняя группа первой, вложенные после,
     * по position) — должен совпадать с порядком groupBy в компиляторе.
     */
    static List<String> groupFieldsOf(ReportTemplate template) {
        List<ReportBand> headers = new ArrayList<>();
        for (ReportBand band : template.getBands()) {
            if (band.getKind() == ReportBandKind.GROUP_HEADER && band.getGroupField() != null
                    && !band.getGroupField().isBlank()) {
                headers.add(band);
            }
        }
        List<ReportBand> ordered = new ArrayList<>(headers.size());
        for (ReportBand root : sortedOf(headers, b -> b.getParent() == null)) {
            walk(root, headers, ordered);
        }
        for (ReportBand leftover : sortedOf(headers, b -> !ordered.contains(b))) {
            walk(leftover, headers, ordered);
        }
        return ordered.stream().map(ReportBand::getGroupField).toList();
    }

    private static void walk(ReportBand band, List<ReportBand> all, List<ReportBand> out) {
        if (out.contains(band)) {
            return;
        }
        out.add(band);
        for (ReportBand child : sortedOf(all, b -> b.getParent() == band)) {
            walk(child, all, out);
        }
    }

    private static List<ReportBand> sortedOf(List<ReportBand> bands,
            java.util.function.Predicate<ReportBand> filter) {
        return bands.stream()
            .filter(filter)
            .sorted(Comparator.comparingInt(ReportBand::getPosition))
            .toList();
    }
}

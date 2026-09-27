package org.ipro.ureport.catalog;

import java.util.Objects;

import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.ipro.ureport.dom.UreportTemplate;
import org.ipro.ureport.service.UreportTemplateService;

/**
 * Единственный сборочный узел строки каталога (D3.6): движок → {@link ReportCatalogItem}.
 *
 * <p>Сборка одной и той же записи раньше жила в шести местах: три приватных
 * метода {@link ReportCatalogService}, анонимные бриджи обоих variant-каталогов и
 * {@code ContextualReportLauncher}. Из-за этого копия или импортированная запись
 * собиралась не тем же кодом, что запись из списка — и каталог после операции
 * читался второй раз, чтобы «найти» строку, которую только что вернул сервис.</p>
 *
 * <p>Правила сборки, которые обязаны быть в одном месте:</p>
 * <ul>
 *   <li>«включён» для UDR = {@link ReportTemplateState#PUBLISHED} (черновик — не
 *       включён), хотя у UReport3/JR это собственный флаг enabled;</li>
 *   <li>UReport3 несёт URL веб-дизайнера, остальные — {@code null};</li>
 *   <li>{@code fileMissing} — XML/макет отсутствует в хранилище.</li>
 * </ul>
 *
 * <p>{@code fileMissing} передаёт вызывающий: он выводится только из сервиса
 * ({@code fileExists}), и фабрика намеренно не держит состояния — это чистые
 * функции без зависимостей.</p>
 */
public final class ReportCatalogItemFactory {

    /** Строка UDR-отчёта. */
    public static ReportCatalogItem of(ReportTemplate template) {
        Objects.requireNonNull(template, "template");
        return new ReportCatalogItem(
                template.getId(),
                ReportEngineType.UDR,
                template.getName(),
                template.getDescription(),
                template.getState() == ReportTemplateState.PUBLISHED,
                null,
                false);
    }

    /**
     * Строка UReport3-отчёта.
     *
     * @param fileMissing XML-файл шаблона отсутствует в хранилище
     */
    public static ReportCatalogItem of(UreportTemplate template, boolean fileMissing) {
        Objects.requireNonNull(template, "template");
        return new ReportCatalogItem(
                template.getId(),
                ReportEngineType.UREPORT3,
                template.getName(),
                template.getDescription(),
                template.isEnabled(),
                UreportTemplateService.designerUrl(template.getFileName()),
                fileMissing);
    }

    /**
     * Строка JR-отчёта.
     *
     * @param fileMissing .jrxml-макет отсутствует в хранилище
     */
    public static ReportCatalogItem of(JrxmlTemplate template, boolean fileMissing) {
        Objects.requireNonNull(template, "template");
        return new ReportCatalogItem(
                template.getId(),
                ReportEngineType.JR,
                template.getName(),
                template.getDescription(),
                template.isEnabled(),
                null,
                fileMissing);
    }

    private ReportCatalogItemFactory() {
    }
}

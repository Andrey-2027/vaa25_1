package org.ipro.jr.service;

import java.util.List;

import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.report.catalog.ReportCatalogContributor;
import org.ipro.report.catalog.ReportCatalogItem;
import org.ipro.report.catalog.ReportCatalogItemFactory;
import org.ipro.report.catalog.ReportCatalogReadGate;
import org.ipro.report.catalog.ReportEngineType;

/**
 * Адаптер движка JR (.jrxml) в общий каталог отчётов.
 *
 * <p>Правила ветки каталога сохранены: проверка чтения — по типу
 * {@link JrxmlTemplate} и до поиска (пустой список не требует аутентификации),
 * «включён» — собственный флаг шаблона, отсутствие макета в хранилище
 * отражается признаком {@code fileMissing}.</p>
 */
public class JrCatalogContributor implements ReportCatalogContributor {

    private final JrxmlTemplateService templateService;
    private final ReportCatalogReadGate readGate;

    public JrCatalogContributor(JrxmlTemplateService templateService,
                                ReportCatalogReadGate readGate) {
        this.templateService = templateService;
        this.readGate = readGate;
    }

    @Override
    public ReportEngineType type() {
        return ReportEngineType.JR;
    }

    @Override
    public List<ReportCatalogItem> findAll(String term, boolean includeDisabled) {
        if (!readGate.canRead(JrxmlTemplate.class)) {
            return List.of();
        }
        return templateService.search(term).stream()
                .filter(template -> includeDisabled || template.isEnabled())
                .map(this::item)
                .toList();
    }

    @Override
    public ReportCatalogItem find(Long id) {
        return item(templateService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Шаблон JR не найден: " + id)));
    }

    private ReportCatalogItem item(JrxmlTemplate template) {
        // Правило движка (включённость и fileMissing) остаётся здесь, а не в
        // нейтральной фабрике: каталог не знает JrxmlTemplate.
        return ReportCatalogItemFactory.of(template.getId(), ReportEngineType.JR,
                template.getName(), template.getDescription(), template.isEnabled(),
                !templateService.fileExists(template.getFileName()));
    }
}

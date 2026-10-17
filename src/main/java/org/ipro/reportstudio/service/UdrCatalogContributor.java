package org.ipro.reportstudio.service;

import java.util.ArrayList;
import java.util.List;

import org.ipro.report.catalog.ReportCatalogContributor;
import org.ipro.report.catalog.ReportCatalogItem;
import org.ipro.report.catalog.ReportCatalogItemFactory;
import org.ipro.report.catalog.ReportEngineType;
import org.ipro.reportstudio.dom.ReportTemplate;

/**
 * Адаптер конструктора UDR в общий каталог отчётов.
 *
 * <p>Строку собирает {@link ReportCatalogItemFactory} — единственный узел
 * сборки (D3.6); здесь остаются только правила выборки движка: поиск по
 * термину и фильтр «включён», которым для UDR служит состояние публикации
 * (черновик не включён).</p>
 *
 * <p>RLS-гейт каталога к UDR не применяется намеренно: видимость шаблонов
 * задаёт сам {@link ReportTemplateService} своим scope, и добавление второй
 * проверки здесь изменило бы поведение каталога.</p>
 */
public class UdrCatalogContributor implements ReportCatalogContributor {

    private final ReportTemplateService templateService;

    public UdrCatalogContributor(ReportTemplateService templateService) {
        this.templateService = templateService;
    }

    @Override
    public ReportEngineType type() {
        return ReportEngineType.UDR;
    }

    @Override
    public List<ReportCatalogItem> findAll(String term, boolean includeDisabled) {
        List<ReportCatalogItem> items = new ArrayList<>();
        for (ReportTemplate template : templateService.search(term)) {
            ReportCatalogItem item = ReportCatalogItemFactory.of(template);
            if (!includeDisabled && !item.enabled()) {
                continue;
            }
            items.add(item);
        }
        return items;
    }

    @Override
    public ReportCatalogItem find(Long id) {
        return ReportCatalogItemFactory.of(templateService.loadTemplate(id));
    }
}

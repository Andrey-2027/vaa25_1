package org.ipro.ureport.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.ureport.dom.UreportTemplate;
import org.ipro.ureport.service.UreportTemplateService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;
import org.springframework.transaction.annotation.Transactional;

public class ReportCatalogService {

    private final ReportTemplateService reportTemplateService;
    private final UreportTemplateService ureportTemplateService;
    private final RlsReadGate rlsReadGate;
    private final RlsCurrentUser currentUser;

    public ReportCatalogService(ReportTemplateService reportTemplateService,
                                UreportTemplateService ureportTemplateService,
                                RlsReadGate rlsReadGate,
                                RlsCurrentUser currentUser) {
        this.reportTemplateService = reportTemplateService;
        this.ureportTemplateService = ureportTemplateService;
        this.rlsReadGate = rlsReadGate;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ReportCatalogItem> findAll(String term, boolean includeDisabled) {
        List<ReportCatalogItem> result = new ArrayList<>();
        result.addAll(udrItems(term, includeDisabled));
        result.addAll(ureportItems(term, includeDisabled));
        result.sort(Comparator.comparing(ReportCatalogItem::name,
                String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    @Transactional(readOnly = true)
    public ReportCatalogItem find(ReportEngineType type, Long id) {
        return switch (type) {
            case UDR -> udrItem(reportTemplateService.loadTemplate(id));
            case UREPORT3 -> ureportItem(ureportTemplateService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Шаблон UReport не найден: " + id)));
        };
    }

    private List<ReportCatalogItem> udrItems(String term, boolean includeDisabled) {
        List<ReportCatalogItem> items = new ArrayList<>();
        for (ReportTemplate template : reportTemplateService.search(term)) {
            ReportCatalogItem item = udrItem(template);
            if (!includeDisabled && !item.enabled()) {
                continue;
            }
            items.add(item);
        }
        return items;
    }

    private List<ReportCatalogItem> ureportItems(String term, boolean includeDisabled) {
        List<ReportCatalogItem> items = new ArrayList<>();
        for (UreportTemplate template : ureportTemplateService.search(term)) {
            if (!includeDisabled && !template.isEnabled()) {
                continue;
            }
            if (!rlsReadGate.canRead(UreportTemplate.class, currentUser.username())) {
                continue;
            }
            items.add(ureportItem(template));
        }
        return items;
    }

    private ReportCatalogItem udrItem(ReportTemplate template) {
        // "включён" для UDR = опубликован (DRAFT/PUBLISHED - модель состояний reportstudio)
        boolean enabled = template.getState() == org.ipro.reportstudio.dom.ReportTemplateState.PUBLISHED;
        return new ReportCatalogItem(template.getId(), ReportEngineType.UDR,
                template.getName(), template.getDescription(), enabled,
                null, false);
    }

    private ReportCatalogItem ureportItem(UreportTemplate template) {
        return new ReportCatalogItem(template.getId(), ReportEngineType.UREPORT3,
                template.getName(), template.getDescription(), template.isEnabled(),
                UreportTemplateService.designerUrl(template.getFileName()),
                !ureportTemplateService.fileExists(template.getFileName()));
    }

    private static boolean matches(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    @SuppressWarnings("unused")
    private static boolean containsIgnoreCase(String value, String needle) {
        return matches(value, needle);
    }
}

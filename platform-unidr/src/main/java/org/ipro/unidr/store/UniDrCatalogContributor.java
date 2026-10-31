package org.ipro.unidr.store;

import org.ipro.report.catalog.ReportCatalogContributor;
import org.ipro.report.catalog.ReportCatalogItem;
import org.ipro.report.catalog.ReportCatalogItemFactory;
import org.ipro.report.catalog.ReportEngineType;
import java.util.List;

public final class UniDrCatalogContributor implements ReportCatalogContributor {
    private final UniDrFileStore store;
    public UniDrCatalogContributor(UniDrFileStore store) { this.store = store; }
    @Override public ReportEngineType type() { return ReportEngineType.UNIDR; }
    @Override public List<ReportCatalogItem> findAll(String term, boolean includeDisabled) {
        try { return store.list(term).stream().map(this::item).toList(); }
        catch (org.ipro.unidr.run.UniDrRunException denied) { return List.of(); }
    }
    @Override public ReportCatalogItem find(Long id) {
        return store.list(null).stream().filter(entry -> entry.id() == id).findFirst().map(this::item)
            .orElseThrow(() -> new UniDrFileStore.StoreException("Отчёт UniDR не найден"));
    }
    private ReportCatalogItem item(UniDrFileStore.Entry entry) {
        return ReportCatalogItemFactory.ofUniDr(entry.id(), entry.name(), entry.description());
    }
}

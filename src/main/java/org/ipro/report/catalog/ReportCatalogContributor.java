package org.ipro.report.catalog;

import java.util.List;

/** A report engine with its own storage, contributing rows to the common catalog. */
public interface ReportCatalogContributor {
    ReportEngineType type();
    List<ReportCatalogItem> findAll(String term, boolean includeDisabled);
    ReportCatalogItem find(Long id);
}

package org.ipro.report.ui.unidr;

import org.ipro.unidr.store.UniDrFileStore;

import java.util.List;

/**
 * Optional UniDR catalogue at the report UI seam.
 *
 * <p>The report module never creates a file store and never decides whether UniDR is
 * enabled. The consuming application supplies an adapter over its configured store only
 * when the pilot is on; without the bean the UniDR rows and actions simply do not appear.</p>
 */
public interface UniDrCatalogGateway {

    /** Reports explicitly bound to the given application entity type. */
    List<UniDrFileStore.Entry> findPrintable(String entityClassName);

    UniDrFileStore.Saved load(long id);

    UniDrFileStore.Saved copy(long id, String title);

    String exportJson(long id);

    UniDrFileStore.ImportPreview inspectImport(String json);

    UniDrFileStore.Saved importJson(String json, String title);

    void delete(long id, String revision);

    String currentUser();
}

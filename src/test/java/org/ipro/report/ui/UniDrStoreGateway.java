package org.ipro.report.ui;

import org.ipro.report.ui.unidr.UniDrCatalogGateway;
import org.ipro.unidr.store.UniDrFileStore;

import java.util.List;

/** Test-only bridge: the port is the production seam, the store is its real implementation. */
public final class UniDrStoreGateway implements UniDrCatalogGateway {

    private final UniDrFileStore store;

    public UniDrStoreGateway(UniDrFileStore store) {
        this.store = store;
    }

    @Override public List<UniDrFileStore.Entry> findPrintable(String entityClassName) {
        return store.findPrintable(entityClassName);
    }

    @Override public UniDrFileStore.Saved load(long id) { return store.load(id); }

    @Override public UniDrFileStore.Saved copy(long id, String title) { return store.copy(id, title); }

    @Override public String exportJson(long id) { return store.exportJson(id); }

    @Override public UniDrFileStore.ImportPreview inspectImport(String json) {
        return store.inspectImport(json);
    }

    @Override public UniDrFileStore.Saved importJson(String json, String title) {
        return store.importJson(json, title);
    }

    @Override public void delete(long id, String revision) { store.delete(id, revision); }

    @Override public String currentUser() { return store.currentUser(); }
}

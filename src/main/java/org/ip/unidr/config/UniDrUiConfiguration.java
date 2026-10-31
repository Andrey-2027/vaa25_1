package org.ip.unidr.config;

import com.vaadin.flow.component.UI;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.query.ReportContext;
import org.ipro.query.ui.selection.EntitySelection;
import org.ipro.report.ui.ReportUiPreferences;
import org.ipro.report.ui.unidr.UniDrCatalogGateway;
import org.ipro.report.ui.unidr.UniDrRunLauncher;
import org.ipro.unidr.model.UniDrDefinition;
import org.ipro.unidr.store.UniDrFileStore;
import org.ipro.unidr.ui.UniDrRunDialogFactory;
import org.ip.unidr.model.UniDrDefinitions;
import org.ip.views.preferences.UserPreferencesStore;
import org.ip.views.query.FormEntitySelection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application side of the M2.2.2 report/UniDR ports.
 *
 * <p>The platform UI module knows only the ports; this configuration is the one place
 * that names the application's file store, run dialog factory, entity selection form
 * and preference store. It is enabled together with the UniDR pilot, so a supply
 * without UniDR simply has no gateway/launcher/selection beans.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.unidr", name = "enabled", havingValue = "true")
public class UniDrUiConfiguration {

    /** Query editor and UniDR parameter forms use the same entity selection as the application. */
    @Bean
    public EntitySelection uniDrEntitySelection(SelectionFormAssembler assembler, EntityLookup lookup) {
        return new FormEntitySelection(assembler, lookup);
    }

    /** The saved catalogue, exposed without leaking the concrete file store into report UI. */
    @Bean
    public UniDrCatalogGateway uniDrCatalogGateway(UniDrFileStore store) {
        return new UniDrCatalogGateway() {
            @Override public java.util.List<UniDrFileStore.Entry> findPrintable(String entityClassName) {
                return store.findPrintable(entityClassName);
            }
            @Override public UniDrFileStore.Saved load(long id) { return store.load(id); }
            @Override public UniDrFileStore.Saved copy(long id, String title) { return store.copy(id, title); }
            @Override public String exportJson(long id) { return store.exportJson(id); }
            @Override public UniDrFileStore.ImportPreview inspectImport(String json) { return store.inspectImport(json); }
            @Override public UniDrFileStore.Saved importJson(String json, String title) { return store.importJson(json, title); }
            @Override public void delete(long id, String revision) { store.delete(id, revision); }
            @Override public String currentUser() { return store.currentUser(); }
        };
    }

    /** The report module does not know the editor's run dialog. */
    @Bean
    public UniDrRunLauncher uniDrRunLauncher(UniDrRunDialogFactory factory) {
        return new UniDrRunLauncher() {
            @Override public void open(UniDrDefinition definition) { factory.open(definition); }
            @Override public void open(UniDrDefinition definition, ReportContext context) {
                factory.open(definition, context);
            }
        };
    }

    /** Starting recipe stays application-owned; the platform route receives it as data. */
    @Bean
    public UniDrDefinition uniDrInitialDefinition() {
        return UniDrDefinitions.receivingItemsSummary();
    }

    /** Saved UI density is an application preference, not a platform contract. */
    @Bean
    public ReportUiPreferences reportUiPreferences(UserPreferencesStore preferences) {
        return new ReportUiPreferences() {
            @Override public void applySavedDensity(UI ui) { preferences.applySavedDensity(ui); }
        };
    }
}

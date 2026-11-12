import java.io.PrintWriter;
import java.util.Arrays;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** P1 gate. Arguments select specific classes (including filtered API/surface checks). */
public final class ExplorerStructureChecks {
    public static void main(String[] args) {
        var builder = LauncherDiscoveryRequestBuilder.request();
        if (args.length > 0) {
            builder.selectors(Arrays.stream(args).map(DiscoverySelectors::selectClass).toList());
        } else {
            builder.selectors(DiscoverySelectors.selectPackage("org.ipro.vaadin.explorer"),
                DiscoverySelectors.selectPackage("org.ip.vaadin.explorer"),
                DiscoverySelectors.selectClass("org.ip.views.admin.EntityExplorerPilotBoundaryTest"),
                DiscoverySelectors.selectClass("org.ip.views.admin.EntityExplorerCatalogTest"),
                DiscoverySelectors.selectClass("org.ip.views.admin.EntityExplorerWorkspaceTest"),
                DiscoverySelectors.selectClass("org.ipro.form.host.FormRouteUrlBridgeTest"),
                DiscoverySelectors.selectClass("org.ip.views.admin.ExplorerTreeModelTest"),
                DiscoverySelectors.selectClass("org.ip.views.admin.EntityStructureNavigationTest"),
                DiscoverySelectors.selectClass("org.ip.views.admin.SubsystemStructureNavigationTest"),
                DiscoverySelectors.selectClass("org.ip.views.admin.EntityDraftCatalogAdapterTest"),
                DiscoverySelectors.selectClass("org.ip.views.admin.DraftEditorSessionTest"),
                DiscoverySelectors.selectClass("org.ip.views.admin.EntityDraftEditorViewTest"),
                DiscoverySelectors.selectClass("org.ip.views.admin.EntityModificationTest"),
                DiscoverySelectors.selectClass("org.ip.views.admin.EntityDraftWorkspaceTest"),
                DiscoverySelectors.selectClass("org.ip.views.workspace.EntityDraftWorkspaceContractTest"),
                DiscoverySelectors.selectClass("org.ip.views.workspace.WorkspaceDirtyGuardTest"),
                DiscoverySelectors.selectClass("org.ip.views.workspace.WorkspaceRouteStateTest"),
                DiscoverySelectors.selectClass("org.ip.views.EntityExplorerAddressWiringTest"),
                DiscoverySelectors.selectClass("org.ip.views.EntityExplorerRouteGateTest"),
                DiscoverySelectors.selectClass("org.ipro.metadata.FieldMetadataInlineFilterTest"),
                DiscoverySelectors.selectClass("org.ipro.metadata.FieldMetadataRequiredOriginTest"));
            builder.filters(ClassNameFilter.includeClassNamePatterns(".*Test"));
        }
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(builder.build());
        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.out));
        if (summary.getTestsSucceededCount() == 0 || summary.getTestsFailedCount() != 0
                || summary.getTestsAbortedCount() != 0 || summary.getContainersFailedCount() != 0) {
            throw new AssertionError("Entity Explorer P1 checks failed");
        }
    }
}

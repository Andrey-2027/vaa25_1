import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Scoped P0 gate; neither this runner nor its fixtures are shipped in the application. */
public final class ExplorerPilotChecks {
    public static void main(String[] args) {
        var request = LauncherDiscoveryRequestBuilder.request().selectors(
            DiscoverySelectors.selectClass("org.ip.views.admin.EntityExplorerPilotBoundaryTest"),
            DiscoverySelectors.selectClass("org.ip.views.admin.EntityExplorerViewAddressTest"),
            DiscoverySelectors.selectClass("org.ip.views.admin.EntityExplorerViewSpringCreationTest"),
            DiscoverySelectors.selectClass("org.ip.views.EntityExplorerAddressWiringTest"),
            DiscoverySelectors.selectClass("org.ip.views.EntityExplorerRouteGateTest")).build();
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.out));
        if (summary.getTestsSucceededCount() == 0 || summary.getTestsFailedCount() != 0
                || summary.getTestsAbortedCount() != 0 || summary.getContainersFailedCount() != 0) {
            throw new AssertionError("Entity Explorer P0 checks failed");
        }
    }
}

package org.ip.probe;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Standalone launcher only; no launcher dependency is added to application test sources or the POM. */
public final class PermissionsP0Launcher {
    public static void main(String[] args) throws Exception {
        var listener = new SummaryGeneratingListener();
        String[] classes = args.length == 0 ? new String[]{"org.ip.security.PermissionsWorkspaceP0IT"} : args;
        var request = LauncherDiscoveryRequestBuilder.request()
            .selectors(Arrays.stream(classes).map(DiscoverySelectors::selectClass).toList()).build();
        LauncherFactory.create().execute(request, listener);
        Path output = Path.of(System.getProperty("permissions.p0.output", "outputs/permissions-p0"));
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output.resolve("test-summary.txt")))) {
            listener.getSummary().printTo(writer);
            listener.getSummary().printFailuresTo(writer);
        }
        listener.getSummary().printTo(new PrintWriter(System.out, true));
        if (listener.getSummary().getTestsFoundCount() == 0
            || listener.getSummary().getTestsSucceededCount() != listener.getSummary().getTestsFoundCount()
            || listener.getSummary().getTotalFailureCount() != 0) {
            listener.getSummary().printFailuresTo(new PrintWriter(System.out, true));
            System.exit(1);
        }
        // Cached Spring test contexts may retain non-daemon workers after a successful run.
        System.exit(0);
    }
}

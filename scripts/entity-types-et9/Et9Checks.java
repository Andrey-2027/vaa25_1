import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Runs explicitly selected ET9 classes/methods; no classpath-wide test discovery. */
public final class Et9Checks {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--matrix")) {
            var definitions = org.ipro.design.RecipeCatalog.recipes().stream().map(recipe -> java.util.Map.of(
                "definition", recipe, "draftSupported", recipe.draftSupported(),
                "generationEnabled", recipe.generationEnabled())).toList();
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                new java.io.File(args[1]), java.util.Map.of("stage", "ET9", "generationEnabled", false,
                    "g1Prerequisites", org.ipro.design.RecipeCatalog.g1Prerequisites(), "recipes", definitions));
            return;
        }
        var request = LauncherDiscoveryRequestBuilder.request();
        for (var arg : args) {
            var selector = arg.split("#", 2);
            request.selectors(selector.length == 1 ? DiscoverySelectors.selectClass(selector[0])
                : DiscoverySelectors.selectMethod(selector[0], selector[1]));
        }
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request.build());
        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.out));
        boolean failed = summary.getTestsFailedCount() != 0 || summary.getTestsAbortedCount() != 0
            || summary.getTestsSkippedCount() != 0 || summary.getContainersFailedCount() != 0
            || summary.getTestsSucceededCount() == 0;
        // Spring test contexts may own scheduler threads. Exit the standalone launcher
        // after reporting; registered Spring shutdown hooks close those contexts.
        System.exit(failed ? 1 : 0);
    }
}

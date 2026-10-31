import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;

/** Same production inventory/classpath on both runs; no DB, entities or form factory invocation. */
public final class ExplorerSnapshotMeasurement {
    public static void main(String[] args) {
        var metadata = new MetadataResolver();
        var references = new ReferenceIndex("org.ip.model");
        references.afterPropertiesSet();
        var numbering = new NumberingMetadataRegistry("org.ip.model");
        numbering.afterPropertiesSet();
        var subsystems = new SubsystemRegistry("org.ip", new org.ipro.metadata.MetadataResolver());
        subsystems.afterPropertiesSet();
        var assembler = new EntitySummaryAssembler("org.ip.model", metadata, new FormRegistry(),
            references, numbering, subsystems, FacetResolver.none());
        for (int i = 0; i < 5; i++) assembler.explorerSnapshot();
        long[] times = new long[15];
        ExplorerSnapshot snapshot = null;
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            snapshot = assembler.explorerSnapshot();
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        var measured = snapshot;
        var summaries = snapshot.entries().stream().flatMap(e -> measured.summaryOf(e.type()).stream()).toList();
        int bytes = JsonMapper.builder().build().writeValueAsBytes(summaries).length;
        System.out.printf(java.util.Locale.ROOT, "types=%d attempts=%d failures=%d median_ms=%.3f summaries_json_bytes=%d%n",
            snapshot.stats().typeCount(), snapshot.stats().summarizeAttempts(), snapshot.stats().summarizeFailures(),
            times[times.length / 2] / 1_000_000.0, bytes);
    }
}

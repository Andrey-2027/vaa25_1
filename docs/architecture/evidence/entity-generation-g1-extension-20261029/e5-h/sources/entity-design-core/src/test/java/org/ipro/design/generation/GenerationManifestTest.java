package org.ipro.design.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ipro.design.DraftOperations;
import org.ipro.design.ProjectDesignContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationManifestTest {
    private final ObjectMapper json = new ObjectMapper();

    private GenerationPlan plan(List<GenerationFile> files) {
        var draft = new DraftOperations().create();
        var names = ProjectDesignContext.Names.known(Set.of());
        var context = new ProjectDesignContext("fixture", "v1", "com.example", names, names, names, names, Map.of(), true);
        return new GenerationPlan("g1-" + "1".repeat(64), draft.recipe(), "fixture-v1", draft, context, files);
    }

    @Test void portableVectorsRejectIncompleteOrChangedSets() throws Exception {
        try (var input = getClass().getResourceAsStream("/org/ipro/design/generation/manifest-v2-vectors.json")) {
            var root = json.readTree(input);
            var files = new ArrayList<GenerationFile>();
            for (var source : root.path("sources")) files.add(new GenerationFile(GenerationFile.Operation.CREATE,
                    source.path("relativePath").asText(), source.path("content").asText(), source.path("sha256").asText()));
            var plan = plan(files);
            var valid = GenerationManifest.from(plan, GenerationManifest.LEGACY_FINGERPRINT);
            assertEquals(root.path("manifestSha256").asText(), valid.manifestSha256());
            assertEquals(2, valid.entries().size());
            assertTrue(valid.entries().getFirst().bytes() > files.getFirst().content().length());
            assertEquals(valid, json.readValue(json.writeValueAsBytes(valid), GenerationManifest.class));
            valid.verify(plan, GenerationManifest.LEGACY_FINGERPRINT);
            for (var vector : root.path("invalid")) {
                var entries = new ArrayList<GenerationManifest.Entry>();
                for (var index : vector.path("sourceIndexes")) entries.add(valid.entries().get(index.asInt()));
                var digest = vector.has("manifestSha256") ? vector.path("manifestSha256").asText() : GenerationManifest.hash(entries);
                assertThrows(IllegalArgumentException.class, () -> {
                    var manifest = new GenerationManifest(GenerationManifest.KIND, plan.planId(), plan.recipe(),
                            plan.generatorVersion(), GenerationManifest.LEGACY_FINGERPRINT, entries, digest);
                    manifest.verify(plan, GenerationManifest.LEGACY_FINGERPRINT);
                }, vector.path("name").asText());
            }
            var wrong = new GenerationFile(GenerationFile.Operation.CREATE, files.getLast().relativePath(),
                    "// changed\n", files.getLast().sha256());
            assertThrows(IllegalArgumentException.class, () -> GenerationManifest.from(plan(List.of(wrong)), GenerationManifest.LEGACY_FINGERPRINT));
            var extra = new GenerationManifest.Entry(GenerationFile.Operation.CREATE, "com/example/Extra.java", files.getFirst().sha256(), 1);
            var extraEntries = new ArrayList<>(valid.entries());
            extraEntries.add(extra);
            assertThrows(IllegalArgumentException.class, () -> new GenerationManifest(GenerationManifest.KIND,
                    plan.planId(), plan.recipe(), plan.generatorVersion(), GenerationManifest.LEGACY_FINGERPRINT,
                    extraEntries, GenerationManifest.hash(extraEntries)).verify(plan, GenerationManifest.LEGACY_FINGERPRINT));
            var badBytes = new ArrayList<>(valid.entries());
            var entry = badBytes.getLast();
            badBytes.set(1, new GenerationManifest.Entry(entry.operation(), entry.relativePath(), entry.sha256(), entry.bytes() + 1));
            assertThrows(IllegalArgumentException.class, () -> new GenerationManifest(GenerationManifest.KIND,
                    plan.planId(), plan.recipe(), plan.generatorVersion(), GenerationManifest.LEGACY_FINGERPRINT,
                    badBytes, GenerationManifest.hash(badBytes)).verify(plan, GenerationManifest.LEGACY_FINGERPRINT));
            var anotherBinding = new GenerationPlan("g1-" + "2".repeat(64), plan.recipe(), plan.generatorVersion(), plan.source(), plan.context(), plan.files());
            assertThrows(IllegalArgumentException.class, () -> valid.verify(anotherBinding, GenerationManifest.LEGACY_FINGERPRINT));
            assertThrows(IllegalArgumentException.class, () -> valid.verify(plan, GenerationManifest.EXTENSION_FINGERPRINT));
            var alias = new GenerationFile(GenerationFile.Operation.CREATE, files.getFirst().relativePath().toUpperCase(java.util.Locale.ROOT).replace(".JAVA", ".java"),
                    files.getFirst().content(), files.getFirst().sha256());
            assertThrows(IllegalArgumentException.class, () -> GenerationManifest.from(plan(List.of(files.getFirst(), alias)), GenerationManifest.LEGACY_FINGERPRINT));
        }
    }
}

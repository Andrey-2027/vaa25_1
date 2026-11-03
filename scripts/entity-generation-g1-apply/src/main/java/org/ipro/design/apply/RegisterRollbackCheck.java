package org.ipro.design.apply;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.generation.GenerationPlan;

/** Test-only actual-input rollback probe. It never changes production apply hooks or outcomes. */
public final class RegisterRollbackCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Usage: project C-summary plan dependencies result");
        Path project = Path.of(args[0]).toAbsolutePath().normalize();
        Path c = Path.of(args[1]);
        var plan = Json.MAPPER.readValue(Files.readString(Path.of(args[2])), GenerationPlan.class);
        if (!RecipeCatalog.INDEPENDENT_REGISTER.equals(plan.recipe())) throw new IllegalArgumentException("Register plan required");
        var dependencies = new ArrayList<DependencyLocation>();
        for (var node : Json.MAPPER.readTree(Files.readString(Path.of(args[3])))) {
            dependencies.add(new DependencyLocation(node.get("id").asText(), Path.of(node.get("path").asText())));
        }
        Path resultFile = Path.of(args[4]);
        Files.createDirectories(resultFile.getParent());
        Path journal = project.resolve(".g1-apply-v2/journal.json");
        Path capturedJournal = resultFile.resolveSibling("rollback-journal.json");
        Path source = project.resolve("src/main/java");
        if (Files.exists(project.resolve(".g1-apply-v2"))) throw new IllegalArgumentException("Rollback check requires a fresh project");
        var before = snapshot(source);
        ApplyResult result = new GenerationApplyAdapter(new ApplyHooks() {
            @Override public void beforeReceipt(Path receipt) throws IOException {
                Files.copy(journal, capturedJournal); // proves real publication/verification reached the D2 journal
                throw new IOException("injected actual-register receipt failure");
            }
        }).apply(new ApplyRequest(project, "src/main/java", c, dependencies), plan, plan.source(), plan.context());
        if (result.status() != ApplyStatus.ROLLED_BACK || !result.code().equals("RECEIPT_WRITE_FAILED")
                || Files.exists(journal) || !before.equals(snapshot(source)) || !Files.exists(capturedJournal)) {
            throw new AssertionError("Actual rollback failed or changed pre-existing source files: " + result);
        }
        Path receipts = project.resolve(".g1-apply-v2/receipts");
        if (Files.exists(receipts)) try (var paths = Files.walk(receipts)) {
            if (paths.anyMatch(Files::isRegularFile)) throw new AssertionError("Receipt survived rollback");
        }
        var output = Json.object();
        output.put("status", "PASS");
        output.put("mode", "actual plan/C/dependencies; test-only injected receipt failure");
        output.put("applyStatus", result.status().name());
        output.put("code", result.code());
        output.put("planId", plan.planId());
        output.put("cSummarySha256", ApplyHash.sha256(c));
        output.put("sourceSnapshotPreserved", true);
        output.put("journalRemoved", true);
        output.put("capturedJournal", capturedJournal.toString());
        Files.writeString(resultFile, Json.write(output));
    }

    private static java.util.Map<String, String> snapshot(Path source) throws IOException {
        var result = new java.util.TreeMap<String, String>();
        try (var paths = Files.walk(source)) {
            for (var path : paths.filter(Files::isRegularFile).toList())
                result.put(source.relativize(path).toString(), ApplyHash.sha256(path));
        }
        return result;
    }
}

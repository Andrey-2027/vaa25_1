package org.ipro.design.apply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.ipro.design.CheckedStructure;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftProblem;
import org.ipro.design.DraftText;
import org.ipro.design.DraftValidator;
import org.ipro.design.EntityDraft;
import org.ipro.design.EntityProperties;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RuntimeDesignSnapshot;
import org.ipro.design.RuntimeDesignSnapshot.Prerequisite;
import org.ipro.design.RuntimeDesignSnapshot.Status;
import org.ipro.design.ValidationResult;
import org.ipro.design.generation.EntityJavaGenerator;
import org.ipro.design.generation.GenerationFile;
import org.ipro.design.generation.GenerationPlan;
import org.ipro.design.generation.GenerationResult;

/**
 * Self-test launcher for D. It builds test fixtures through the public validator/generator;
 * the generated C-like summary is explicitly a harness fixture, not a generated-acceptance
 * result. The final G1.3 gate still requires the real A plan and matching C evidence.
 */
public final class ApplyHarness {
    private static final UUID DRAFT_ID = new UUID(1, 100);

    private ApplyHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 7 && args[0].equals("--apply")) {
            applyFile(args);
            return;
        }
        if (args.length != 2 || !args[0].equals("--self-test")) {
            throw new IllegalArgumentException("Usage: ApplyHarness --self-test <output-directory>"
                    + " | --apply <project> <sourceRoot> <cSummary> <plan> <dependencies> <result>");
        }
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        Path runRoot = output;
        Path workRoot = runRoot.resolve("work");
        Files.createDirectories(workRoot);

        List<CaseResult> results = new ArrayList<>();
        Fixture full = fixture(fullDraft());
        Fixture empty = fixture(emptyDraft());

        resultCase(results, workRoot, "form-configuration-applied-then-noop", ApplyStatus.APPLIED, "APPLIED", () -> {
            var fixture = formsFixture(workRoot); var project = projectV2(workRoot, fixture);
            var result = applyV2(project, fixture); expect(result, ApplyStatus.APPLIED, "APPLIED");
            assertAppliedV2(project, fixture, result);
            expect(applyV2(project, fixture), ApplyStatus.NOOP, "NOOP");
            return outcome(result, project, fixture.fixture);
        });
        resultCase(results, workRoot, "form-configuration-conflict-before-first-write", ApplyStatus.CONFLICT, "CONFLICT_UNOWNED", () -> {
            var fixture = formsFixture(workRoot); var project = projectV2(workRoot, fixture);
            Path config = project.root.resolve("src/main/java").resolve(fixture.fixture.plan.files().getLast().relativePath());
            Files.createDirectories(config.getParent()); Files.writeString(config, "foreign configuration");
            var result = applyV2(project, fixture); expect(result, ApplyStatus.CONFLICT, "CONFLICT_UNOWNED");
            if (Files.exists(project.root.resolve("src/main/java").resolve(fixture.fixture.plan.files().getFirst().relativePath())))
                throw new AssertionError("Entity written before configuration conflict");
            if (!Files.readString(config).equals("foreign configuration")) throw new AssertionError("Changed foreign config");
            return outcome(result, project, fixture.fixture);
        });
        resultCase(results, workRoot, "form-configuration-partial-write-rollback", ApplyStatus.ROLLED_BACK, "APPLY_IO", () -> {
            var fixture = formsFixture(workRoot); var project = projectV2(workRoot, fixture);
            var result = new GenerationApplyAdapter(new ApplyHooks() {
                @Override public void afterPublish(Path target) throws IOException { throw new IOException("after entity publication"); }
            }).apply(v2Request(project, fixture), fixture.fixture.plan, fixture.fixture.draft, fixture.fixture.context);
            expect(result, ApplyStatus.ROLLED_BACK, "APPLY_IO");
            for (var file : fixture.fixture.plan.files())
                if (Files.exists(project.root.resolve("src/main/java").resolve(file.relativePath()))) throw new AssertionError("Partial output retained");
            return outcome(result, project, fixture.fixture);
        });
        resultCase(results, workRoot, "empty-catalog-applied", ApplyStatus.APPLIED, "APPLIED",
                () -> {
                    Project project = project(workRoot, empty);
                    ApplyResult result = apply(project, empty);
                    expect(result, ApplyStatus.APPLIED, "APPLIED");
                    assertApplied(project, empty, result);
                    return outcome(result, project, empty);
                });
        resultCase(results, workRoot, "full-catalog-applied-then-noop", ApplyStatus.APPLIED, "APPLIED",
                () -> {
                    Project project = project(workRoot, full);
                    ApplyResult first = apply(project, full);
                    expect(first, ApplyStatus.APPLIED, "APPLIED");
                    assertApplied(project, full, first);
                    String receiptHash = ApplyHash.sha256(first.receipt());
                    ApplyResult second = apply(project, full);
                    expect(second, ApplyStatus.NOOP, "NOOP");
                    if (!receiptHash.equals(ApplyHash.sha256(second.receipt()))) {
                        throw new AssertionError("NOOP rewrote the receipt");
                    }
                    return outcome(first, project, full);
                });
        resultCase(results, workRoot, "document-v2-applied-then-noop", ApplyStatus.APPLIED, "APPLIED",
                () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    ApplyResult first = applyV2(project, fixture);
                    expect(first, ApplyStatus.APPLIED, "APPLIED");
                    assertAppliedV2(project, fixture, first);
                    ApplyResult second = applyV2(project, fixture);
                    expect(second, ApplyStatus.NOOP, "NOOP");
                    return outcome(first, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-multifile-applied-then-noop",
                ApplyStatus.APPLIED, "APPLIED", () -> {
                    V2Fixture fixture = v2MultiFixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    ApplyResult first = applyV2Multi(project, fixture);
                    expect(first, ApplyStatus.APPLIED, "APPLIED");
                    assertAppliedV2(project, fixture, first);
                    ApplyResult second = applyV2Multi(project, fixture);
                    expect(second, ApplyStatus.NOOP, "NOOP");
                    return outcome(first, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-multifile-owned-modified",
                ApplyStatus.CONFLICT, "CONFLICT_MODIFIED", () -> {
                    V2Fixture fixture = v2MultiFixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    expect(applyV2Multi(project, fixture), ApplyStatus.APPLIED, "APPLIED");
                    GenerationFile secondFile = fixture.fixture.plan.files().get(1);
                    Path second = targetOf(project.root, secondFile);
                    Files.writeString(second, "tampered", StandardCharsets.UTF_8);
                    ApplyResult result = applyV2Multi(project, fixture);
                    expect(result, ApplyStatus.CONFLICT, "CONFLICT_MODIFIED");
                    if (!Files.readString(second).equals("tampered")) {
                        throw new AssertionError("modified second target was changed");
                    }
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-multifile-owned-missing",
                ApplyStatus.RECOVERY_REQUIRED, "OWNED_TARGET_MISSING", () -> {
                    V2Fixture fixture = v2MultiFixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    expect(applyV2Multi(project, fixture), ApplyStatus.APPLIED, "APPLIED");
                    GenerationFile secondFile = fixture.fixture.plan.files().get(1);
                    Path second = targetOf(project.root, secondFile);
                    Files.delete(second);
                    ApplyResult result = applyV2Multi(project, fixture);
                    expect(result, ApplyStatus.RECOVERY_REQUIRED, "OWNED_TARGET_MISSING");
                    if (Files.exists(second, LinkOption.NOFOLLOW_LINKS)) {
                        throw new AssertionError("missing second target was recreated");
                    }
                    if (!Files.exists(targetOf(project.root,
                            fixture.fixture.plan.files().get(0)), LinkOption.NOFOLLOW_LINKS)) {
                        throw new AssertionError("missing-row recovery deleted the first target");
                    }
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-multifile-unowned-conflict",
                ApplyStatus.CONFLICT, "CONFLICT_UNOWNED", () -> {
                    V2Fixture fixture = v2MultiFixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    GenerationFile firstFile = fixture.fixture.plan.files().get(0);
                    GenerationFile secondFile = fixture.fixture.plan.files().get(1);
                    Path second = targetOf(project.root, secondFile);
                    Files.createDirectories(second.getParent());
                    Files.writeString(second, secondFile.content(), StandardCharsets.UTF_8);
                    ApplyResult result = applyV2Multi(project, fixture);
                    expect(result, ApplyStatus.CONFLICT, "CONFLICT_UNOWNED");
                    if (Files.exists(targetOf(project.root, firstFile), LinkOption.NOFOLLOW_LINKS)) {
                        throw new AssertionError("preflight wrote the first target");
                    }
                    if (!Files.readString(second).equals(secondFile.content())) {
                        throw new AssertionError("preflight changed the identical second target");
                    }
                    assertNoV2Receipt(project);
                    if (Files.exists(ApplyStateV2.journalPath(project.root.resolve(".g1-apply-v2")))) {
                        throw new AssertionError("preflight conflict left a journal");
                    }
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-multifile-late-second-target",
                ApplyStatus.ROLLED_BACK, "CONFLICT_LATE_TARGET", () -> {
                    V2Fixture fixture = v2MultiFixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    GenerationFile firstFile = fixture.fixture.plan.files().get(0);
                    GenerationFile secondFile = fixture.fixture.plan.files().get(1);
                    Path first = targetOf(project.root, firstFile);
                    Path second = targetOf(project.root, secondFile);
                    ApplyHooks hooks = new ApplyHooks() {
                        @Override
                        public void beforePublish(Path path) throws IOException {
                            if (path.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize())) {
                                Files.createDirectories(path.getParent());
                                Files.writeString(path, "late-bytes", StandardCharsets.UTF_8);
                            }
                        }
                    };
                    ApplyResult result = applyV2Multi(project, fixture, hooks);
                    expect(result, ApplyStatus.ROLLED_BACK, "CONFLICT_LATE_TARGET");
                    if (Files.exists(first, LinkOption.NOFOLLOW_LINKS)) {
                        throw new AssertionError("first target survived rollback");
                    }
                    if (!Files.readString(second).equals("late-bytes")) {
                        throw new AssertionError("late second target was changed");
                    }
                    assertNoV2Receipt(project);
                    if (Files.exists(ApplyStateV2.journalPath(project.root.resolve(".g1-apply-v2")))) {
                        throw new AssertionError("late second target rollback left a journal");
                    }
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-multifile-missing-sources",
                ApplyStatus.REJECTED, "C_SUMMARY_MISMATCH", () -> {
                    V2Fixture fixture = v2MultiFixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    ObjectNode summary = (ObjectNode) Json.MAPPER.readTree(
                            Files.readAllBytes(project.summary));
                    summary.remove("sources");
                    summary.remove("sourceSetSha256");
                    Files.writeString(project.summary, Json.write(summary), StandardCharsets.UTF_8);
                    ApplyResult result = applyV2Multi(project, fixture);
                    expect(result, ApplyStatus.REJECTED, "C_SUMMARY_MISMATCH");
                    assertNoTarget(project, fixture.fixture);
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-multifile-source-mismatch",
                ApplyStatus.REJECTED, "C_SUMMARY_MISMATCH", () -> {
                    V2Fixture fixture = v2MultiFixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    ObjectNode summary = (ObjectNode) Json.MAPPER.readTree(
                            Files.readAllBytes(project.summary));
                    ArrayNode sources = (ArrayNode) summary.get("sources");
                    ((ObjectNode) sources.get(1)).put("sha256", "0".repeat(64));
                    Files.writeString(project.summary, Json.write(summary), StandardCharsets.UTF_8);
                    ApplyResult result = applyV2Multi(project, fixture);
                    expect(result, ApplyStatus.REJECTED, "C_SUMMARY_MISMATCH");
                    assertNoTarget(project, fixture.fixture);
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-multifile-case-collision",
                ApplyStatus.REJECTED, "TARGET_CASE_COLLISION", () -> {
                    V2Fixture fixture = v2MultiFixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    GenerationFile first = fixture.fixture.plan.files().get(0);
                    GenerationFile colliding = new GenerationFile(GenerationFile.Operation.CREATE,
                            "com/example/generated/applieddocument.java", first.content(), first.sha256());
                    GenerationPlan forged = new GenerationPlan(fixture.fixture.plan.planId(),
                            fixture.fixture.plan.recipe(), fixture.fixture.plan.generatorVersion(),
                            fixture.fixture.plan.source(), fixture.fixture.plan.context(),
                            List.of(first, colliding));
                    ApplyResult result = new GenerationApplyAdapter(ApplyHooks.NONE,
                            (reviewed, draft, context) -> reviewed)
                            .apply(v2Request(project, fixture), forged, fixture.fixture.draft,
                                    fixture.fixture.context);
                    expect(result, ApplyStatus.REJECTED, "TARGET_CASE_COLLISION");
                    assertNoTarget(project, fixture.fixture);
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-multifile-foreign-receipt",
                ApplyStatus.CONFLICT, "CONFLICT_OTHER_PLAN", () -> {
                    V2Fixture single = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, single);
                    expect(applyV2(project, single), ApplyStatus.APPLIED, "APPLIED");
                    V2Fixture multi = multiFixture(workRoot, single.fixture,
                            "g1-" + "f".repeat(64));
                    Files.writeString(project.summary,
                            Json.write(baseSummaryV2(multi.fixture, multi.jar, multi.id)),
                            StandardCharsets.UTF_8);
                    ApplyResult result = applyV2Multi(project, multi);
                    expect(result, ApplyStatus.CONFLICT, "CONFLICT_OTHER_PLAN");
                    if (!Files.exists(targetOf(project.root,
                            multi.fixture.plan.files().get(0)), LinkOption.NOFOLLOW_LINKS)) {
                        throw new AssertionError("foreign receipt target was deleted");
                    }
                    if (Files.exists(targetOf(project.root,
                            multi.fixture.plan.files().get(1)), LinkOption.NOFOLLOW_LINKS)) {
                        throw new AssertionError("foreign receipt conflict wrote a new target");
                    }
                    return outcome(result, project, multi.fixture);
                });
        resultCase(results, workRoot, "document-v2-moved-project-noop", ApplyStatus.NOOP, "NOOP",
                () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    expect(applyV2(project, fixture), ApplyStatus.APPLIED, "APPLIED");
                    String summaryText = Files.readString(project.summary, StandardCharsets.UTF_8);
                    Path movedRoot = project.root.resolveSibling(project.root.getFileName() + "-moved");
                    Files.move(project.root, movedRoot);
                    Project moved = new Project(movedRoot, movedRoot.resolve("c-summary.json"));
                    Files.writeString(moved.summary, summaryText, StandardCharsets.UTF_8);
                    ApplyResult result = applyV2(moved, fixture);
                    expect(result, ApplyStatus.NOOP, "NOOP");
                    return outcome(result, moved, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-c-summary-changed", ApplyStatus.REJECTED,
                "C_SUMMARY_CHANGED", () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    expect(applyV2(project, fixture), ApplyStatus.APPLIED, "APPLIED");
                    ObjectNode changed = baseSummaryV2(fixture.fixture, fixture.jar, fixture.id);
                    ObjectNode scenario = changed.putObject("scenario");
                    scenario.put("id", "changed-after-apply");
                    scenario.put("specSha256", "1".repeat(64));
                    Files.writeString(project.summary, Json.write(changed), StandardCharsets.UTF_8);
                    ApplyResult result = applyV2(project, fixture);
                    expect(result, ApplyStatus.REJECTED, "C_SUMMARY_CHANGED");
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-dependency-drift", ApplyStatus.REJECTED,
                "DEPENDENCIES_CHANGED", () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    Files.writeString(fixture.jar, "changed-bytes", StandardCharsets.UTF_8);
                    ApplyResult result = applyV2(project, fixture);
                    expect(result, ApplyStatus.REJECTED, "DEPENDENCIES_CHANGED");
                    if (Files.exists(target(project, fixture.fixture))) {
                        throw new AssertionError("dependency drift created a target");
                    }
                    return outcome(result, project, fixture.fixture);
                });
        for (String mode : List.of("missing", "extra", "unreadable")) {
            String code = mode.equals("extra") ? "DEPENDENCY_SET_MISMATCH" : "DEPENDENCIES_UNAVAILABLE";
            resultCase(results, workRoot, "extension-dependency-" + mode, ApplyStatus.REJECTED, code, () -> {
                V2Fixture fixture = v2Fixture(workRoot); Project project = projectV2(workRoot, fixture);
                List<DependencyLocation> locations;
                if (mode.equals("missing")) locations = List.of();
                else if (mode.equals("unreadable")) locations = List.of(new DependencyLocation(fixture.id, project.root));
                else locations = List.of(new DependencyLocation(fixture.id, fixture.jar),
                        new DependencyLocation("org.ipro:extra:jar::1", Files.createTempFile(workRoot, "extra", ".jar")));
                ApplyResult result = new GenerationApplyAdapter().apply(new ApplyRequest(project.root,"src/main/java",project.summary,locations),fixture.fixture.plan,fixture.fixture.draft,fixture.fixture.context);
                expect(result,ApplyStatus.REJECTED,code);assertNoTarget(project,fixture.fixture);return outcome(result,project,fixture.fixture);
            });
        }
        resultCase(results, workRoot, "extension-dependency-drift-noop", ApplyStatus.REJECTED,"DEPENDENCIES_CHANGED",()->{
            V2Fixture fixture=v2Fixture(workRoot);Project project=projectV2(workRoot,fixture);
            expect(applyV2(project,fixture),ApplyStatus.APPLIED,"APPLIED");Files.writeString(fixture.jar,"changed after apply");
            ApplyResult result=applyV2(project,fixture);expect(result,ApplyStatus.REJECTED,"DEPENDENCIES_CHANGED");
            if(!Files.exists(target(project,fixture.fixture)))throw new AssertionError("NOOP drift deleted target");return outcome(result,project,fixture.fixture);
        });
        resultCase(results, workRoot, "extension-dependency-drift-after-publication", ApplyStatus.ROLLED_BACK,"DEPENDENCIES_CHANGED",()->{
            V2Fixture fixture=v2Fixture(workRoot);Project project=projectV2(workRoot,fixture);
            ApplyResult result=new GenerationApplyAdapter(new ApplyHooks(){@Override public void afterPublish(Path target)throws IOException {Files.writeString(fixture.jar,"changed during apply");}})
                    .apply(v2Request(project,fixture),fixture.fixture.plan,fixture.fixture.draft,fixture.fixture.context);
            expect(result,ApplyStatus.ROLLED_BACK,"DEPENDENCIES_CHANGED");assertNoTarget(project,fixture.fixture);assertNoV2Receipt(project);return outcome(result,project,fixture.fixture);
        });
        resultCase(results, workRoot, "document-v2-unowned-conflict", ApplyStatus.CONFLICT,
                "CONFLICT_UNOWNED", () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    Path target = target(project, fixture.fixture);
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, fixture.fixture.plan.files().getFirst().content(),
                            StandardCharsets.UTF_8);
                    ApplyResult result = applyV2(project, fixture);
                    expect(result, ApplyStatus.CONFLICT, "CONFLICT_UNOWNED");
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new AssertionError("unowned target disappeared");
                    }
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-late-target", ApplyStatus.ROLLED_BACK,
                "CONFLICT_LATE_TARGET", () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    Path target = target(project, fixture.fixture);
                    ApplyResult result = new GenerationApplyAdapter(new ApplyHooks() {
                        @Override
                        public void beforePublish(Path path) throws IOException {
                            Files.createDirectories(path.getParent());
                            Files.writeString(path, "late-bytes", StandardCharsets.UTF_8);
                        }
                    }).apply(v2Request(project, fixture), fixture.fixture.plan,
                            fixture.fixture.draft, fixture.fixture.context);
                    expect(result, ApplyStatus.ROLLED_BACK, "CONFLICT_LATE_TARGET");
                    if (!Files.readString(target).equals("late-bytes")) {
                        throw new AssertionError("late target bytes were changed");
                    }
                    assertNoV2Receipt(project);
                    if (Files.exists(ApplyStateV2.journalPath(project.root.resolve(".g1-apply-v2")))) {
                        throw new AssertionError("late target rollback left a journal");
                    }
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-replaced-target-preserved",
                ApplyStatus.RECOVERY_REQUIRED, "TARGET_IDENTITY_CHANGED", () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    Path target = target(project, fixture.fixture);
                    ApplyResult result = new GenerationApplyAdapter(new ApplyHooks() {
                        @Override
                        public void afterPublish(Path path) throws IOException {
                            FileTime modified = Files.getLastModifiedTime(path);
                            byte[] bytes = Files.readAllBytes(path);
                            Path original = path.resolveSibling("original-link");
                            Files.move(path, original);
                            Files.write(path, bytes);
                            Files.setLastModifiedTime(path, modified);
                        }
                    }).apply(v2Request(project, fixture), fixture.fixture.plan,
                            fixture.fixture.draft, fixture.fixture.context);
                    expect(result, ApplyStatus.RECOVERY_REQUIRED, "TARGET_IDENTITY_CHANGED");
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new AssertionError("replaced target was deleted by rollback");
                    }
                    if (!Files.readString(target).equals(
                            fixture.fixture.plan.files().getFirst().content())) {
                        throw new AssertionError("replaced target bytes were changed");
                    }
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-post-receipt-target-modified",
                ApplyStatus.RECOVERY_REQUIRED, "TARGET_MODIFIED", () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    Path target = target(project, fixture.fixture);
                    ApplyResult result = new GenerationApplyAdapter(new ApplyHooks() {
                        @Override
                        public void afterReceipt(Path receipt) throws IOException {
                            Files.writeString(target, "changed-after-receipt", StandardCharsets.UTF_8);
                        }
                    }).apply(v2Request(project, fixture), fixture.fixture.plan,
                            fixture.fixture.draft, fixture.fixture.context);
                    expect(result, ApplyStatus.RECOVERY_REQUIRED, "TARGET_MODIFIED");
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                            || !Files.readString(target).equals("changed-after-receipt")) {
                        throw new AssertionError("target modified after receipt was not preserved");
                    }
                    if (!Files.exists(ApplyStateV2.receiptPath(project.root.resolve(".g1-apply-v2"),
                            "src/main/java", fixture.fixture.plan.planId()))) {
                        throw new AssertionError("durable receipt was deleted after post-commit failure");
                    }
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-post-receipt-journal-failure",
                ApplyStatus.RECOVERY_REQUIRED, "APPLY_IO", () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    Path target = target(project, fixture.fixture);
                    ApplyResult result = new GenerationApplyAdapter(new ApplyHooks() {
                        @Override
                        public void afterReceipt(Path receipt) throws IOException {
                            throw new IOException("injected journal failure after receipt");
                        }
                    }).apply(v2Request(project, fixture), fixture.fixture.plan,
                            fixture.fixture.draft, fixture.fixture.context);
                    expect(result, ApplyStatus.RECOVERY_REQUIRED, "APPLY_IO");
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new AssertionError("confirmed output was deleted after receipt");
                    }
                    if (!Files.exists(ApplyStateV2.receiptPath(project.root.resolve(".g1-apply-v2"),
                            "src/main/java", fixture.fixture.plan.planId()))) {
                        throw new AssertionError("receipt was deleted after post-commit failure");
                    }
                    if (!Files.exists(ApplyStateV2.journalPath(project.root.resolve(".g1-apply-v2")))) {
                        throw new AssertionError("journal was deleted after post-commit failure");
                    }
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-arbitrary-required-suite",
                ApplyStatus.REJECTED, "C_TESTS_INCOMPLETE", () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    ObjectNode changed = baseSummaryV2(fixture.fixture, fixture.jar, fixture.id);
                    ((ObjectNode) changed.path("tests").path("suites").get(0))
                            .put("name", "org.example.NotTheHarness");
                    Files.writeString(project.summary, Json.write(changed), StandardCharsets.UTF_8);
                    ApplyResult result = applyV2(project, fixture);
                    expect(result, ApplyStatus.REJECTED, "C_TESTS_INCOMPLETE");
                    assertNoTarget(project, fixture.fixture);
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-receipt-failure-rollback", ApplyStatus.ROLLED_BACK,
                "RECEIPT_WRITE_FAILED", () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    ApplyResult result = new GenerationApplyAdapter(new ApplyHooks() {
                        @Override
                        public void beforeReceipt(Path path) throws IOException {
                            throw new IOException("injected receipt failure");
                        }
                    }).apply(v2Request(project, fixture), fixture.fixture.plan,
                            fixture.fixture.draft, fixture.fixture.context);
                    expect(result, ApplyStatus.ROLLED_BACK, "RECEIPT_WRITE_FAILED");
                    assertNoTarget(project, fixture.fixture);
                    assertNoV2Receipt(project);
                    if (Files.exists(ApplyStateV2.journalPath(project.root.resolve(".g1-apply-v2")))) {
                        throw new AssertionError("journal left after receipt rollback");
                    }
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-pending-journal", ApplyStatus.RECOVERY_REQUIRED,
                "JOURNAL_INCOMPLETE", () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    Path state = project.root.resolve(".g1-apply-v2");
                    Files.createDirectories(state);
                    ApplyStateV2.writeJournal(ApplyStateV2.journalPath(state),
                            new ApplyStateV2.Journal("pending-apply",
                                    org.ipro.design.generation.GenerationManifest.from(
                                            fixture.fixture.plan,
                                            EntityJavaGenerator.fingerprintProfile(
                                                    fixture.fixture.plan.recipe())),
                                    "0".repeat(64), List.of(), project.root.toString(),
                                    "src/main/java", ApplyStateV2.now(), List.of(), List.of(),
                                    ApplyStateV2.Phase.PREPARED, -1));
                    ApplyResult result = applyV2(project, fixture);
                    expect(result, ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_INCOMPLETE");
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "document-v2-unsupported-profile", ApplyStatus.REJECTED,
                "UNSUPPORTED_GENERATOR_PROFILE", () -> {
                    V2Fixture fixture = v2Fixture(workRoot);
                    Project project = projectV2(workRoot, fixture);
                    GenerationPlan unsupported = new GenerationPlan(fixture.fixture.plan.planId(),
                            fixture.fixture.plan.recipe(), "document-java-v0",
                            fixture.fixture.plan.source(), fixture.fixture.plan.context(),
                            fixture.fixture.plan.files());
                    ApplyResult result = new GenerationApplyAdapter().apply(v2Request(project, fixture),
                            unsupported, fixture.fixture.draft, fixture.fixture.context);
                    expect(result, ApplyStatus.REJECTED, "UNSUPPORTED_GENERATOR_PROFILE");
                    return outcome(result, project, fixture.fixture);
                });
        resultCase(results, workRoot, "moved-project-noop", ApplyStatus.NOOP, "NOOP",
                () -> {
                    Project project = project(workRoot, full);
                    expect(apply(project, full), ApplyStatus.APPLIED, "APPLIED");
                    Path movedRoot = project.root.resolveSibling(project.root.getFileName() + "-moved");
                    Files.move(project.root, movedRoot);
                    Project moved = new Project(movedRoot, movedRoot.resolve("c-summary.json"));
                    ApplyResult result = apply(moved, full);
                    expect(result, ApplyStatus.NOOP, "NOOP");
                    if (!Files.exists(target(moved, full))) throw new AssertionError("moved target missing");
                    return outcome(result, moved, full);
                });
        resultCase(results, workRoot, "owned-modified-conflict", ApplyStatus.CONFLICT, "CONFLICT_MODIFIED",
                () -> {
                    Project project = project(workRoot, full);
                    expect(apply(project, full), ApplyStatus.APPLIED, "APPLIED");
                    Path target = target(project, full);
                    Files.writeString(target, "tampered", StandardCharsets.UTF_8);
                    ApplyResult result = apply(project, full);
                    expect(result, ApplyStatus.CONFLICT, "CONFLICT_MODIFIED");
                    if (!Files.readString(target).equals("tampered")) throw new AssertionError("modified target was changed");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "owned-missing-recovery", ApplyStatus.RECOVERY_REQUIRED,
                "OWNED_TARGET_MISSING", () -> {
                    Project project = project(workRoot, full);
                    expect(apply(project, full), ApplyStatus.APPLIED, "APPLIED");
                    Files.delete(target(project, full));
                    ApplyResult result = apply(project, full);
                    expect(result, ApplyStatus.RECOVERY_REQUIRED, "OWNED_TARGET_MISSING");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "unowned-identical-conflict", ApplyStatus.CONFLICT, "CONFLICT_UNOWNED",
                () -> {
                    Project project = project(workRoot, full);
                    Path target = target(project, full);
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, full.plan.files().getFirst().content(), StandardCharsets.UTF_8);
                    ApplyResult result = apply(project, full);
                    expect(result, ApplyStatus.CONFLICT, "CONFLICT_UNOWNED");
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("unowned target disappeared");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "other-plan-conflict", ApplyStatus.CONFLICT, "CONFLICT_OTHER_PLAN",
                () -> {
                    Project project = project(workRoot, full);
                    expect(apply(project, full), ApplyStatus.APPLIED, "APPLIED");
                    Fixture other = fixture(changedDraft(full.draft));
                    project.summary = project.root.resolve("other-summary.json");
                    writeSummary(project.summary, other);
                    ApplyResult result = apply(project, other);
                    expect(result, ApplyStatus.CONFLICT, "CONFLICT_OTHER_PLAN");
                    if (!Files.readString(target(project, full)).equals(full.plan.files().getFirst().content())) {
                        throw new AssertionError("other-plan conflict changed the owned target");
                    }
                    return outcome(result, project, other);
                });
        resultCase(results, workRoot, "stale-draft-rejected", ApplyStatus.REJECTED, "STALE_INPUT",
                () -> {
                    Project project = project(workRoot, full);
                    ApplyResult result = apply(project, full.plan, changedDraft(full.draft), full.context);
                    expect(result, ApplyStatus.REJECTED, "STALE_INPUT");
                    assertNoTarget(project, full);
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "stale-context-rejected", ApplyStatus.REJECTED, "STALE_INPUT",
                () -> {
                    Project project = project(workRoot, full);
                    var changed = full.context.withRuntimeSnapshot(new RuntimeDesignSnapshot("runtime:changed",
                            true, java.util.Map.of(), java.util.Map.of(
                            "STANDARD_CATALOG_APP_API", new Prerequisite(Status.AVAILABLE, "fixture"),
                            "ET0_ET7_RUNTIME", new Prerequisite(Status.AVAILABLE, "fixture"))));
                    ApplyResult result = apply(project, full.plan, full.draft, changed);
                    expect(result, ApplyStatus.REJECTED, "STALE_INPUT");
                    assertNoTarget(project, full);
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "stale-plan-id-rejected", ApplyStatus.REJECTED, "PLAN_MISMATCH",
                () -> {
                    Project project = project(workRoot, full);
                    GenerationPlan forged = new GenerationPlan("g1-" + "a".repeat(64), full.plan.recipe(),
                            full.plan.generatorVersion(), full.plan.source(), full.plan.context(), full.plan.files());
                    ApplyResult result = apply(project, forged, full.draft, full.context);
                    expect(result, ApplyStatus.REJECTED, "PLAN_MISMATCH");
                    assertNoTarget(project, full);
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "bad-plan-hash-rejected", ApplyStatus.REJECTED, "PLAN_HASH_MISMATCH",
                () -> {
                    Project project = project(workRoot, full);
                    GenerationFile file = full.plan.files().getFirst();
                    GenerationFile bad = new GenerationFile(file.operation(), file.relativePath(),
                            file.content(), "0".repeat(64));
                    GenerationPlan forged = new GenerationPlan("g1-" + "b".repeat(64), full.plan.recipe(),
                            full.plan.generatorVersion(), full.plan.source(), full.plan.context(), List.of(bad));
                    ApplyResult result = apply(project, forged, full.draft, full.context);
                    expect(result, ApplyStatus.REJECTED, "PLAN_HASH_MISMATCH");
                    assertNoTarget(project, full);
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "multi-file-rejected", ApplyStatus.REJECTED, "MULTI_FILE",
                () -> {
                    Project project = project(workRoot, full);
                    GenerationFile file = full.plan.files().getFirst();
                    GenerationFile second = new GenerationFile(GenerationFile.Operation.CREATE,
                            "com/example/generated/Other.java", file.content(), file.sha256());
                    GenerationPlan forged = new GenerationPlan("g1-" + "c".repeat(64), full.plan.recipe(),
                            full.plan.generatorVersion(), full.plan.source(), full.plan.context(),
                            List.of(file, second));
                    ApplyResult result = apply(project, forged, full.draft, full.context);
                    expect(result, ApplyStatus.REJECTED, "MULTI_FILE");
                    assertNoTarget(project, full);
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "unsupported-recipe-rejected", ApplyStatus.REJECTED, "UNSUPPORTED_RECIPE",
                () -> {
                    Project project = project(workRoot, full);
                    EntityDraft v1 = new EntityDraft(1, RecipeCatalog.STANDARD_CATALOG, full.draft.draftId(),
                            full.draft.entity(), full.draft.fields(), full.draft.sections());
                    GenerationFile file = full.plan.files().getFirst();
                    GenerationPlan forged = new GenerationPlan("g1-" + "d".repeat(64),
                            RecipeCatalog.STANDARD_CATALOG, full.plan.generatorVersion(), v1,
                            full.context, List.of(file));
                    ApplyResult result = apply(project, forged, v1, full.context);
                    expect(result, ApplyStatus.REJECTED, "UNSUPPORTED_RECIPE");
                    return outcome(result, project, full);
                });

        cCase(results, workRoot, full, "c-selftest-mode-rejected", "C_SUMMARY_MISMATCH",
                root -> root.put("mode", "harness-selftest"));
        cCase(results, workRoot, full, "c-failed-status-rejected", "C_SUMMARY_MISMATCH",
                root -> root.put("status", "failed"));
        cCase(results, workRoot, full, "c-plan-mismatch-rejected", "C_SUMMARY_MISMATCH",
                root -> ((ObjectNode) root.get("plan")).put("planId", "g1-" + "e".repeat(64)));
        cCase(results, workRoot, full, "c-source-hash-mismatch-rejected", "C_SUMMARY_MISMATCH",
                root -> ((ObjectNode) root.get("source")).put("sha256", "0".repeat(64)));
        cCase(results, workRoot, full, "c-maven-not-attempted-rejected", "C_SUMMARY_MISMATCH",
                root -> ((ObjectNode) root.get("maven")).put("attempted", false));
        cCase(results, workRoot, full, "c-maven-exit-rejected", "C_SUMMARY_MISMATCH",
                root -> ((ObjectNode) root.get("maven")).put("exitCode", 1));
        cCase(results, workRoot, full, "c-no-tests-rejected", "C_TESTS_INCOMPLETE",
                root -> ((ObjectNode) root.get("tests")).put("run", 0));
        cCase(results, workRoot, full, "c-skipped-tests-rejected", "C_TESTS_INCOMPLETE",
                root -> ((ObjectNode) root.get("tests")).put("skipped", 1));
        cCase(results, workRoot, full, "c-missing-field-rejected", "C_SUMMARY_MISSING",
                root -> ((ObjectNode) root.get("tests")).remove("failures"));
        cCase(results, workRoot, full, "c-wrong-type-rejected", "C_SUMMARY_TYPE",
                root -> ((ObjectNode) root.get("tests")).put("reports", "one"));
        cCase(results, workRoot, full, "c-expected-hash-rejected", "C_EXPECTED_HASH_MISMATCH",
                root -> ((ObjectNode) root.get("source")).put("expectedSha256", "0".repeat(64)));
        cCase(results, workRoot, full, "c-bytes-rejected", "C_BYTES_MISMATCH",
                root -> ((ObjectNode) root.get("source")).put("bytes", 1));

        resultCase(results, workRoot, "source-root-traversal-rejected", ApplyStatus.REJECTED,
                "SOURCE_ROOT_INVALID", () -> {
                    Project project = project(workRoot, full);
                    ApplyResult result = rawApply(project.root, "../outside", project.summary, full);
                    expect(result, ApplyStatus.REJECTED, "SOURCE_ROOT_INVALID");
                    assertNoTarget(project, full);
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "source-root-unc-rejected", ApplyStatus.REJECTED,
                "SOURCE_ROOT_INVALID", () -> {
                    Project project = project(workRoot, full);
                    ApplyResult result = rawApply(project.root, "\\\\server\\share", project.summary, full);
                    expect(result, ApplyStatus.REJECTED, "SOURCE_ROOT_INVALID");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "source-root-absolute-rejected", ApplyStatus.REJECTED,
                "SOURCE_ROOT_INVALID", () -> {
                    Project project = project(workRoot, full);
                    ApplyResult result = rawApply(project.root, "C:\\outside", project.summary, full);
                    expect(result, ApplyStatus.REJECTED, "SOURCE_ROOT_INVALID");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "parent-file-rejected", ApplyStatus.REJECTED,
                "TARGET_PARENT_NOT_DIRECTORY", () -> {
                    Project project = project(workRoot, full);
                    Files.createFile(project.root.resolve("src/main/java/com"));
                    ApplyResult result = apply(project, full);
                    expect(result, ApplyStatus.REJECTED, "TARGET_PARENT_NOT_DIRECTORY");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "target-directory-conflict", ApplyStatus.CONFLICT, "CONFLICT_UNOWNED",
                () -> {
                    Project project = project(workRoot, full);
                    Files.createDirectories(target(project, full));
                    ApplyResult result = apply(project, full);
                    expect(result, ApplyStatus.CONFLICT, "CONFLICT_UNOWNED");
                    if (!Files.isDirectory(target(project, full))) throw new AssertionError("target directory disappeared");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "case-collision-rejected", ApplyStatus.REJECTED,
                "TARGET_CASE_COLLISION", () -> {
                    Project project = project(workRoot, full);
                    Files.createDirectories(project.root.resolve("src/main/java/com/example/Generated"));
                    ApplyResult result = apply(project, full);
                    expect(result, ApplyStatus.REJECTED, "TARGET_CASE_COLLISION");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "target-case-only-file-rejected", ApplyStatus.REJECTED,
                "TARGET_CASE_COLLISION", () -> {
                    Project project = project(workRoot, full);
                    Files.createDirectories(target(project, full).getParent());
                    Files.writeString(target(project, full).resolveSibling("futurecatalog.java"), "other");
                    ApplyResult result = apply(project, full);
                    expect(result, ApplyStatus.REJECTED, "TARGET_CASE_COLLISION");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "apply-locked-rejected", ApplyStatus.REJECTED, "APPLY_LOCKED",
                () -> {
                    Project project = project(workRoot, full);
                    var roots = ApplyPaths.resolve(project.root, "src/main/java");
                    ApplyPaths.ensureStateRoot(roots);
                    Path lockFile = roots.stateRoot().resolve(ApplyState.LOCK_FILE);
                    try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
                        ApplyResult result = apply(project, full);
                        expect(result, ApplyStatus.REJECTED, "APPLY_LOCKED");
                        return outcome(result, project, full);
                    }
                });
        resultCase(results, workRoot, "journal-incomplete-recovery", ApplyStatus.RECOVERY_REQUIRED,
                "JOURNAL_INCOMPLETE", () -> {
                    Project project = project(workRoot, full);
                    var roots = ApplyPaths.resolve(project.root, "src/main/java");
                    ApplyPaths.ensureStateRoot(roots);
                    GenerationFile file = full.plan.files().getFirst();
                    ApplyState.Journal journal = new ApplyState.Journal(UUID.randomUUID().toString(),
                            full.plan.planId(), full.plan.recipe().id() + "@" + full.plan.recipe().version(),
                            full.plan.generatorVersion(), roots.sourceRootRelative(), file.relativePath(),
                            file.sha256(), "0".repeat(64), ApplyState.Phase.PREPARED,
                            project.root.toString(), target(project, full).toString(),
                            target(project, full).resolveSibling(".g1-crash.tmp").toString(),
                            List.of(), ApplyState.now());
                    ApplyState.writeJournal(ApplyState.journalPath(roots.stateRoot()), journal);
                    ApplyResult result = apply(project, full);
                    expect(result, ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_INCOMPLETE");
                    assertNoTarget(project, full);
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "journal-invalid-recovery", ApplyStatus.RECOVERY_REQUIRED,
                "JOURNAL_INVALID", () -> {
                    Project project = project(workRoot, full);
                    var roots = ApplyPaths.resolve(project.root, "src/main/java");
                    ApplyPaths.ensureStateRoot(roots);
                    Files.writeString(ApplyState.journalPath(roots.stateRoot()), "{\"kind\":\"bad\"}");
                    ApplyResult result = apply(project, full);
                    expect(result, ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_INVALID");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "late-target-conflict", ApplyStatus.CONFLICT, "CONFLICT_LATE_TARGET",
                () -> {
                    Project project = project(workRoot, full);
                    ApplyHooks hooks = new ApplyHooks() {
                        @Override
                        public void beforePublish(Path target) throws IOException {
                            Files.writeString(target, "OTHER", StandardCharsets.UTF_8);
                        }
                    };
                    ApplyResult result = new GenerationApplyAdapter(hooks)
                            .apply(request(project), full.plan, full.draft, full.context);
                    expect(result, ApplyStatus.CONFLICT, "CONFLICT_LATE_TARGET");
                    if (!Files.readString(target(project, full)).equals("OTHER")) {
                        throw new AssertionError("late target was overwritten");
                    }
                    if (Files.exists(ApplyState.receiptPath(stateRoot(project), "src/main/java",
                            full.plan.planId()))) throw new AssertionError("late conflict wrote a receipt");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "rollback-after-publish", ApplyStatus.ROLLED_BACK, "APPLY_IO",
                () -> {
                    Project project = project(workRoot, full);
                    ApplyHooks hooks = new ApplyHooks() {
                        @Override
                        public void afterPublish(Path target) throws IOException {
                            throw new IOException("injected after publication");
                        }
                    };
                    ApplyResult result = new GenerationApplyAdapter(hooks)
                            .apply(request(project), full.plan, full.draft, full.context);
                    expect(result, ApplyStatus.ROLLED_BACK, "APPLY_IO");
                    assertNoTarget(project, full);
                    assertNoReceipt(project, full);
                    if (Files.exists(ApplyState.journalPath(stateRoot(project)))) {
                        throw new AssertionError("rollback left a journal");
                    }
                    if (Files.exists(project.root.resolve("src/main/java/com"))) {
                        throw new AssertionError("rollback left created package directories");
                    }
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "rollback-proof-recovery", ApplyStatus.RECOVERY_REQUIRED,
                "TARGET_MODIFIED", () -> {
                    Project project = project(workRoot, full);
                    ApplyHooks hooks = new ApplyHooks() {
                        @Override
                        public void afterPublish(Path target) throws IOException {
                            Files.writeString(target, "CHANGED", StandardCharsets.UTF_8);
                            throw new IOException("injected after modification");
                        }
                    };
                    ApplyResult result = new GenerationApplyAdapter(hooks)
                            .apply(request(project), full.plan, full.draft, full.context);
                    expect(result, ApplyStatus.RECOVERY_REQUIRED, "TARGET_MODIFIED");
                    if (!Files.readString(target(project, full)).equals("CHANGED")) {
                        throw new AssertionError("modified target was not preserved");
                    }
                    if (!Files.exists(ApplyState.journalPath(stateRoot(project)))) {
                        throw new AssertionError("recovery case did not retain the journal");
                    }
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "summary-changed-rollback", ApplyStatus.ROLLED_BACK,
                "C_SUMMARY_CHANGED", () -> {
                    Project project = project(workRoot, full);
                    ApplyHooks hooks = new ApplyHooks() {
                        @Override
                        public void afterPublish(Path target) throws IOException {
                            Files.writeString(project.summary, Files.readString(project.summary) + " ",
                                    StandardCharsets.UTF_8);
                        }
                    };
                    ApplyResult result = new GenerationApplyAdapter(hooks)
                            .apply(request(project), full.plan, full.draft, full.context);
                    expect(result, ApplyStatus.ROLLED_BACK, "C_SUMMARY_CHANGED");
                    assertNoTarget(project, full);
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "noop-requires-fresh-c", ApplyStatus.REJECTED, "C_SUMMARY_MISMATCH",
                () -> {
                    Project project = project(workRoot, full);
                    expect(apply(project, full), ApplyStatus.APPLIED, "APPLIED");
                    JsonNode tree = Json.MAPPER.readTree(Files.readAllBytes(project.summary));
                    ((ObjectNode) tree).put("mode", "harness-selftest");
                    Files.writeString(project.summary, Json.write(tree), StandardCharsets.UTF_8);
                    ApplyResult result = apply(project, full);
                    expect(result, ApplyStatus.REJECTED, "C_SUMMARY_MISMATCH");
                    if (!Files.exists(target(project, full))) throw new AssertionError("fresh-C NOOP removed target");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "corrupt-ledger-recovery", ApplyStatus.RECOVERY_REQUIRED,
                "LEDGER_INVALID", () -> {
                    Project project = project(workRoot, full);
                    expect(apply(project, full), ApplyStatus.APPLIED, "APPLIED");
                    Path receipt = receipt(project);
                    Files.writeString(receipt, "{", StandardCharsets.UTF_8);
                    ApplyResult result = apply(project, full);
                    expect(result, ApplyStatus.RECOVERY_REQUIRED, "LEDGER_INVALID");
                    if (!Files.exists(target(project, full))) throw new AssertionError("corrupt ledger removed target");
                    return outcome(result, project, full);
                });
        resultCase(results, workRoot, "unsupported-ledger-version-recovery", ApplyStatus.RECOVERY_REQUIRED,
                "LEDGER_INVALID", () -> {
                    Project project = project(workRoot, full);
                    expect(apply(project, full), ApplyStatus.APPLIED, "APPLIED");
                    Path receiptPath = receipt(project);
                    ObjectNode tree = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(receiptPath));
                    tree.put("stateVersion", 999);
                    Files.writeString(receiptPath, Json.write(tree), StandardCharsets.UTF_8);
                    ApplyResult result = apply(project, full);
                    expect(result, ApplyStatus.RECOVERY_REQUIRED, "LEDGER_INVALID");
                    return outcome(result, project, full);
                });

        assertionCase(results, "reserved-device-name-rejected",
                () -> expectApplyFailure("TARGET_PATH_INVALID",
                        () -> ApplyPaths.validateJavaRelativePath("com/example/CON.java")));
        assertionCase(results, "ads-path-rejected",
                () -> expectApplyFailure("TARGET_PATH_INVALID",
                        () -> ApplyPaths.validateJavaRelativePath("com/example/a:b.java")));
        assertionCase(results, "traversal-path-rejected",
                () -> expectApplyFailure("TARGET_PATH_INVALID",
                        () -> ApplyPaths.validateJavaRelativePath("com/../A.java")));
        assertionCase(results, "trailing-space-path-rejected",
                () -> expectApplyFailure("TARGET_PATH_INVALID",
                        () -> ApplyPaths.validateJavaRelativePath("com/example/A .java")));

        Path outside = workRoot.resolve("outside-" + UUID.randomUUID());
        Files.createDirectories(outside);
        results.add(aliasCase(workRoot, outside, full));
        results.add(stateAliasCase(workRoot, outside, full));

        threeFileCases(results, workRoot);
        registerCases(results, workRoot);

        boolean failed = results.stream().anyMatch(result -> result.status.equals("FAIL"));
        long skipped = results.stream().filter(result -> result.status.equals("SKIP")).count();
        ObjectNode summary = Json.object();
        summary.put("mode", "g1-apply-selftest");
        summary.put("status", failed ? "failed" : "success");
        summary.put("harnessFixture", "D-self-test; not generated-acceptance");
        summary.put("cases", results.size());
        summary.put("passed", results.stream().filter(result -> result.status.equals("PASS")).count());
        summary.put("failed", results.stream().filter(result -> result.status.equals("FAIL")).count());
        summary.put("skipped", skipped);
        summary.put("workRoot", workRoot.toString());
        summary.put("timestamp", ApplyState.now());
        summary.put("note", "D self-test fixture; not a generated-acceptance result");
        ArrayNode array = summary.putArray("results");
        for (CaseResult result : results) {
            ObjectNode node = array.addObject();
            node.put("name", result.name);
            node.put("status", result.status);
            node.put("expected", result.expected);
            node.put("actual", result.actual);
            node.put("detail", result.detail);
            if (result.project != null) node.put("projectRoot", result.project.toString());
            if (result.target != null) node.put("target", result.target.toString());
            node.put("targetExists", result.target != null && Files.exists(result.target, LinkOption.NOFOLLOW_LINKS));
            if (result.receipt != null) {
                node.put("receipt", result.receipt.toString());
                node.put("receiptExists", Files.exists(result.receipt, LinkOption.NOFOLLOW_LINKS));
            }
            if (result.journal != null) {
                node.put("journal", result.journal.toString());
                node.put("journalExists", Files.exists(result.journal, LinkOption.NOFOLLOW_LINKS));
            }
            if (result.status.equals("FAIL")) {
                System.out.println("FAIL " + result.name + " expected=" + result.expected
                        + " actual=" + result.actual + " detail=" + result.detail);
            } else if (result.status.equals("SKIP")) {
                System.out.println("SKIP " + result.name + " " + result.detail);
            } else {
                System.out.println("PASS " + result.name);
            }
        }
        Files.writeString(runRoot.resolve("selftest-summary.json"), Json.write(summary), StandardCharsets.UTF_8);
        System.out.println("Evidence: " + runRoot.resolve("selftest-summary.json"));
        System.out.println("Status: " + summary.get("status").asText() + " (failed "
                + summary.get("failed").asInt() + ", skipped " + skipped + ")");

        if (failed) System.exit(1);
    }

    private static CaseResult aliasCase(Path workRoot, Path outside, Fixture full) {
        Path project = null;
        try {
            project = project(workRoot, full).root;
            Path source = project.resolve("src/main/java");
            Path link = source.resolve("com");
            if (!createAlias(link, outside)) {
                return new CaseResult("symlink-or-junction-rejected", "SKIP", "REJECT", "SKIP",
                        "Alias creation is unavailable in this environment", project, null, null, null);
            }
            ApplyResult result = new GenerationApplyAdapter()
                    .apply(new ApplyRequest(project, "src/main/java", project.resolve("c-summary.json")),
                            full.plan, full.draft, full.context);
            boolean rejected = result.status() == ApplyStatus.REJECTED
                    && (result.code().startsWith("TARGET_PARENT_") || result.code().startsWith("TARGET_PATH_"));
            return new CaseResult("symlink-or-junction-rejected", rejected ? "PASS" : "FAIL",
                    "REJECTED/TARGET_PARENT_*", result.status() + "/" + result.code(),
                    "Alias path must be refused before write", project, targetOf(project, full),
                    null, null);
        } catch (Throwable ex) {
            return new CaseResult("symlink-or-junction-rejected", "FAIL", "REJECTED/TARGET_PARENT_*",
                    "exception", ex.getClass().getSimpleName() + ": " + ex.getMessage(), project,
                    null, null, null);
        }
    }

    private static CaseResult stateAliasCase(Path workRoot, Path outside, Fixture full) {
        Path project = null;
        try {
            project = project(workRoot, full).root;
            Path link = project.resolve(".entity-generation");
            if (!createAlias(link, outside)) {
                return new CaseResult("state-root-alias-rejected", "SKIP", "REJECT", "SKIP",
                        "Alias creation is unavailable in this environment", project, null, null, null);
            }
            ApplyResult result = new GenerationApplyAdapter()
                    .apply(new ApplyRequest(project, "src/main/java", project.resolve("c-summary.json")),
                            full.plan, full.draft, full.context);
            boolean rejected = result.status() == ApplyStatus.REJECTED
                    && result.code().startsWith("STATE_ROOT_");
            return new CaseResult("state-root-alias-rejected", rejected ? "PASS" : "FAIL",
                    "REJECTED/STATE_ROOT_*", result.status() + "/" + result.code(),
                    "Apply state root must not be a link/reparse alias", project, targetOf(project, full),
                    null, null);
        } catch (Throwable ex) {
            return new CaseResult("state-root-alias-rejected", "FAIL", "REJECTED/STATE_ROOT_*",
                    "exception", ex.getClass().getSimpleName() + ": " + ex.getMessage(), project,
                    null, null, null);
        }
    }

    private static boolean createAlias(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException ignored) {
            // Fall back to a Windows junction without an administrator prompt.
        }
        try {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return exit == 0 && Files.exists(link, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void cCase(List<CaseResult> results, Path workRoot, Fixture fixture, String name,
                              String expectedCode, Consumer<ObjectNode> mutation) {
        resultCase(results, workRoot, name, ApplyStatus.REJECTED, expectedCode, () -> {
            Project project = project(workRoot, fixture);
            ObjectNode summary = baseSummary(fixture);
            mutation.accept(summary);
            Files.writeString(project.summary, Json.write(summary), StandardCharsets.UTF_8);
            ApplyResult result = apply(project, fixture);
            expect(result, ApplyStatus.REJECTED, expectedCode);
            assertNoTarget(project, fixture);
            return outcome(result, project, fixture);
        });
    }

    private static void resultCase(List<CaseResult> results, Path workRoot, String name,
                                   ApplyStatus expectedStatus, String expectedCode, CheckedSupplier supplier) {
        Outcome outcome = null;
        try {
            outcome = supplier.get();
            ApplyResult result = outcome.result;
            if (result.status() != expectedStatus) {
                throw new AssertionError("status " + result.status() + " != " + expectedStatus);
            }
            if (expectedCode != null && !codeMatches(expectedCode, result.code())) {
                throw new AssertionError("code " + result.code() + " != " + expectedCode);
            }
            results.add(new CaseResult(name, "PASS", expectedStatus + "/" + expectedCode,
                    result.status() + "/" + result.code(), "ok", outcome.project.root,
                    targetOf(outcome.project.root, outcome.fixture), outcome.result.receipt(),
                    outcome.result.journal()));
        } catch (Throwable ex) {
            Path project = outcome == null ? null : outcome.project.root;
            Path target = outcome == null ? null : targetOf(outcome.project.root, outcome.fixture);
            results.add(new CaseResult(name, "FAIL", expectedStatus + "/" + expectedCode,
                    outcome == null ? "exception" : outcome.result.status() + "/" + outcome.result.code(),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), project, target,
                    outcome == null ? null : outcome.result.receipt(),
                    outcome == null ? null : outcome.result.journal()));
        }
    }

    private static void assertionCase(List<CaseResult> results, String name, CheckedRunnable action) {
        try {
            action.run();
            results.add(new CaseResult(name, "PASS", "REJECT", "REJECT", "ok", null, null, null, null));
        } catch (Throwable ex) {
            results.add(new CaseResult(name, "FAIL", "REJECT", "ACCEPT/OTHER",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), null, null, null, null));
        }
    }

    private static void expectApplyFailure(String code, CheckedRunnable action) throws Exception {
        try {
            action.run();
        } catch (ApplyFailure failure) {
            if (!failure.code().equals(code)) {
                throw new AssertionError("failure code " + failure.code() + " != " + code);
            }
            return;
        }
        throw new AssertionError("expected ApplyFailure " + code);
    }

    private static boolean codeMatches(String expected, String actual) {
        return actual != null && (expected.endsWith("*")
                ? actual.startsWith(expected.substring(0, expected.length() - 1))
                : actual.equals(expected));
    }

    private static void assertApplied(Project project, Fixture fixture, ApplyResult result) throws Exception {
        Path target = target(project, fixture);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("target was not created");
        String expected = fixture.plan.files().getFirst().content();
        String actual = Files.readString(target, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) throw new AssertionError("target content differs from plan");
        if (!fixture.plan.files().getFirst().sha256().equals(ApplyHash.sha256(actual))) {
            throw new AssertionError("target hash differs from plan");
        }
        if (!Files.exists(result.receipt(), LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("receipt missing");
        ObjectNode receipt = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(result.receipt()));
        if (!receipt.get("cSummarySha256").asText().equals(ApplyHash.sha256(project.summary))) {
            throw new AssertionError("receipt does not pin the C summary bytes");
        }
        if (Files.exists(ApplyState.journalPath(stateRoot(project)))) throw new AssertionError("journal left after APPLIED");
    }

    private static void assertNoTarget(Project project, Fixture fixture) {
        for (GenerationFile file : fixture.plan.files()) {
            if (Files.exists(targetOf(project.root, file), LinkOption.NOFOLLOW_LINKS)) {
                throw new AssertionError("target was written: " + file.relativePath());
            }
        }
    }

    private static void assertNoReceipt(Project project, Fixture fixture) {
        Path path = ApplyState.receiptPath(stateRoot(project), "src/main/java", fixture.plan.planId());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("receipt was written");
    }

    private static Path receipt(Project project) throws IOException {
        Path root = stateRoot(project).resolve("receipts");
        try (var stream = Files.walk(root)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .findFirst().orElseThrow(() -> new AssertionError("receipt not found"));
        }
    }

    private static Path stateRoot(Project project) {
        return project.root.resolve(".entity-generation").resolve("apply");
    }

    private static ApplyRequest request(Project project) {
        return new ApplyRequest(project.root, "src/main/java", project.summary);
    }

    private static ApplyResult apply(Project project, Fixture fixture) {
        return new GenerationApplyAdapter().apply(request(project), fixture.plan, fixture.draft, fixture.context);
    }

    private static ApplyResult apply(Project project, GenerationPlan plan, EntityDraft draft,
                                     ProjectDesignContext context) {
        return new GenerationApplyAdapter().apply(request(project), plan, draft, context);
    }

    private static ApplyResult rawApply(Path root, String sourceRoot, Path summary, Fixture fixture) {
        return new GenerationApplyAdapter().apply(new ApplyRequest(root, sourceRoot, summary),
                fixture.plan, fixture.draft, fixture.context);
    }

    private static Path target(Project project, Fixture fixture) {
        return targetOf(project.root, fixture);
    }

    private static Path targetOf(Path project, Fixture fixture) {
        return targetOf(project, fixture.plan.files().getFirst());
    }

    private static Path targetOf(Path project, GenerationFile file) {
        return project.resolve("src/main/java").resolve(file.relativePath());
    }

    private static Project project(Path workRoot, Fixture fixture) throws IOException {
        Path root = Files.createTempDirectory(workRoot, "project-");
        Files.createDirectories(root.resolve("src/main/java"));
        Path summary = root.resolve("c-summary.json");
        writeSummary(summary, fixture);
        return new Project(root, summary);
    }

    private static void writeSummary(Path path, Fixture fixture) throws IOException {
        Files.writeString(path, Json.write(baseSummary(fixture)), StandardCharsets.UTF_8);
    }

    private static ObjectNode baseSummary(Fixture fixture) {
        GenerationFile file = fixture.plan.files().getFirst();
        ObjectNode root = Json.object();
        root.put("mode", "generated-acceptance");
        root.put("scenario", "D-self-test");
        root.put("harnessFixture", "D-self-test; not a real generated-acceptance run");
        root.put("status", "success");
        ObjectNode plan = root.putObject("plan");
        plan.put("planId", fixture.plan.planId());
        plan.put("recipe", fixture.plan.recipe().id() + "@" + fixture.plan.recipe().version());
        plan.put("generatorVersion", fixture.plan.generatorVersion());
        plan.put("sourceFixture", "D-self-test");
        ObjectNode source = root.putObject("source");
        source.put("relativePath", file.relativePath());
        source.put("sha256", file.sha256());
        source.put("expectedSha256", file.sha256());
        source.put("bytes", file.content().getBytes(StandardCharsets.UTF_8).length);
        ObjectNode maven = root.putObject("maven");
        maven.put("attempted", true);
        maven.put("exitCode", 0);
        ObjectNode tests = root.putObject("tests");
        tests.put("reports", 1);
        tests.put("run", 5);
        tests.put("failures", 0);
        tests.put("errors", 0);
        tests.put("skipped", 0);
        return root;
    }

    private static Fixture fixture(EntityDraft draft) {
        return fixture(draft, context());
    }

    private static Fixture fixture(EntityDraft draft, ProjectDesignContext context) {
        ValidationResult validation = new DraftValidator().validate(draft, context);
        if (validation.hasErrors() || validation.checkedStructure().isEmpty()) {
            throw new IllegalStateException("Fixture is invalid: " + validation.problems());
        }
        CheckedStructure checked = validation.checkedStructure().orElseThrow();
        GenerationResult result = RecipeCatalog.STANDARD_CATALOG_V2.equals(draft.recipe())
                ? new org.ipro.design.generation.CatalogJavaGenerator().generate(checked, draft, context)
                : new EntityJavaGenerator().generate(checked, draft, context);
        if (!result.succeeded()) throw new IllegalStateException("Fixture generation failed: " + result.diagnostics());
        return new Fixture(draft, context, result.plan().orElseThrow());
    }

    /** Document plan produced by the shared public validator/generator, not a hand-written file. */
    private static Fixture documentFixture() {
        return fixture(documentDraft(), documentContext());
    }

    private static EntityDraft documentDraft() {
        return new EntityDraft(1, RecipeCatalog.STANDARD_DOCUMENT, new UUID(1, 200),
                new EntityProperties("AppliedDocument", DraftText.explicit("com.example.generated"),
                        "Применяемый документ",
                        new EntityProperties.Titles(DraftText.explicit("Документы"),
                                DraftText.explicit("Документ"), DraftText.explicit("Выбор документа")),
                        DraftText.explicit("applied_document"), null, "FILE"),
                List.of(), List.of());
    }

    private static ProjectDesignContext documentContext() {
        return new ProjectDesignContext("fixture-project", "v1", "com.example.generated",
                ProjectDesignContext.Names.known(Set.of()), ProjectDesignContext.Names.known(Set.of()),
                ProjectDesignContext.Names.known(Set.of()), ProjectDesignContext.Names.known(Set.of("FILE")),
                java.util.Map.of(), true,
                new RuntimeDesignSnapshot("runtime:document-control", true, java.util.Map.of(),
                        java.util.Map.of(
                                "STANDARD_DOCUMENT_APP_API", new Prerequisite(Status.AVAILABLE, "fixture"),
                                "ET0_ET7_RUNTIME", new Prerequisite(Status.AVAILABLE, "fixture"))));
    }

    private static void applyFile(String[] args) throws Exception {
        Path projectRoot = Path.of(args[1]).toAbsolutePath().normalize();
        String sourceRoot = args[2];
        Path cSummary = Path.of(args[3]).toAbsolutePath().normalize();
        Path planFile = Path.of(args[4]).toAbsolutePath().normalize();
        Path dependenciesFile = Path.of(args[5]).toAbsolutePath().normalize();
        Path resultFile = Path.of(args[6]).toAbsolutePath().normalize();

        GenerationPlan plan = Json.MAPPER.readValue(Files.readString(planFile, StandardCharsets.UTF_8),
                GenerationPlan.class);
        List<DependencyLocation> dependencies = new ArrayList<>();
        JsonNode node = Json.MAPPER.readTree(Files.readString(dependenciesFile, StandardCharsets.UTF_8));
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("Dependencies file must be a JSON array");
        }
        for (JsonNode item : node) {
            if (!item.isObject() || !item.hasNonNull("id") || !item.hasNonNull("path")) {
                throw new IllegalArgumentException("Each dependency needs id and path");
            }
            dependencies.add(new DependencyLocation(item.get("id").asText(),
                    Path.of(item.get("path").asText())));
        }

        ApplyResult result = new GenerationApplyAdapter().apply(
                new ApplyRequest(projectRoot, sourceRoot, cSummary, dependencies),
                plan, plan.source(), plan.context());
        ObjectNode output = Json.object();
        output.put("status", result.status().name());
        output.put("code", result.code());
        output.put("message", result.message());
        output.put("applyId", result.applyId());
        output.put("planId", result.planId());
        output.put("target", result.target() == null ? null : result.target().toString());
        output.put("receipt", result.receipt() == null ? null : result.receipt().toString());
        output.put("journal", result.journal() == null ? null : result.journal().toString());
        output.put("contentSha256", result.contentSha256());
        output.put("cSummarySha256", result.cSummarySha256());
        output.put("exitCode", result.exitCode());
        Files.createDirectories(resultFile.getParent());
        Files.writeString(resultFile, Json.write(output), StandardCharsets.UTF_8);
        System.out.println("RESULT " + result.status().name() + "/" + result.code());
        System.exit(result.exitCode());
    }

    private static V2Fixture formsFixture(Path workRoot) throws IOException {
        var old = documentDraft();
        var draft = new EntityDraft(old.schemaVersion(), RecipeCatalog.STANDARD_DOCUMENT_V2, old.draftId(), old.entity(), old.fields(), old.sections());
        var generated = fixture(draft, documentContext());
        Path jar = Files.createTempFile(workRoot, "platform-fixture-", ".jar");
        Files.writeString(jar, "selftest-platform-jar-v1", StandardCharsets.UTF_8);
        return new V2Fixture(generated, jar, "org.ipro:fixture-platform:jar::1.0-SELFTEST", ApplyHash.sha256(jar));
    }

    private static V2Fixture v2Fixture(Path workRoot) throws IOException {
        Fixture fixture = documentFixture();
        Path jar = Files.createTempFile(workRoot, "platform-fixture-", ".jar");
        Files.writeString(jar, "selftest-platform-jar-v1", StandardCharsets.UTF_8);
        return new V2Fixture(fixture, jar,
                "org.ipro:fixture-platform:jar::1.0-SELFTEST", ApplyHash.sha256(jar));
    }

    /** Real root + two generated rows; only C/dependency outcomes remain self-test fixtures. */
    private static V2Fixture threeFileFixture(Path workRoot) throws IOException {
        List<org.ipro.design.SectionDraft> sections = new ArrayList<>();
        for (int index = 1; index <= 2; index++) {
            sections.add(new org.ipro.design.SectionDraft(new UUID(7, index), "Rows " + index,
                    "AppliedDocumentRow" + index, DraftText.explicit("com.example.generated.rows"),
                    DraftText.auto(), DraftText.explicit("document"), DraftText.auto(), List.of()));
        }
        Fixture generated = fixture(documentDraft().withSections(sections), documentContext());
        if (generated.plan.files().size() != 3) throw new AssertionError("Expected root + two rows");
        Path jar = Files.createTempFile(workRoot, "three-file-dependency-", ".jar");
        Files.writeString(jar, "selftest-platform-jar-v1", StandardCharsets.UTF_8);
        return new V2Fixture(generated, jar, "org.ipro:fixture-platform:jar::1.0-SELFTEST",
                ApplyHash.sha256(jar));
    }

    private static Project threeFileProject(Path workRoot, V2Fixture fixture) throws IOException {
        Project project = projectV2(workRoot, fixture);
        Files.writeString(project.root.resolve("reviewed-plan.json"),
                Json.MAPPER.writeValueAsString(fixture.fixture.plan), StandardCharsets.UTF_8);
        return project;
    }

    private static ApplyResult applyThree(Project project, V2Fixture fixture, ApplyHooks hooks) {
        // Unlike the synthetic two-file fixture, this must pass the public freshness rebuild.
        return new GenerationApplyAdapter(hooks).apply(v2Request(project, fixture),
                fixture.fixture.plan, fixture.fixture.draft, fixture.fixture.context);
    }

    private static Path threeTarget(Project project, V2Fixture fixture, int index) {
        return targetOf(project.root, fixture.fixture.plan.files().get(index));
    }

    private static void assertThreeBytes(Project project, V2Fixture fixture) throws IOException {
        for (GenerationFile file : fixture.fixture.plan.files()) {
            Path target = targetOf(project.root, file);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !ApplyHash.sha256(target).equals(file.sha256())
                    || Files.size(target) != file.content().getBytes(StandardCharsets.UTF_8).length) {
                throw new AssertionError("Changed/missing generated target: " + file.relativePath());
            }
        }
    }

    private static void assertThreeRolledBack(Project project, V2Fixture fixture) throws IOException {
        for (int index = 0; index < 3; index++) {
            if (Files.exists(threeTarget(project, fixture, index), LinkOption.NOFOLLOW_LINKS)) {
                throw new AssertionError("Target survived complete rollback: " + index);
            }
        }
        assertNoV2Receipt(project);
        if (Files.exists(ApplyStateV2.journalPath(project.root.resolve(".g1-apply-v2")))) {
            throw new AssertionError("Journal survived complete rollback");
        }
        try (var paths = Files.walk(project.root.resolve("src/main/java"))) {
            if (paths.anyMatch(path -> !path.equals(project.root.resolve("src/main/java")))) {
                throw new AssertionError("Rollback left temp files or created directories");
            }
        }
    }

    private static java.util.Map<String, String> fileSnapshot(Path root) throws IOException {
        java.util.Map<String, String> hashes = new java.util.TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                hashes.put(root.relativize(path).toString().replace('\\', '/'), ApplyHash.sha256(path));
            }
        }
        return hashes;
    }

    private static Project relocateThree(Project project) throws IOException {
        Path moved = project.root.resolveSibling(project.root.getFileName() + "-moved").normalize();
        if (!moved.getParent().equals(project.root.getParent())) {
            throw new AssertionError("Relocation must stay in the isolated work root");
        }
        Files.move(project.root, moved);
        return new Project(moved, moved.resolve("c-summary.json"));
    }

    private static Outcome threeOutcome(Path workRoot, String name, Project project,
                                        V2Fixture fixture, ApplyResult result) throws IOException {
        ObjectNode proof = Json.object();
        proof.put("mode", "D-self-test; synthetic C and dependency JAR; public generator/rebuild");
        proof.put("status", result.status().name());
        proof.put("code", result.code());
        proof.put("projectRoot", project.root.toString());
        proof.put("planId", fixture.fixture.plan.planId());
        proof.put("cSummarySha256", ApplyHash.sha256(project.summary));
        proof.set("manifest", Json.MAPPER.valueToTree(org.ipro.design.generation.GenerationManifest.from(
                fixture.fixture.plan, EntityJavaGenerator.fingerprintProfile(fixture.fixture.plan.recipe()))));
        proof.set("filesAfterAssertions", Json.MAPPER.valueToTree(fileSnapshot(project.root)));
        Path evidence = workRoot.resolve("three-file-evidence");
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve(name + ".json"), Json.write(proof), StandardCharsets.UTF_8);
        return outcome(result, project, fixture.fixture);
    }

    private static void threeFileCases(List<CaseResult> results, Path workRoot) {
        resultCase(results, workRoot, "three-file-applied-noop", ApplyStatus.NOOP, "NOOP", () -> {
            V2Fixture fixture = threeFileFixture(workRoot);
            Project project = threeFileProject(workRoot, fixture);
            ApplyResult first = applyThree(project, fixture, ApplyHooks.NONE);
            assertAppliedV2(project, fixture, first);
            var before = fileSnapshot(project.root);
            ApplyResult result = applyThree(project, fixture, ApplyHooks.NONE);
            expect(result, ApplyStatus.NOOP, "NOOP");
            if (!before.equals(fileSnapshot(project.root))) throw new AssertionError("NOOP changed files");
            return threeOutcome(workRoot, "applied-noop", project, fixture, result);
        });
        for (int slot : List.of(1, 2)) {
            String name = "io-before-file-" + (slot + 1);
            resultCase(results, workRoot, "three-file-" + name, ApplyStatus.ROLLED_BACK, "APPLY_IO", () -> {
                V2Fixture fixture = threeFileFixture(workRoot);
                Project project = threeFileProject(workRoot, fixture);
                ApplyResult result = applyThree(project, fixture, new ApplyHooks() {
                    @Override public void beforePublish(Path target) throws IOException {
                        if (target.equals(threeTarget(project, fixture, slot))) {
                            for (int index = 0; index < slot; index++) {
                                if (!ApplyHash.sha256(threeTarget(project, fixture, index)).equals(
                                        fixture.fixture.plan.files().get(index).sha256())) {
                                    throw new AssertionError("Earlier file was not published");
                                }
                            }
                            throw new IOException("injected I/O failure before file " + (slot + 1));
                        }
                    }
                });
                expect(result, ApplyStatus.ROLLED_BACK, "APPLY_IO");
                assertThreeRolledBack(project, fixture);
                return threeOutcome(workRoot, name, project, fixture, result);
            });
        }
        resultCase(results, workRoot, "three-file-late-third", ApplyStatus.ROLLED_BACK,
                "CONFLICT_LATE_TARGET", () -> {
            V2Fixture fixture = threeFileFixture(workRoot);
            Project project = threeFileProject(workRoot, fixture);
            ApplyResult result = applyThree(project, fixture, new ApplyHooks() {
                @Override public void beforePublish(Path target) throws IOException {
                    if (target.equals(threeTarget(project, fixture, 2))) {
                        for (int index = 0; index < 2; index++) {
                            if (!Files.exists(threeTarget(project, fixture, index))) {
                                throw new AssertionError("Late conflict must follow two publications");
                            }
                        }
                        Files.writeString(target, "foreign-third", StandardCharsets.UTF_8);
                    }
                }
            });
            expect(result, ApplyStatus.ROLLED_BACK, "CONFLICT_LATE_TARGET");
            for (int index = 0; index < 2; index++) {
                if (Files.exists(threeTarget(project, fixture, index))) throw new AssertionError("Owned output left");
            }
            if (!Files.readString(threeTarget(project, fixture, 2)).equals("foreign-third")) {
                throw new AssertionError("Foreign third file changed");
            }
            assertNoV2Receipt(project);
            if (Files.exists(result.journal())) throw new AssertionError("Rollback left journal");
            try (var paths = Files.walk(project.root)) {
                if (paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"))) {
                    throw new AssertionError("Rollback left temporary files");
                }
            }
            return threeOutcome(workRoot, "late-third", project, fixture, result);
        });
        resultCase(results, workRoot, "three-file-io-after-third", ApplyStatus.ROLLED_BACK, "APPLY_IO", () -> {
            V2Fixture fixture = threeFileFixture(workRoot);
            Project project = threeFileProject(workRoot, fixture);
            ApplyResult result = applyThree(project, fixture, new ApplyHooks() {
                @Override public void afterPublish(Path target) throws IOException {
                    if (target.equals(threeTarget(project, fixture, 2))) {
                        assertThreeBytes(project, fixture);
                        throw new IOException("injected after all three publications");
                    }
                }
            });
            expect(result, ApplyStatus.ROLLED_BACK, "APPLY_IO");
            assertThreeRolledBack(project, fixture);
            return threeOutcome(workRoot, "io-after-third", project, fixture, result);
        });
        for (String mode : List.of("rollback", "modified", "unknown", "committed")) {
            ApplyStatus expected = mode.equals("rollback") ? ApplyStatus.ROLLED_BACK : ApplyStatus.RECOVERY_REQUIRED;
            String code = switch (mode) {
                case "rollback" -> "RECEIPT_WRITE_FAILED";
                case "modified" -> "TARGET_MODIFIED";
                case "unknown" -> "RECEIPT_STATE_UNKNOWN";
                default -> "APPLY_IO";
            };
            resultCase(results, workRoot, "three-file-receipt-" + mode, expected, code, () -> {
                V2Fixture fixture = threeFileFixture(workRoot);
                Project project = threeFileProject(workRoot, fixture);
                ApplyResult result = applyThree(project, fixture, new ApplyHooks() {
                    @Override public void beforeReceipt(Path receipt) throws IOException {
                        assertThreeBytes(project, fixture);
                        if (mode.equals("committed")) return;
                        if (mode.equals("modified")) {
                            Files.writeString(threeTarget(project, fixture, 2), "foreign-edit", StandardCharsets.UTF_8);
                        }
                        if (mode.equals("unknown")) {
                            Files.createDirectories(receipt.getParent());
                            Files.writeString(receipt, "uncertain-receipt", StandardCharsets.UTF_8);
                        }
                        throw new IOException("injected before receipt: " + mode);
                    }
                    @Override public void afterReceipt(Path receipt) throws IOException {
                        if (mode.equals("committed")) throw new IOException("injected after durable receipt");
                    }
                });
                expect(result, expected, code);
                if (mode.equals("rollback")) {
                    assertThreeRolledBack(project, fixture);
                } else {
                    if (!Files.exists(result.journal())) throw new AssertionError("Recovery lost journal");
                    if (mode.equals("modified")) {
                        if (Files.exists(threeTarget(project, fixture, 0))
                                || Files.exists(threeTarget(project, fixture, 1))
                                || !Files.readString(threeTarget(project, fixture, 2)).equals("foreign-edit")) {
                            throw new AssertionError("Partial cleanup failed to preserve foreign edit");
                        }
                        assertNoV2Receipt(project);
                    } else {
                        assertThreeBytes(project, fixture);
                        if (mode.equals("unknown")) {
                            if (!Files.readString(result.receipt()).equals("uncertain-receipt")) {
                                throw new AssertionError("Uncertain receipt was changed");
                            }
                        } else {
                            ApplyStateV2.Receipt receipt = ApplyStateV2.readReceipt(result.receipt());
                            if (receipt.entries().size() != 3 || !receipt.applyId().equals(result.applyId())) {
                                throw new AssertionError("Durable whole-set receipt lost");
                            }
                        }
                    }
                    var before = fileSnapshot(project.root);
                    ApplyResult retry = applyThree(project, fixture, ApplyHooks.NONE);
                    expect(retry, ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_INCOMPLETE");
                    if (!before.equals(fileSnapshot(project.root))) throw new AssertionError("Recovery retry changed files");
                }
                return threeOutcome(workRoot, "receipt-" + mode, project, fixture, result);
            });
        }
        for (boolean copy : List.of(false, true)) {
            String name = copy ? "completed-copy-noop" : "completed-move-noop";
            resultCase(results, workRoot, "three-file-" + name, ApplyStatus.NOOP, "NOOP", () -> {
                V2Fixture fixture = threeFileFixture(workRoot);
                Project project = threeFileProject(workRoot, fixture);
                assertAppliedV2(project, fixture, applyThree(project, fixture, ApplyHooks.NONE));
                var before = fileSnapshot(project.root);
                Project relocated;
                if (copy) {
                    Path root = Files.createTempDirectory(workRoot, "three-file-copy-");
                    try (var paths = Files.walk(project.root)) {
                        for (Path source : paths.toList()) {
                            Path destination = root.resolve(project.root.relativize(source));
                            if (Files.isDirectory(source)) Files.createDirectories(destination);
                            else Files.copy(source, destination);
                        }
                    }
                    relocated = new Project(root, root.resolve("c-summary.json"));
                } else {
                    relocated = relocateThree(project);
                }
                // Reoccupy an old provenance target: it must never be read/removed by relocated D.
                Path old = threeTarget(project, fixture, 2);
                if (!copy) {
                    Files.createDirectories(old.getParent());
                    Files.writeString(old, "old-location-foreign", StandardCharsets.UTF_8);
                }
                var oldBefore = fileSnapshot(project.root);
                ApplyResult result = applyThree(relocated, fixture, ApplyHooks.NONE);
                expect(result, ApplyStatus.NOOP, "NOOP");
                assertThreeBytes(relocated, fixture);
                if (!before.equals(fileSnapshot(relocated.root)) || !oldBefore.equals(fileSnapshot(project.root))) {
                    throw new AssertionError("Relocated NOOP changed completed or old project bytes");
                }
                return threeOutcome(workRoot, name, relocated, fixture, result);
            });
        }
        resultCase(results, workRoot, "three-file-pending-move-recovery", ApplyStatus.RECOVERY_REQUIRED,
                "JOURNAL_INCOMPLETE", () -> {
            V2Fixture fixture = threeFileFixture(workRoot);
            Project project = threeFileProject(workRoot, fixture);
            // Bypass adapter catch/rollback, like an abrupt stop; this is not an OS-crash test.
            class AbruptStop extends Error { }
            boolean stopped = false;
            try {
                applyThree(project, fixture, new ApplyHooks() {
                    @Override public void beforePublish(Path target) {
                        if (target.equals(threeTarget(project, fixture, 2))) throw new AbruptStop();
                    }
                });
            } catch (AbruptStop expected) {
                stopped = true;
            }
            if (!stopped) throw new AssertionError("Abrupt stop was not reached");
            Path journalPath = ApplyStateV2.journalPath(project.root.resolve(".g1-apply-v2"));
            ApplyStateV2.Journal journal = ApplyStateV2.readJournal(journalPath).orElseThrow();
            if (journal.phase() != ApplyStateV2.Phase.PUBLISHED || journal.entryIndex() != 1
                    || journal.entries().size() != 3 || journal.entries().get(2).publishedIdentity() != null) {
                throw new AssertionError("Expected partial three-entry journal after second publication");
            }
            for (int index = 0; index < 2; index++) {
                if (!ApplyHash.sha256(threeTarget(project, fixture, index)).equals(
                        fixture.fixture.plan.files().get(index).sha256())) throw new AssertionError("Missing published file");
            }
            if (Files.exists(threeTarget(project, fixture, 2))) throw new AssertionError("Third file published early");
            assertNoV2Receipt(project);
            var before = fileSnapshot(project.root);
            Project moved = relocateThree(project);
            Path old = threeTarget(project, fixture, 0);
            Files.createDirectories(old.getParent());
            Files.writeString(old, "old-location-foreign", StandardCharsets.UTF_8);
            var oldBefore = fileSnapshot(project.root);
            ApplyResult result = applyThree(moved, fixture, ApplyHooks.NONE);
            expect(result, ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_INCOMPLETE");
            if (!before.equals(fileSnapshot(moved.root)) || !oldBefore.equals(fileSnapshot(project.root))) {
                throw new AssertionError("Pending relocation modified journal, outputs, temp links or old location");
            }
            return threeOutcome(workRoot, "pending-move-recovery", moved, fixture, result);
        });
    }

    private static V2Fixture v2MultiFixture(Path workRoot) throws IOException {
        Fixture base = documentFixture();
        return multiFixture(workRoot, base, base.plan.planId());
    }

    private static V2Fixture multiFixture(Path workRoot, Fixture base, String planId)
            throws IOException {
        String content = "package com.example.generated;\n\n"
                + "final class AppliedDocumentSections {\n}\n";
        GenerationFile second = new GenerationFile(GenerationFile.Operation.CREATE,
                "com/example/generated/AppliedDocumentSections.java", content,
                ApplyHash.sha256(content));
        GenerationPlan plan = new GenerationPlan(planId, base.plan.recipe(),
                base.plan.generatorVersion(), base.plan.source(), base.plan.context(),
                List.of(base.plan.files().getFirst(), second));
        Fixture multi = new Fixture(base.draft, base.context, plan);
        Path jar = Files.createTempFile(workRoot, "platform-fixture-", ".jar");
        Files.writeString(jar, "selftest-platform-jar-v1", StandardCharsets.UTF_8);
        return new V2Fixture(multi, jar, "org.ipro:fixture-platform:jar::1.0-SELFTEST",
                ApplyHash.sha256(jar));
    }

    private static Project projectV2(Path workRoot, V2Fixture fixture) throws IOException {
        Path root = Files.createTempDirectory(workRoot, "project-v2-");
        Files.createDirectories(root.resolve("src/main/java"));
        Path summary = root.resolve("c-summary.json");
        Files.writeString(summary, Json.write(baseSummaryV2(fixture.fixture, fixture.jar, fixture.id)),
                StandardCharsets.UTF_8);
        return new Project(root, summary);
    }

    private static ObjectNode baseSummaryV2(Fixture fixture, Path dependencyJar, String dependencyId)
            throws IOException {
        GenerationFile file = fixture.plan.files().getFirst();
        ObjectNode root = Json.object();
        root.put("kind", "g1-c-summary-v2");
        root.put("mode", "generated-acceptance");
        root.put("status", "success");
        var manifest = org.ipro.design.generation.GenerationManifest.from(fixture.plan,
                EntityJavaGenerator.fingerprintProfile(fixture.plan.recipe()));
        root.set("manifest", Json.MAPPER.valueToTree(manifest));
        ObjectNode scenario = root.putObject("scenario");
        scenario.put("id", "d-self-test");
        scenario.put("specSha256", "0".repeat(64));
        ArrayNode dependencies = root.putArray("dependencies");
        ObjectNode dependency = dependencies.addObject();
        dependency.put("id", dependencyId);
        dependency.put("sha256", ApplyHash.sha256(dependencyJar));
        root.putArray("supportSources");
        if (fixture.plan.files().size() > 1) {
            ArrayNode sources = root.putArray("sources");
            for (GenerationFile sourceFile : fixture.plan.files()) {
                ObjectNode source = sources.addObject();
                source.put("relativePath", sourceFile.relativePath());
                source.put("sha256", sourceFile.sha256());
                source.put("bytes", sourceFile.content().getBytes(StandardCharsets.UTF_8).length);
            }
            root.put("sourceSetSha256", manifest.manifestSha256());
        }
        ObjectNode compile = root.putObject("compile");
        compile.put("attempted", true);
        compile.put("exitCode", 0);
        ObjectNode runtime = root.putObject("runtime");
        runtime.put("attempted", true);
        runtime.put("startup", "success");
        ObjectNode tests = root.putObject("tests");
        tests.put("reports", 1);
        tests.put("run", 5);
        tests.put("failures", 0);
        tests.put("errors", 0);
        tests.put("skipped", 0);
        ArrayNode suites = tests.putArray("suites");
        ObjectNode suite = suites.addObject();
        suite.put("name", RecipeCatalog.INDEPENDENT_REGISTER.equals(fixture.draft.recipe())
                ? "com.example.generated.harness.RegisterScenarioTest"
                : !fixture.draft.sections().isEmpty()
                ? "com.example.generated.harness.SectionsScenarioTest"
                : fixture.plan.files().size() > (RecipeCatalog.formConfigurationRecipe(fixture.plan.recipe()) ? 2 : 1)
                ? "com.example.generated.harness.MultiFileScenarioTest"
                : "com.example.generated.harness.GenerationScenarioTest");
        suite.put("required", true);
        suite.put("run", 5);
        suite.put("failures", 0);
        suite.put("errors", 0);
        suite.put("skipped", 0);
        return root;
    }

    private static ApplyRequest v2Request(Project project, V2Fixture fixture) {
        return new ApplyRequest(project.root, "src/main/java", project.summary,
                List.of(new DependencyLocation(fixture.id, fixture.jar)));
    }

    private static V2Fixture registerFixture(Path workRoot, String period) throws IOException {
        boolean day = org.ipro.design.RegisterDraft.DAY.equals(period);
        UUID dimension = new UUID(8, 1), resource = new UUID(8, 2);
        var fields = List.of(new FieldDraft(dimension, day ? "item" : "region", "Измерение",
            day ? "REFERENCE" : "STRING", DraftText.auto(), true, FieldDraft.UiRequired.AUTO,
            day ? ConstraintDraft.empty() : new ConstraintDraft("20", "", ""),
            day ? new org.ipro.design.ReferenceDraft("com.example.Item") : null, FieldApplications.root()),
            new FieldDraft(resource, "rate", "Ресурс", "DECIMAL", DraftText.auto(), true,
                FieldDraft.UiRequired.AUTO, new ConstraintDraft("", "19", "2"), null, FieldApplications.root()));
        var draft = new EntityDraft(3, RecipeCatalog.INDEPENDENT_REGISTER, new UUID(8, 3),
            new EntityProperties(day ? "AppliedDayRegister" : "AppliedNoneRegister", DraftText.explicit("com.example.generated"),
                "Применённый регистр", EntityProperties.Titles.auto(), DraftText.auto(), null, "FILE"),
            fields, List.of(), null, new org.ipro.design.RegisterDraft(period,
                org.ipro.design.RegisterDraft.NAMED_AND_CANONICAL_CRUD, List.of(dimension), List.of(resource)));
        var targets = day ? java.util.Map.of("com.example.Item", new org.ipro.design.ReferenceTargetDescriptor(
            "com.example.Item", "Item", "STANDARD_ROOT", "java.lang.Long",
            org.ipro.design.ReferenceTargetDescriptor.Fact.YES, org.ipro.design.ReferenceTargetDescriptor.Fact.YES,
            org.ipro.design.ReferenceTargetDescriptor.Eligibility.SUPPORTED, ""))
            : java.util.Map.<String, org.ipro.design.ReferenceTargetDescriptor>of();
        var names = ProjectDesignContext.Names.known(Set.of());
        var available = new Prerequisite(Status.AVAILABLE, "selftest");
        var context = new ProjectDesignContext("register-selftest", "v1", "com.example.generated", names, names,
            names, ProjectDesignContext.Names.known(Set.of("FILE")), targets, true,
            new RuntimeDesignSnapshot("register-selftest", true, java.util.Map.of(), java.util.Map.of(
                "REGISTER_FACTS", available, "ET6_ET7_REGISTER_CLASSIFICATION", available,
                "REGISTER_ENGINE_OPERATIONS", available)));
        Fixture generated = fixture(draft, context);
        Path jar = Files.createTempFile(workRoot, "register-selftest-", ".jar");
        Files.writeString(jar, "explicit synthetic register dependency, not actual acceptance");
        return new V2Fixture(generated, jar, "org.ipro:platform-register:jar::1.0-SELFTEST", ApplyHash.sha256(jar));
    }

    private static void registerCases(List<CaseResult> results, Path workRoot) {
        for (String period : List.of("NONE", "DAY")) {
            for (String scenario : List.of("noop", "foreign", "modified", "rollback", "generator", "recipe",
                    "source", "c-profile", "c-generator", "c-recipe", "suite", "dependency", "receipt", "prerequisite",
                    "multiple-entries", "unsafe-source-root", "missing-register-dependency")) {
                ApplyStatus status = switch (scenario) {
                    case "noop" -> ApplyStatus.NOOP;
                    case "foreign", "modified" -> ApplyStatus.CONFLICT;
                    case "rollback" -> ApplyStatus.ROLLED_BACK;
                    case "receipt" -> ApplyStatus.RECOVERY_REQUIRED;
                    default -> ApplyStatus.REJECTED;
                };
                String code = switch (scenario) {
                    case "noop" -> "NOOP";
                    case "foreign" -> "CONFLICT_UNOWNED";
                    case "modified" -> "CONFLICT_MODIFIED";
                    case "rollback" -> "RECEIPT_WRITE_FAILED";
                    case "generator" -> "UNSUPPORTED_GENERATOR_PROFILE";
                    case "recipe", "multiple-entries" -> "UNSUPPORTED_REGISTER_PROFILE";
                    case "source" -> "PLAN_MISMATCH";
                    case "c-profile", "c-generator", "c-recipe" -> "C_SUMMARY_MISMATCH";
                    case "suite" -> "C_TESTS_INCOMPLETE";
                    case "dependency" -> "DEPENDENCIES_CHANGED";
                    case "receipt" -> "LEDGER_INVALID";
                    case "prerequisite" -> "CONTROL_PLAN_REJECTED";
                    case "unsafe-source-root" -> "SOURCE_ROOT_INVALID";
                    case "missing-register-dependency" -> "C_REGISTER_DEPENDENCY_MISSING";
                    default -> throw new AssertionError(scenario);
                };
                resultCase(results, workRoot, "register-" + period.toLowerCase() + "-" + scenario, status, code, () -> {
                    V2Fixture fixture = registerFixture(workRoot, period);
                    Project project = projectV2(workRoot, fixture);
                    GenerationPlan plan = fixture.fixture.plan;
                    ApplyResult result;
                    if (scenario.equals("noop")) {
                        assertAppliedV2(project, fixture, applyV2(project, fixture));
                        var before = fileSnapshot(project.root);
                        result = applyV2(project, fixture);
                        if (!before.equals(fileSnapshot(project.root))) throw new AssertionError("NOOP changed files");
                    } else if (scenario.equals("foreign")) {
                        Path target = target(project, fixture.fixture);
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, plan.files().getFirst().content());
                        result = applyV2(project, fixture);
                        if (!ApplyHash.sha256(target).equals(plan.files().getFirst().sha256())) throw new AssertionError("Foreign file changed");
                    } else if (scenario.equals("modified")) {
                        assertAppliedV2(project, fixture, applyV2(project, fixture));
                        Files.writeString(target(project, fixture.fixture), "manual edit");
                        result = applyV2(project, fixture);
                        if (!Files.readString(target(project, fixture.fixture)).equals("manual edit")) throw new AssertionError("Manual edit overwritten");
                    } else if (scenario.equals("rollback")) {
                        result = new GenerationApplyAdapter(new ApplyHooks() {
                            @Override public void beforeReceipt(Path receipt) throws IOException { throw new IOException("injected register receipt failure"); }
                        }).apply(v2Request(project, fixture), plan, plan.source(), plan.context());
                        assertNoTarget(project, fixture.fixture);
                        assertNoV2Receipt(project);
                        if (Files.exists(ApplyStateV2.journalPath(project.root.resolve(".g1-apply-v2")))) throw new AssertionError("Journal survived rollback");
                    } else if (scenario.equals("generator")) {
                        var changed = new GenerationPlan(plan.planId(), plan.recipe(), "register-java-v999", plan.source(), plan.context(), plan.files());
                        result = new GenerationApplyAdapter().apply(v2Request(project, fixture), changed, changed.source(), changed.context());
                    } else if (scenario.equals("multiple-entries")) {
                        var first = plan.files().getFirst();
                        var extra = new GenerationFile(first.operation(), "com/example/generated/Extra.java", first.content(), first.sha256());
                        var changed = new GenerationPlan(plan.planId(), plan.recipe(), plan.generatorVersion(), plan.source(), plan.context(), List.of(first, extra));
                        result = new GenerationApplyAdapter().apply(v2Request(project, fixture), changed, changed.source(), changed.context());
                    } else if (scenario.equals("unsafe-source-root")) {
                        result = new GenerationApplyAdapter().apply(new ApplyRequest(project.root, "../outside", project.summary,
                            List.of(new DependencyLocation(fixture.id, fixture.jar))), plan, plan.source(), plan.context());
                    } else if (scenario.equals("recipe")) {
                        var d = plan.source();
                        var source = new EntityDraft(d.schemaVersion(), RecipeCatalog.STANDARD_DOCUMENT, d.draftId(), d.entity(), d.fields(), d.sections(), d.owner(), d.register());
                        var changed = new GenerationPlan(plan.planId(), source.recipe(), plan.generatorVersion(), source, plan.context(), plan.files());
                        result = new GenerationApplyAdapter().apply(v2Request(project, fixture), changed, source, changed.context());
                    } else if (scenario.equals("source")) {
                        var file = plan.files().getFirst();
                        String content = file.content() + "// modified reviewed source\n";
                        var changed = new GenerationPlan(plan.planId(), plan.recipe(), plan.generatorVersion(), plan.source(), plan.context(),
                            List.of(new GenerationFile(file.operation(), file.relativePath(), content, ApplyHash.sha256(content))));
                        result = new GenerationApplyAdapter().apply(v2Request(project, fixture), changed, changed.source(), changed.context());
                    } else if (scenario.startsWith("c-") || scenario.equals("suite") || scenario.equals("missing-register-dependency")) {
                        ObjectNode c = baseSummaryV2(fixture.fixture, fixture.jar, fixture.id);
                        if (scenario.equals("c-profile")) ((ObjectNode)c.get("manifest")).put("fingerprintProfile", "plan-fingerprint-v2");
                        if (scenario.equals("c-generator")) ((ObjectNode)c.get("manifest")).put("generatorVersion", "document-java-v4");
                        if (scenario.equals("c-recipe")) ((ObjectNode)c.get("manifest").get("recipe")).put("id", "recorded-register-v1");
                        if (scenario.equals("suite")) ((ObjectNode)c.get("tests").get("suites").get(0)).put("name", "com.example.generated.harness.GenerationScenarioTest");
                        if (scenario.equals("missing-register-dependency")) ((ObjectNode)c.get("dependencies").get(0)).put("id", "org.ipro:fixture-platform:jar::1.0-SELFTEST");
                        Files.writeString(project.summary, Json.write(c));
                        result = applyV2(project, fixture);
                    } else if (scenario.equals("dependency")) {
                        Files.writeString(fixture.jar, "changed bytes");
                        result = applyV2(project, fixture);
                    } else if (scenario.equals("receipt")) {
                        ApplyResult first = applyV2(project, fixture);
                        assertAppliedV2(project, fixture, first);
                        ObjectNode receipt = (ObjectNode) Json.MAPPER.readTree(Files.readString(first.receipt()));
                        ((ObjectNode)receipt.get("manifest")).put("fingerprintProfile", "plan-fingerprint-v2");
                        Files.writeString(first.receipt(), Json.write(receipt));
                        var before = fileSnapshot(project.root);
                        result = applyV2(project, fixture);
                        if (!before.equals(fileSnapshot(project.root))) throw new AssertionError("Invalid receipt attempt changed files");
                    } else {
                        var facts = new java.util.HashMap<>(plan.context().runtimeSnapshot().prerequisites());
                        facts.put("REGISTER_ENGINE_OPERATIONS", new Prerequisite(Status.UNAVAILABLE, "not installed"));
                        var context = plan.context().withRuntimeSnapshot(new RuntimeDesignSnapshot("unavailable", true, java.util.Map.of(), facts));
                        var changed = new GenerationPlan(plan.planId(), plan.recipe(), plan.generatorVersion(), plan.source(), context, plan.files());
                        result = new GenerationApplyAdapter().apply(v2Request(project, fixture), changed, changed.source(), changed.context());
                    }
                    expect(result, status, code);
                    if (status == ApplyStatus.REJECTED) assertNoTarget(project, fixture.fixture);
                    return outcome(result, project, fixture.fixture);
                });
            }
        }
    }

    private static ApplyResult applyV2(Project project, V2Fixture fixture) {
        return new GenerationApplyAdapter().apply(v2Request(project, fixture),
                fixture.fixture.plan, fixture.fixture.draft, fixture.fixture.context);
    }

    private static ApplyResult applyV2Multi(Project project, V2Fixture fixture) {
        return applyV2Multi(project, fixture, ApplyHooks.NONE);
    }

    private static ApplyResult applyV2Multi(Project project, V2Fixture fixture, ApplyHooks hooks) {
        return new GenerationApplyAdapter(hooks, (reviewed, draft, context) -> reviewed)
                .apply(v2Request(project, fixture), fixture.fixture.plan,
                        fixture.fixture.draft, fixture.fixture.context);
    }

    private static void assertAppliedV2(Project project, V2Fixture fixture, ApplyResult result)
            throws IOException, ApplyFailure {
        if (result.status() != ApplyStatus.APPLIED) {
            throw new AssertionError("Expected APPLIED, got " + result.status() + "/" + result.code());
        }
        for (GenerationFile file : fixture.fixture.plan.files()) {
            Path target = targetOf(project.root, file);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new AssertionError("v2 target missing: " + file.relativePath());
            }
            if (!ApplyHash.sha256(target).equals(file.sha256())) {
                throw new AssertionError("v2 target hash mismatch: " + file.relativePath());
            }
        }
        Path state = project.root.resolve(".g1-apply-v2");
        ApplyStateV2.Receipt receipt = ApplyStateV2.readReceipt(
                ApplyStateV2.receiptPath(state, "src/main/java", fixture.fixture.plan.planId()));
        if (!receipt.manifest().equals(org.ipro.design.generation.GenerationManifest.from(
                fixture.fixture.plan, EntityJavaGenerator.fingerprintProfile(fixture.fixture.plan.recipe())))) {
            throw new AssertionError("v2 receipt manifest mismatch");
        }
        if (receipt.entries().size() != fixture.fixture.plan.files().size()) {
            throw new AssertionError("v2 receipt entry count mismatch");
        }
        for (int index = 0; index < fixture.fixture.plan.files().size(); index++) {
            GenerationFile file = fixture.fixture.plan.files().get(index);
            ApplyStateV2.Entry entry = receipt.entries().get(index);
            if (!entry.relativePath().equals(file.relativePath())
                    || !entry.expectedSha256().equals(file.sha256())) {
                throw new AssertionError("v2 receipt entry mismatch: " + file.relativePath());
            }
        }
        if (Files.exists(ApplyStateV2.journalPath(state))) {
            throw new AssertionError("v2 journal left after APPLIED");
        }
    }

    private static void assertNoV2Receipt(Project project) throws IOException {
        Path receipts = project.root.resolve(".g1-apply-v2").resolve("receipts");
        if (!Files.exists(receipts)) return;
        try (var paths = Files.walk(receipts)) {
            if (paths.anyMatch(path -> path.getFileName().toString().endsWith(".json"))) {
                throw new AssertionError("v2 receipt left after rollback");
            }
        }
    }

    private static EntityDraft changedDraft(EntityDraft draft) {
        EntityProperties entity = draft.entity();
        return draft.withEntity(new EntityProperties(entity.className(), entity.packageName(),
                entity.label() + " (другой)", entity.titles(), entity.tableName(),
                entity.subsystemId(), entity.icon()));
    }

    private static EntityDraft emptyDraft() {
        return draft(entity("EmptyCatalog", "Пустой справочник", "Пустые справочники", "Пустой справочник",
                "Выбор пустого справочника", "empty_catalog", "FILE"), List.of());
    }

    private static EntityDraft fullDraft() {
        return draft(entity("FutureCatalog", "Будущий справочник", "Будущие справочники", "Будущий справочник",
                "Выбор справочника", "future_catalog", "BOOK"), List.of(
                field(1, "article", "Артикул", "STRING", new ConstraintDraft("100", "", ""), true,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(true, true, true, false)),
                field(2, "amount", "Количество", "INTEGER", ConstraintDraft.empty(), false,
                        FieldDraft.UiRequired.OPTIONAL, new FieldApplications(false, false, false, false)),
                field(3, "total", "Итого", "LONG", ConstraintDraft.empty(), false,
                        FieldDraft.UiRequired.REQUIRED, new FieldApplications(true, false, false, false)),
                field(4, "price", "Цена", "DECIMAL", new ConstraintDraft("", "19", "4"), true,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(true, true, true, false)),
                field(5, "active", "Активен", "BOOLEAN", ConstraintDraft.empty(), false,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(false, true, true, false)),
                field(6, "startDate", "Дата начала", "DATE", ConstraintDraft.empty(), false,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(true, true, false, false)),
                field(7, "startedAt", "Момент начала", "DATETIME", ConstraintDraft.empty(), false,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(true, false, true, false)),
                field(8, "listOnly", "Только в списке", "STRING", new ConstraintDraft("40", "", ""), false,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(false, true, false, false)),
                field(9, "selectionOnly", "Только в выборе", "STRING", new ConstraintDraft("40", "", ""), false,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(false, false, true, false)),
                field(10, "serviceCode", "Служебный код", "STRING", new ConstraintDraft("20", "", ""), false,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(false, false, false, false))));
    }

    private static EntityDraft draft(EntityProperties entity, List<FieldDraft> fields) {
        return new EntityDraft(1, RecipeCatalog.STANDARD_CATALOG_V2, DRAFT_ID, entity, fields, List.of());
    }

    private static EntityProperties entity(String className, String label, String listTitle, String itemTitle,
                                           String selectionTitle, String tableName, String icon) {
        return new EntityProperties(className, DraftText.explicit("com.example.generated"), label,
                new EntityProperties.Titles(DraftText.explicit(listTitle), DraftText.explicit(itemTitle),
                        DraftText.explicit(selectionTitle)), DraftText.explicit(tableName), null, icon);
    }

    private static FieldDraft field(long number, String name, String label, String type,
                                    ConstraintDraft constraints, boolean serverRequired,
                                    FieldDraft.UiRequired uiRequired, FieldApplications applications) {
        return new FieldDraft(new UUID(1, number), name, label, type, DraftText.auto(), serverRequired,
                uiRequired, constraints, null, applications);
    }

    private static ProjectDesignContext context() {
        return new ProjectDesignContext("fixture-project", "v1", "com.example.generated",
                ProjectDesignContext.Names.known(Set.of()), ProjectDesignContext.Names.known(Set.of()),
                ProjectDesignContext.Names.known(Set.of()), ProjectDesignContext.Names.known(Set.of("BOOK", "FILE")),
                java.util.Map.of(), true, new RuntimeDesignSnapshot("runtime:generation-1", true,
                java.util.Map.of(), java.util.Map.of(
                "STANDARD_CATALOG_APP_API", new Prerequisite(Status.AVAILABLE, "fixture"),
                "ET0_ET7_RUNTIME", new Prerequisite(Status.AVAILABLE, "fixture"))));
    }

    private static Outcome outcome(ApplyResult result, Project project, Fixture fixture) {
        return new Outcome(result, project, fixture);
    }

    private static void expect(ApplyResult result, ApplyStatus status, String code) {
        if (result.status() != status || !codeMatches(code, result.code())) {
            throw new AssertionError("expected " + status + "/" + code + " but got "
                    + result.status() + "/" + result.code() + ": " + result.message());
        }
    }

    private record Fixture(EntityDraft draft, ProjectDesignContext context, GenerationPlan plan) {
    }

    private record V2Fixture(Fixture fixture, Path jar, String id, String sha256) {
    }

    private static final class Project {
        final Path root;
        Path summary;

        Project(Path root, Path summary) {
            this.root = root;
            this.summary = summary;
        }
    }

    private record Outcome(ApplyResult result, Project project, Fixture fixture) {
    }

    private record CaseResult(String name, String status, String expected, String actual, String detail,
                              Path project, Path target, Path receipt, Path journal) {
    }

    @FunctionalInterface
    private interface CheckedSupplier {
        Outcome get() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}

package org.ipro.design.apply;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.ipro.design.DraftValidator;
import org.ipro.design.EntityDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.ValidationResult;
import org.ipro.design.generation.EntityJavaGenerator;
import org.ipro.design.generation.GenerationFile;
import org.ipro.design.generation.GenerationManifest;
import org.ipro.design.generation.GenerationPlan;
import org.ipro.design.generation.GenerationResult;

/**
 * Extension apply profile D2: the whole reviewed CREATE set bound to a C summary v2 and its full
 * manifest. It keeps its own {@code .g1-apply-v2} ledger; the legacy catalog profile retains D1.
 * One-file plans keep the original profile; N-file plans preflight every target before the first
 * write, then publish entries in manifest order and roll back only proven own outputs.
 */
final class GenerationApplyV2 {
    static final String DOCUMENT_GENERATOR = "document-java-v2";
    private static final Set<String> SUPPORTED_GENERATORS = Set.of(
            "catalog-java-v2", "document-java-v2",
            "catalog-java-v3", "document-java-v3",
            "catalog-java-v4", "document-java-v4", "register-java-v1");
    private static final String OWNER_ATTRIBUTE = "g1.apply";

    private final ApplyHooks hooks;
    private final PlanRebuilder planRebuilder;

    GenerationApplyV2(ApplyHooks hooks, PlanRebuilder planRebuilder) {
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.planRebuilder = Objects.requireNonNull(planRebuilder, "planRebuilder");
    }

    ApplyResult apply(ApplyRequest request, GenerationPlan reviewedPlan,
                      EntityDraft currentDraft, ProjectDesignContext currentContext) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reviewedPlan, "reviewedPlan");
        try {
            return applyChecked(request, reviewedPlan, currentDraft, currentContext);
        } catch (ApplyFailure failure) {
            return new ApplyResult(failure.status(), failure.code(), failure.getMessage(), null,
                    reviewedPlan.planId(), null, null, null, null, null);
        }
    }

    private ApplyResult applyChecked(ApplyRequest request, GenerationPlan plan, EntityDraft draft,
                                     ProjectDesignContext context) throws ApplyFailure {
        List<GenerationFile> files = validateScope(plan);
        validateFreshness(plan, draft, context);
        ApplyPaths.Resolved roots = ApplyPaths.resolveV2(request.projectRoot(), request.sourceRoot());
        List<Path> targets = new ArrayList<>();
        Set<String> targetKeys = new HashSet<>();
        for (GenerationFile file : files) {
            Path target = ApplyPaths.target(roots, file.relativePath());
            if (!targetKeys.add(target.toString().toLowerCase(java.util.Locale.ROOT))) {
                throw new ApplyFailure(ApplyStatus.REJECTED, "TARGET_CASE_COLLISION",
                        "The reviewed plan contains targets that collide on this filesystem: "
                                + file.relativePath());
            }
            targets.add(target);
        }

        CSummaryV2 summary = CSummaryV2.read(request.cSummary());
        summary.verify(plan, files, EntityJavaGenerator.fingerprintProfile(plan.recipe()));

        ApplyPaths.ensureStateRootV2(roots);
        LockHandle lock = LockHandle.acquire(roots.stateRoot());
        try {
            List<ApplyStateV2.Dependency> verified =
                    verifyDependencies(summary.dependencies(), request.currentDependencies());
            return applyLocked(roots, targets, plan, files, summary, verified,
                    request.currentDependencies(), request.cSummary());
        } finally {
            try {
                lock.close();
            } catch (IOException ignored) {
                // The outcome is already determined; the OS releases the lock at process exit.
            }
        }
    }

    private ApplyResult applyLocked(ApplyPaths.Resolved roots, List<Path> targets, GenerationPlan plan,
                                    List<GenerationFile> files, CSummaryV2 summary,
                                    List<ApplyStateV2.Dependency> verified,
                                    List<DependencyLocation> requestDependencies, Path summaryPath)
            throws ApplyFailure {
        ApplyPaths.assertNoLinks(roots.stateRoot(), "STATE_ROOT_UNSAFE");
        Path target = targets.getFirst();
        GenerationFile file = files.getFirst();
        Path journalPath = ApplyStateV2.journalPath(roots.stateRoot());
        if (ApplyStateV2.readJournal(journalPath).isPresent()) {
            return recovery("JOURNAL_INCOMPLETE",
                    "An unfinished v2 apply journal exists; inspect it and recover explicitly",
                    plan.planId(), target, receiptPath(roots, plan.planId()), journalPath, file, summary);
        }

        List<ApplyStateV2.Receipt> receipts = ApplyStateV2.readReceipts(roots.stateRoot());
        List<ApplyStateV2.Receipt> own = receipts.stream()
                .filter(receipt -> receipt.manifest().planId().equals(plan.planId())
                        && receipt.sourceRoot().equals(roots.sourceRootRelative()))
                .toList();
        if (own.size() > 1) {
            return recovery("LEDGER_DUPLICATE",
                    "The v2 ledger contains more than one receipt for the reviewed plan",
                    plan.planId(), target, receiptPath(roots, plan.planId()), journalPath, file, summary);
        }
        ApplyStateV2.Receipt receipt = own.isEmpty() ? null : own.getFirst();
        if (receipt != null) {
            return noopOrConflict(roots, targets, plan, files, summary, verified, receipt,
                    journalPath, summaryPath);
        }

        Map<String, Path> plannedTargets = new TreeMap<>();
        for (int index = 0; index < files.size(); index++) {
            plannedTargets.put(files.get(index).relativePath(), targets.get(index));
        }
        Set<String> plannedPaths = plannedTargets.keySet();
        List<ApplyStateV2.Receipt> targetReceipts = receipts.stream()
                .filter(candidate -> candidate.sourceRoot().equals(roots.sourceRootRelative())
                        && candidate.entries().stream()
                        .anyMatch(entry -> plannedPaths.contains(entry.relativePath())))
                .toList();
        Path receiptPath = receiptPath(roots, plan.planId());
        if (!targetReceipts.isEmpty()) {
            for (ApplyStateV2.Receipt candidate : targetReceipts) {
                for (ApplyStateV2.Entry entry : candidate.entries()) {
                    Path owned = plannedTargets.get(entry.relativePath());
                    if (owned == null) continue;
                    if (!Files.exists(owned, LinkOption.NOFOLLOW_LINKS)) {
                        return recovery("ORPHAN_RECEIPT",
                                "A v2 receipt owns a target in this set but that target is missing",
                                plan.planId(), target, receiptPath, journalPath, file, summary);
                    }
                }
            }
            return conflict("CONFLICT_OTHER_PLAN",
                    "A target in this set is owned by a different plan receipt",
                    plan.planId(), target, receiptPath, journalPath, file, summary);
        }
        for (Path candidate : targets) {
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return conflict("CONFLICT_UNOWNED",
                        "A target exists without a matching receipt; identical content is not ownership",
                        plan.planId(), target, receiptPath, journalPath, file, summary);
            }
        }
        return create(roots, targets, plan, files, summary, verified, requestDependencies, summaryPath);
    }

    private ApplyResult noopOrConflict(ApplyPaths.Resolved roots, List<Path> targets, GenerationPlan plan,
                                       List<GenerationFile> files, CSummaryV2 summary,
                                       List<ApplyStateV2.Dependency> verified,
                                       ApplyStateV2.Receipt receipt, Path journalPath,
                                       Path summaryPath) throws ApplyFailure {
        Path target = targets.getFirst();
        GenerationFile file = files.getFirst();
        Path receiptPath = receiptPath(roots, plan.planId());
        GenerationManifest expected = GenerationManifest.from(plan,
                EntityJavaGenerator.fingerprintProfile(plan.recipe()));
        if (!receipt.manifest().equals(expected)
                || !receipt.sourceRoot().equals(roots.sourceRootRelative())
                || receipt.entries().size() != files.size()) {
            return recovery("LEDGER_INVALID",
                    "The v2 receipt does not match the reviewed plan or current source root",
                    plan.planId(), target, receiptPath, journalPath, file, summary);
        }
        for (int index = 0; index < files.size(); index++) {
            GenerationFile expectedFile = files.get(index);
            ApplyStateV2.Entry entry = receipt.entries().get(index);
            if (!entry.relativePath().equals(expectedFile.relativePath())
                    || !entry.expectedSha256().equals(expectedFile.sha256())) {
                return recovery("LEDGER_INVALID",
                        "The v2 receipt does not match the reviewed plan or current source root",
                        plan.planId(), target, receiptPath, journalPath, file, summary);
            }
        }
        if (!receipt.cSummarySha256().equals(summary.sha256())) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "C_SUMMARY_CHANGED",
                    "NOOP requires the same C summary bytes that were used for CREATE");
        }
        if (!sameDependencies(receipt.verifiedDependencies(), verified)) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "DEPENDENCIES_CHANGED",
                    "NOOP requires the same verified dependency set that was used for CREATE");
        }
        for (Path candidate : targets) {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return recovery("OWNED_TARGET_MISSING",
                        "A completed v2 receipt exists but one of its targets is missing; re-create is not automatic",
                        plan.planId(), target, receiptPath, journalPath, file, summary);
            }
        }
        for (int index = 0; index < files.size(); index++) {
            GenerationFile expectedFile = files.get(index);
            Path candidate = targets.get(index);
            if (!isRegular(candidate) || !expectedFile.sha256().equals(hashOrConflict(candidate))) {
                return conflict("CONFLICT_MODIFIED",
                        "A target exists but is no longer the file recorded by this plan",
                        plan.planId(), candidate, receiptPath, journalPath, expectedFile, summary);
            }
        }
        summary.verifyUnchanged(summaryPath, ApplyStatus.REJECTED);
        return new ApplyResult(ApplyStatus.NOOP, "NOOP",
                "The same plan, C summary, dependencies and every unchanged target are already applied",
                receipt.applyId(), plan.planId(), target, receiptPath, journalPath,
                file.sha256(), summary.sha256());
    }

    private ApplyResult create(ApplyPaths.Resolved roots, List<Path> targets, GenerationPlan plan,
                               List<GenerationFile> files, CSummaryV2 summary,
                               List<ApplyStateV2.Dependency> verified,
                               List<DependencyLocation> requestDependencies, Path summaryPath)
            throws ApplyFailure {
        String applyId = UUID.randomUUID().toString();
        String createdAt = ApplyStateV2.now();
        Path journalPath = ApplyStateV2.journalPath(roots.stateRoot());
        Path receiptPath = receiptPath(roots, plan.planId());
        List<Path> temps = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            temps.add(targets.get(index).getParent().resolve(".g1-" + applyId + "-" + index + ".tmp"));
        }
        GenerationManifest manifest = summary.manifest();
        Mutation mutation = new Mutation(applyId, plan, files, roots, targets, temps, receiptPath,
                journalPath, summary.sha256(), verified, requestDependencies, manifest, createdAt);
        try {
            mutation.journal = new ApplyStateV2.Journal(applyId, manifest, summary.sha256(),
                    verified, roots.projectRoot().toString(), roots.sourceRootRelative(), createdAt,
                    List.of(), List.copyOf(mutation.entries), ApplyStateV2.Phase.PREPARED, -1);
            ApplyStateV2.writeJournal(journalPath, mutation.journal);

            for (Path target : targets) {
                ApplyPaths.createParentDirectories(target.getParent(), roots.sourceRoot(),
                        mutation.createdDirs);
            }
            mutation.journal = mutation.journal
                    .withCreatedDirs(relativeDirectories(roots.projectRoot(), mutation.createdDirs))
                    .withPhase(ApplyStateV2.Phase.DIRECTORIES, -1);
            ApplyStateV2.writeJournal(journalPath, mutation.journal);

            for (Path target : targets) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ApplyFailure(ApplyStatus.ROLLED_BACK, "CONFLICT_LATE_TARGET",
                            "A target appeared before publication; existing data was preserved");
                }
            }
            for (int index = 0; index < files.size(); index++) {
                GenerationFile file = files.get(index);
                Path target = targets.get(index);
                Path temp = temps.get(index);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ApplyFailure(ApplyStatus.ROLLED_BACK, "CONFLICT_LATE_TARGET",
                            "Target appeared before publication; existing data was preserved");
                }
                hooks.beforePublish(target);
                ApplyPaths.assertSafePublicationPath(target, roots);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ApplyFailure(ApplyStatus.ROLLED_BACK, "CONFLICT_LATE_TARGET",
                            "Target appeared before publication; existing data was preserved");
                }

                byte[] content = file.content().getBytes(StandardCharsets.UTF_8);
                Files.write(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                force(temp);
                if (!file.sha256().equals(ApplyHash.sha256(temp))) {
                    throw new ApplyFailure(ApplyStatus.ROLLED_BACK, "TEMP_HASH_MISMATCH",
                            "The temp file bytes do not match the reviewed plan");
                }
                mutation.journal = mutation.journal.withPhase(ApplyStateV2.Phase.TEMP_WRITTEN, index);
                ApplyStateV2.writeJournal(journalPath, mutation.journal);

                try {
                    Files.createLink(target, temp);
                } catch (FileAlreadyExistsException ex) {
                    throw new ApplyFailure(ApplyStatus.ROLLED_BACK, "CONFLICT_LATE_TARGET",
                            "Target appeared at publication; no-replace refused the link", ex);
                } catch (UnsupportedOperationException ex) {
                    throw new ApplyFailure(ApplyStatus.REJECTED, "PUBLICATION_UNSUPPORTED",
                            "The filesystem does not support the required no-replace publication", ex);
                } catch (IOException ex) {
                    if (isUnsupported(ex)) {
                        throw new ApplyFailure(ApplyStatus.REJECTED, "PUBLICATION_UNSUPPORTED",
                                "The filesystem does not support the required no-replace publication", ex);
                    }
                    throw new ApplyFailure(ApplyStatus.ROLLED_BACK, "PUBLICATION_FAILED",
                            "No-replace publication failed", ex);
                }
                mutation.published[index] = true;
                if (!isRegular(target) || !file.sha256().equals(ApplyHash.sha256(target))) {
                    throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TARGET_HASH_MISMATCH",
                            "A published target cannot be proven to be this apply's content");
                }
                mutation.entries.set(index, mutation.entries.get(index).published(
                        identity(target, applyId, file.sha256()), file.sha256()));
                mutation.journal = mutation.journal.withEntry(index, mutation.entries.get(index))
                        .withPhase(ApplyStateV2.Phase.PUBLISHED, index);
                ApplyStateV2.writeJournal(journalPath, mutation.journal);
                hooks.afterPublish(target);
                assertTargetsUnchanged(mutation);
            }
            summary.verifyUnchanged(summaryPath, ApplyStatus.ROLLED_BACK);

            for (Path temp : temps) {
                try {
                    Files.delete(temp);
                } catch (IOException ex) {
                    throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TEMP_DELETE_FAILED",
                            "A published target exists but its temp link could not be removed", ex);
                }
            }
            for (int index = 0; index < mutation.entries.size(); index++) {
                mutation.entries.set(index, mutation.entries.get(index).withTemp(null));
            }
            mutation.journal = mutation.journal
                    .withEntries(mutation.entries)
                    .withPhase(ApplyStateV2.Phase.VERIFIED, files.size() - 1);
            ApplyStateV2.writeJournal(journalPath, mutation.journal);

            List<ApplyStateV2.Dependency> afterPublish;
            try {
                afterPublish = verifyDependencies(summary.dependencies(), mutation.requestDependencies);
            } catch (ApplyFailure changed) {
                throw new ApplyFailure(ApplyStatus.ROLLED_BACK, changed.code(), changed.getMessage(), changed);
            }
            if (!sameDependencies(verified, afterPublish)) {
                throw new ApplyFailure(ApplyStatus.ROLLED_BACK, "DEPENDENCIES_CHANGED",
                        "Dependency bytes changed before the durable receipt");
            }

            assertTargetsUnchanged(mutation);
            ApplyStateV2.Receipt receipt = new ApplyStateV2.Receipt(applyId, manifest,
                    summary.sha256(), verified, roots.projectRoot().toString(),
                    roots.sourceRootRelative(), createdAt,
                    relativeDirectories(roots.projectRoot(), mutation.createdDirs),
                    List.copyOf(mutation.entries));
            try {
                hooks.beforeReceipt(receiptPath);
                ApplyStateV2.writeReceipt(receiptPath, receipt);
            } catch (FileAlreadyExistsException ex) {
                throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "RECEIPT_EXISTS",
                        "A v2 receipt appeared concurrently; ownership is ambiguous", ex);
            } catch (IOException ex) {
                if (Files.exists(receiptPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "RECEIPT_STATE_UNKNOWN",
                            "The v2 receipt may exist but cannot be proven", ex);
                }
                throw new ApplyFailure(ApplyStatus.ROLLED_BACK, "RECEIPT_WRITE_FAILED",
                        "Could not persist the v2 apply receipt", ex);
            }
            mutation.receiptCommitted = true;
            hooks.afterReceipt(receiptPath);
            summary.verifyUnchanged(summaryPath, ApplyStatus.RECOVERY_REQUIRED);
            assertTargetsUnchanged(mutation);
            mutation.journal = mutation.journal.withPhase(
                    ApplyStateV2.Phase.RECEIPT_WRITTEN, files.size() - 1);
            try {
                ApplyStateV2.writeJournal(journalPath, mutation.journal);
            } catch (IOException ex) {
                throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_WRITE_FAILED",
                        "The durable receipt exists but the journal could not record it", ex);
            }

            try {
                Files.delete(journalPath);
            } catch (IOException ex) {
                return recoveryWithJournal("JOURNAL_DELETE_FAILED",
                        "Targets and receipt exist but the v2 journal could not be closed", mutation);
            }
            String message = files.size() == 1
                    ? "Created one Java source file and recorded its v2 receipt"
                    : "Created " + files.size() + " Java source files and recorded their v2 receipt";
            return new ApplyResult(ApplyStatus.APPLIED, "APPLIED", message,
                    applyId, plan.planId(), targets.getFirst(), receiptPath, journalPath,
                    files.getFirst().sha256(), summary.sha256());
        } catch (ApplyFailure failure) {
            return rollback(failure, mutation);
        } catch (IOException ex) {
            return rollback(new ApplyFailure(ApplyStatus.ROLLED_BACK, "APPLY_IO",
                    "File apply failed: " + ex.getMessage(), ex), mutation);
        }
    }

    private ApplyResult rollback(ApplyFailure failure, Mutation mutation) throws ApplyFailure {
        if (failure.status() == ApplyStatus.RECOVERY_REQUIRED || mutation.receiptCommitted) {
            return recoveryWithJournal(failure.code(), failure.getMessage(), mutation);
        }
        try {
            if (Files.exists(mutation.receiptPath, LinkOption.NOFOLLOW_LINKS)) {
                ApplyStateV2.Receipt receipt = ApplyStateV2.readReceipt(mutation.receiptPath);
                if (!receipt.applyId().equals(mutation.applyId)
                        || !receipt.manifest().planId().equals(mutation.plan.planId())) {
                    throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "RECEIPT_MODIFIED",
                            "The v2 receipt no longer belongs to this apply");
                }
                try {
                    Files.delete(mutation.receiptPath);
                } catch (IOException ex) {
                    throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "RECEIPT_DELETE_FAILED",
                            "Cannot remove this apply's v2 receipt", ex);
                }
            }
            for (int index = 0; index < mutation.files.size(); index++) {
                if (mutation.published[index]) {
                    deleteOwnedTarget(mutation, index);
                }
            }
            for (int index = 0; index < mutation.temps.size(); index++) {
                deleteOwnedTemp(mutation, index);
            }
            deleteOwnedJournal(mutation);
            deleteCreatedDirectories(mutation);
        } catch (ApplyFailure cleanup) {
            return recoveryWithJournal(cleanup.code(),
                    failure.getMessage() + "; cleanup could not be proven: " + cleanup.getMessage(),
                    mutation);
        }
        return new ApplyResult(failure.status(), failure.code(), failure.getMessage(),
                mutation.applyId, mutation.plan.planId(), mutation.targets.getFirst(),
                mutation.receiptPath, mutation.journalPath, mutation.files.getFirst().sha256(),
                mutation.summarySha256);
    }

    private void deleteOwnedTarget(Mutation mutation, int index) throws ApplyFailure {
        Path target = mutation.targets.get(index);
        GenerationFile file = mutation.files.get(index);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        if (!isRegular(target) || !file.sha256().equals(hashOrRecovery(target))) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TARGET_MODIFIED",
                    "A target changed after publication; it is preserved");
        }
        if (!matchesIdentity(target, mutation.entries.get(index).publishedIdentity())) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TARGET_IDENTITY_CHANGED",
                    "A published file identity cannot be proven; it is preserved");
        }
        try {
            Files.delete(target);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TARGET_DELETE_FAILED",
                    "Cannot remove this apply's unchanged target", ex);
        }
    }

    private void deleteOwnedTemp(Mutation mutation, int index) throws ApplyFailure {
        Path temp = mutation.temps.get(index);
        GenerationFile file = mutation.files.get(index);
        if (!Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) return;
        if (!isRegular(temp) || !file.sha256().equals(hashOrRecovery(temp))) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TEMP_MODIFIED",
                    "A temp file changed; it is preserved");
        }
        try {
            Files.delete(temp);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TEMP_DELETE_FAILED",
                    "Cannot remove this apply's temp file", ex);
        }
    }

    private void deleteOwnedJournal(Mutation mutation) throws ApplyFailure {
        if (!Files.exists(mutation.journalPath, LinkOption.NOFOLLOW_LINKS)) return;
        ApplyStateV2.Journal journal = ApplyStateV2.readJournal(mutation.journalPath)
                .orElseThrow(() -> new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED,
                        "JOURNAL_INVALID", "The v2 journal disappeared while reading it"));
        if (!journal.applyId().equals(mutation.applyId)) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_MODIFIED",
                    "The v2 journal no longer belongs to this apply");
        }
        try {
            Files.delete(mutation.journalPath);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_DELETE_FAILED",
                    "Cannot remove this apply's v2 journal", ex);
        }
    }

    private void deleteCreatedDirectories(Mutation mutation) {
        for (int index = mutation.createdDirs.size() - 1; index >= 0; index--) {
            Path directory = mutation.createdDirs.get(index);
            try {
                BasicFileAttributes attributes = Files.readAttributes(directory,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
                    continue;
                }
                try (var children = Files.list(directory)) {
                    if (children.findAny().isPresent()) continue;
                }
                Files.deleteIfExists(directory);
            } catch (IOException ex) {
                // Empty-directory cleanup is best effort; no target or receipt depends on it.
            }
        }
    }

    private static List<ApplyStateV2.Dependency> verifyDependencies(
            List<CSummaryV2.SummaryDependency> expected,
            List<DependencyLocation> locations) throws ApplyFailure {
        if (locations == null || locations.isEmpty()) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "DEPENDENCIES_UNAVAILABLE",
                    "Current dependency locations are required for the extension apply");
        }
        Map<String, Path> current = new TreeMap<>();
        Set<Path> paths = new HashSet<>();
        for (DependencyLocation location : locations) {
            if (current.containsKey(location.id())) {
                throw new ApplyFailure(ApplyStatus.REJECTED, "DEPENDENCY_SET_MISMATCH",
                        "Current dependency id is duplicated: " + location.id());
            }
            Path path = location.path().toAbsolutePath().normalize();
            if (!paths.add(path)) {
                throw new ApplyFailure(ApplyStatus.REJECTED, "DEPENDENCY_SET_MISMATCH",
                        "Current dependency location is duplicated: " + path);
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(path)) {
                throw new ApplyFailure(ApplyStatus.REJECTED, "DEPENDENCIES_UNAVAILABLE",
                        "Current dependency is missing or not a regular file: " + path);
            }
            try {
                ApplyPaths.assertNoLinks(path, "DEPENDENCIES_UNAVAILABLE");
            } catch (ApplyFailure failure) {
                throw new ApplyFailure(ApplyStatus.REJECTED, "DEPENDENCIES_UNAVAILABLE",
                        "Current dependency is a link or alias: " + path);
            }
            current.put(location.id(), path);
        }
        Set<String> expectedIds = new HashSet<>();
        for (CSummaryV2.SummaryDependency dependency : expected) {
            expectedIds.add(dependency.id());
        }
        if (!expectedIds.equals(current.keySet())) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "DEPENDENCY_SET_MISMATCH",
                    "Current dependency ids do not equal the C summary set");
        }
        List<ApplyStateV2.Dependency> verified = new ArrayList<>();
        for (CSummaryV2.SummaryDependency dependency : expected) {
            Path path = current.get(dependency.id());
            String hash;
            try {
                hash = ApplyHash.sha256(path);
            } catch (IOException ex) {
                throw new ApplyFailure(ApplyStatus.REJECTED, "DEPENDENCIES_UNAVAILABLE",
                        "Cannot read current dependency: " + path, ex);
            }
            if (!hash.equals(dependency.sha256())) {
                throw new ApplyFailure(ApplyStatus.REJECTED, "DEPENDENCIES_CHANGED",
                        "Current dependency bytes differ from C: " + dependency.id());
            }
            verified.add(new ApplyStateV2.Dependency(dependency.id(), hash));
        }
        return List.copyOf(verified);
    }

    private static boolean sameDependencies(List<ApplyStateV2.Dependency> left,
                                            List<ApplyStateV2.Dependency> right) {
        return left.equals(right);
    }

    private List<GenerationFile> validateScope(GenerationPlan plan) throws ApplyFailure {
        if (!SUPPORTED_GENERATORS.contains(plan.generatorVersion())) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "UNSUPPORTED_GENERATOR_PROFILE",
                    "Only " + SUPPORTED_GENERATORS + " is supported by D2");
        }
        if (org.ipro.design.RecipeCatalog.INDEPENDENT_REGISTER.equals(plan.recipe())
                || "register-java-v1".equals(plan.generatorVersion())) {
            if (!org.ipro.design.RecipeCatalog.INDEPENDENT_REGISTER.equals(plan.recipe())
                    || !"register-java-v1".equals(plan.generatorVersion()) || plan.files().size() != 1) {
                throw new ApplyFailure(ApplyStatus.REJECTED, "UNSUPPORTED_REGISTER_PROFILE",
                    "Register D2 requires independent-register-v1@1 / register-java-v1 and one CREATE entry");
            }
        }
        if (plan.files().isEmpty()) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "EMPTY_PLAN",
                    "A D2 plan must contain at least one CREATE entry");
        }
        Set<String> paths = new HashSet<>();
        for (GenerationFile file : plan.files()) {
            if (file.operation() != GenerationFile.Operation.CREATE) {
                throw new ApplyFailure(ApplyStatus.REJECTED, "UNSUPPORTED_OPERATION",
                        "Only CREATE is supported");
            }
            if (!paths.add(file.relativePath().toLowerCase(java.util.Locale.ROOT))) {
                throw new ApplyFailure(ApplyStatus.REJECTED, "TARGET_CASE_COLLISION",
                        "The reviewed plan contains duplicate or case-colliding paths: "
                                + file.relativePath());
            }
            ApplyPaths.validateJavaRelativePath(file.relativePath());
            if (!ApplyHash.sha256(file.content()).equals(file.sha256())) {
                throw new ApplyFailure(ApplyStatus.REJECTED, "PLAN_HASH_MISMATCH",
                        "GenerationFile.sha256 does not match its exact UTF-8 content: "
                                + file.relativePath());
            }
        }
        return List.copyOf(plan.files());
    }

    private void validateFreshness(GenerationPlan plan, EntityDraft draft,
                                   ProjectDesignContext context) throws ApplyFailure {
        if (!plan.appliesTo(draft, context)) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "STALE_INPUT",
                    "The reviewed plan does not apply to the current draft/context");
        }
        GenerationPlan control = planRebuilder.rebuild(plan, draft, context);
        if (!plan.planId().equals(control.planId())
                || !plan.recipe().equals(control.recipe())
                || !plan.generatorVersion().equals(control.generatorVersion())
                || !plan.files().equals(control.files())) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "PLAN_MISMATCH",
                    "The reviewed plan is not the plan produced by the current builder");
        }
    }

    private static ApplyStateV2.PublishedIdentity identity(Path target, String applyId,
                                                           String expectedHash) throws ApplyFailure {
        try {
            BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
                return null;
            }
            String realPath = target.toRealPath().toString();
            Object key = attributes.fileKey();
            if (key != null) {
                return new ApplyStateV2.PublishedIdentity("fileKey:" + key, realPath);
            }
            UserDefinedFileAttributeView view = Files.getFileAttributeView(target,
                    UserDefinedFileAttributeView.class);
            if (view == null) return null;
            String token = applyId + ":" + expectedHash;
            view.write(OWNER_ATTRIBUTE, ByteBuffer.wrap(token.getBytes(StandardCharsets.UTF_8)));
            return new ApplyStateV2.PublishedIdentity("xattr:" + token, realPath);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TARGET_IDENTITY_UNREADABLE",
                    "Cannot read the published file identity: " + target, ex);
        }
    }

    private static boolean matchesIdentity(Path target, ApplyStateV2.PublishedIdentity recorded)
            throws ApplyFailure {
        if (recorded == null) return false;
        try {
            BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
                return false;
            }
            if (!recorded.realPath().equals(target.toRealPath().toString())) return false;
            if (recorded.fileKey().startsWith("fileKey:")) {
                Object key = attributes.fileKey();
                return key != null && recorded.fileKey().equals("fileKey:" + key);
            }
            if (recorded.fileKey().startsWith("xattr:")) {
                UserDefinedFileAttributeView view = Files.getFileAttributeView(target,
                        UserDefinedFileAttributeView.class);
                if (view == null || !view.list().contains(OWNER_ATTRIBUTE)) return false;
                ByteBuffer buffer = ByteBuffer.allocate(view.size(OWNER_ATTRIBUTE));
                view.read(OWNER_ATTRIBUTE, buffer);
                buffer.flip();
                String token = StandardCharsets.UTF_8.decode(buffer).toString();
                return recorded.fileKey().equals("xattr:" + token);
            }
            return false;
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TARGET_IDENTITY_UNREADABLE",
                    "Cannot prove the published file identity: " + target, ex);
        }
    }

    private void assertTargetsUnchanged(Mutation mutation) throws ApplyFailure {
        for (int index = 0; index < mutation.files.size(); index++) {
            if (!mutation.published[index]) continue;
            Path target = mutation.targets.get(index);
            GenerationFile file = mutation.files.get(index);
            if (!isRegular(target) || !file.sha256().equals(hashOrRecovery(target))) {
                throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TARGET_MODIFIED",
                        "A published target changed before completion; it is preserved");
            }
            ApplyStateV2.PublishedIdentity recorded = mutation.entries.get(index).publishedIdentity();
            if (recorded != null && !matchesIdentity(target, recorded)) {
                throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TARGET_IDENTITY_CHANGED",
                        "A published target identity or path changed; it is preserved");
            }
        }
    }

    private boolean isRegular(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink() && !attributes.isOther();
        } catch (IOException ex) {
            return false;
        }
    }

    private String hashOrConflict(Path path) throws ApplyFailure {
        try {
            return ApplyHash.sha256(path);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.CONFLICT, "OWNED_TARGET_UNREADABLE",
                    "Cannot read the existing target; data is preserved: " + path, ex);
        }
    }

    private String hashOrRecovery(Path path) throws ApplyFailure {
        try {
            return ApplyHash.sha256(path);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TARGET_UNREADABLE",
                    "Cannot read the path for safe recovery: " + path, ex);
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static Path receiptPath(ApplyPaths.Resolved roots, String planId) {
        return ApplyStateV2.receiptPath(roots.stateRoot(), roots.sourceRootRelative(), planId);
    }

    private static List<String> relativeDirectories(Path projectRoot, List<Path> directories) {
        List<String> result = new ArrayList<>();
        for (Path directory : directories) {
            result.add(projectRoot.relativize(directory).toString().replace('\\', '/'));
        }
        return result;
    }

    private static boolean isUnsupported(IOException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("not supported") || message.contains("not implemented")
                || message.contains("privilege") || message.contains("operation not permitted");
    }

    private ApplyResult conflict(String code, String message, String planId, Path target,
                                 Path receipt, Path journal, GenerationFile file, CSummaryV2 summary) {
        return new ApplyResult(ApplyStatus.CONFLICT, code, message, null, planId, target, receipt,
                journal, file.sha256(), summary.sha256());
    }

    private ApplyResult recovery(String code, String message, String planId, Path target,
                                 Path receipt, Path journal, GenerationFile file, CSummaryV2 summary) {
        return new ApplyResult(ApplyStatus.RECOVERY_REQUIRED, code, message, null, planId, target,
                receipt, journal, file.sha256(), summary.sha256());
    }

    private ApplyResult recoveryWithJournal(String code, String message, Mutation mutation) {
        return new ApplyResult(ApplyStatus.RECOVERY_REQUIRED, code, message, mutation.applyId,
                mutation.plan.planId(), mutation.targets.getFirst(), mutation.receiptPath,
                mutation.journalPath, mutation.files.getFirst().sha256(), mutation.summarySha256);
    }

    private static final class Mutation {
        final String applyId;
        final GenerationPlan plan;
        final List<GenerationFile> files;
        final ApplyPaths.Resolved roots;
        final List<Path> targets;
        final List<Path> temps;
        final Path receiptPath;
        final Path journalPath;
        final String summarySha256;
        final List<ApplyStateV2.Dependency> verified;
        final GenerationManifest manifest;
        final String createdAt;
        final List<Path> createdDirs = new ArrayList<>();
        final List<DependencyLocation> requestDependencies;
        final List<ApplyStateV2.Entry> entries = new ArrayList<>();
        final boolean[] published;
        ApplyStateV2.Journal journal;
        boolean receiptCommitted;

        Mutation(String applyId, GenerationPlan plan, List<GenerationFile> files,
                 ApplyPaths.Resolved roots, List<Path> targets, List<Path> temps,
                 Path receiptPath, Path journalPath, String summarySha256,
                 List<ApplyStateV2.Dependency> verified, List<DependencyLocation> requestDependencies,
                 GenerationManifest manifest, String createdAt) {
            this.applyId = applyId;
            this.plan = plan;
            this.files = List.copyOf(files);
            this.roots = roots;
            this.targets = List.copyOf(targets);
            this.temps = List.copyOf(temps);
            this.receiptPath = receiptPath;
            this.journalPath = journalPath;
            this.summarySha256 = summarySha256;
            this.verified = verified;
            this.requestDependencies = List.copyOf(requestDependencies);
            this.manifest = manifest;
            this.createdAt = createdAt;
            this.published = new boolean[files.size()];
            for (int index = 0; index < files.size(); index++) {
                GenerationFile file = files.get(index);
                entries.add(new ApplyStateV2.Entry(file.relativePath(),
                        roots.projectRoot().relativize(targets.get(index)).toString().replace('\\', '/'),
                        roots.projectRoot().relativize(temps.get(index)).toString().replace('\\', '/'),
                        file.sha256(), file.content().getBytes(StandardCharsets.UTF_8).length,
                        null, null));
            }
        }
    }

    private static final class LockHandle implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private LockHandle(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static LockHandle acquire(Path stateRoot) throws ApplyFailure {
            Path lockFile = stateRoot.resolve(ApplyStateV2.LOCK_FILE);
            if (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(lockFile,
                            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
                        throw new ApplyFailure(ApplyStatus.REJECTED, "STATE_ROOT_UNSAFE",
                                "Apply lock path is not a regular file: " + lockFile);
                    }
                } catch (IOException ex) {
                    throw new ApplyFailure(ApplyStatus.REJECTED, "STATE_ROOT_UNSAFE",
                            "Cannot inspect the v2 apply lock: " + lockFile, ex);
                }
            }
            FileChannel channel = null;
            try {
                channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    throw new ApplyFailure(ApplyStatus.REJECTED, "APPLY_LOCKED",
                            "Another apply holds the project lock");
                }
                return new LockHandle(channel, lock);
            } catch (OverlappingFileLockException ex) {
                closeQuietly(channel);
                throw new ApplyFailure(ApplyStatus.REJECTED, "APPLY_LOCKED",
                        "Another apply in this JVM holds the project lock", ex);
            } catch (IOException ex) {
                closeQuietly(channel);
                throw new ApplyFailure(ApplyStatus.REJECTED, "APPLY_LOCK_FAILED",
                        "Cannot acquire the v2 apply lock: " + lockFile, ex);
            }
        }

        private static void closeQuietly(FileChannel channel) {
            if (channel == null) return;
            try {
                channel.close();
            } catch (IOException ignored) {
                // The original lock failure remains the reported cause.
            }
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}

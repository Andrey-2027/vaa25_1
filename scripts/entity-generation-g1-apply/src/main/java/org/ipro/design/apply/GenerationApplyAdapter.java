package org.ipro.design.apply;

import java.io.IOException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.ipro.design.DraftValidator;
import org.ipro.design.EntityDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.ValidationResult;
import org.ipro.design.generation.EntityJavaGenerator;
import org.ipro.design.generation.GenerationFile;
import org.ipro.design.generation.GenerationPlan;
import org.ipro.design.generation.GenerationResult;

/**
 * Applies exactly one reviewed CREATE entry from a real {@link GenerationPlan} to an explicit
 * project/source root. It performs no draft/context discovery, no class loading and no SQL.
 *
 * <p>Preflight rebuilds the plan through the public validator/generator, verifies the current C
 * summary and validates roots. Ownership is established only by a project-local ledger receipt,
 * never by matching content. Publication uses an exclusive hard link after a complete temp file,
 * followed by journal, receipt and no-op on repeat.</p>
 */
public final class GenerationApplyAdapter {
    private static final Set<String> SUPPORTED_RECIPES = Set.of(
            RecipeCatalog.STANDARD_CATALOG_V2.id() + "@" + RecipeCatalog.STANDARD_CATALOG_V2.version());

    private final ApplyHooks hooks;
    private final PlanRebuilder planRebuilder;

    public GenerationApplyAdapter() {
        this(ApplyHooks.NONE, GenerationApplyAdapter::rebuildWithPublicGenerator);
    }

    GenerationApplyAdapter(ApplyHooks hooks) {
        this(hooks, GenerationApplyAdapter::rebuildWithPublicGenerator);
    }

    GenerationApplyAdapter(ApplyHooks hooks, PlanRebuilder planRebuilder) {
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.planRebuilder = Objects.requireNonNull(planRebuilder, "planRebuilder");
    }

    public ApplyResult apply(ApplyRequest request, GenerationPlan reviewedPlan,
                             EntityDraft currentDraft, ProjectDesignContext currentContext) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reviewedPlan, "reviewedPlan");
        Objects.requireNonNull(currentDraft, "currentDraft");
        Objects.requireNonNull(currentContext, "currentContext");
        try {
            return applyChecked(request, reviewedPlan, currentDraft, currentContext);
        } catch (ApplyFailure failure) {
            return failureResult(failure, null, reviewedPlan.planId(), null, null,
                    null, null, null);
        }
    }

    private ApplyResult applyChecked(ApplyRequest request, GenerationPlan plan,
                                     EntityDraft draft, ProjectDesignContext context)
            throws ApplyFailure {
        if (RecipeCatalog.INDEPENDENT_REGISTER.equals(plan.recipe())
                || "register-java-v1".equals(plan.generatorVersion())
                || RecipeCatalog.STANDARD_DOCUMENT.equals(plan.recipe())
                || RecipeCatalog.STANDARD_CATALOG_V2.equals(plan.recipe()) && !"catalog-java-v1".equals(plan.generatorVersion())) {
            return new GenerationApplyV2(hooks, planRebuilder).apply(request, plan, draft, context);
        }
        GenerationFile file = validatePlanScope(plan);
        validateFreshness(plan, file, draft, context);

        ApplyPaths.Resolved roots = ApplyPaths.resolve(request.projectRoot(), request.sourceRoot());
        Path target = ApplyPaths.target(roots, file.relativePath());

        CSummary summary = CSummary.read(request.cSummary());
        summary.verify(plan, file);

        ApplyPaths.ensureStateRoot(roots);
        LockHandle lock = LockHandle.acquire(roots.stateRoot());
        try {
            return applyLocked(roots, target, plan, file, summary, request.cSummary());
        } finally {
            try {
                lock.close();
            } catch (IOException ignored) {
                // The apply outcome is already determined; the OS releases the lock at exit.
            }
        }
    }

    private ApplyResult applyLocked(ApplyPaths.Resolved roots, Path target, GenerationPlan plan,
                                    GenerationFile file, CSummary summary, Path summaryPath)
            throws ApplyFailure {
        ApplyPaths.assertNoLinks(roots.stateRoot(), "STATE_ROOT_UNSAFE");
        Path journalPath = ApplyState.journalPath(roots.stateRoot());
        Optional<ApplyState.Journal> journal = ApplyState.readJournal(journalPath);
        if (journal.isPresent()) {
            return recovery("JOURNAL_INCOMPLETE",
                    "An unfinished apply journal exists; inspect it and recover explicitly", plan.planId(),
                    target, ApplyState.receiptPath(roots.stateRoot(), roots.sourceRootRelative(), plan.planId()),
                    journalPath, file.sha256(), summary.sha256());
        }

        List<ApplyState.Receipt> receipts = ApplyState.readReceipts(roots.stateRoot());
        List<ApplyState.Receipt> own = receipts.stream()
                .filter(receipt -> receipt.planId().equals(plan.planId())
                        && receipt.sourceRoot().equals(roots.sourceRootRelative()))
                .toList();
        if (own.size() > 1) {
            return recovery("LEDGER_DUPLICATE",
                    "The ledger contains more than one receipt for the reviewed plan", plan.planId(),
                    target, ApplyState.receiptPath(roots.stateRoot(), roots.sourceRootRelative(), plan.planId()),
                    journalPath, file.sha256(), summary.sha256());
        }
        ApplyState.Receipt receipt = own.isEmpty() ? null : own.getFirst();
        if (receipt != null) {
            if (!receipt.sourceRoot().equals(roots.sourceRootRelative())
                    || !receipt.relativePath().equals(file.relativePath())
                    || !receipt.contentSha256().equals(file.sha256())
                    || !receipt.recipe().equals(plan.recipe().id() + "@" + plan.recipe().version())
                    || !receipt.generatorVersion().equals(plan.generatorVersion())) {
                return recovery("LEDGER_INVALID",
                        "The plan receipt does not match the reviewed plan or current source root", plan.planId(),
                        target, ApplyState.receiptPath(roots.stateRoot(), roots.sourceRootRelative(), plan.planId()),
                        journalPath, file.sha256(), summary.sha256());
            }
        }

        List<ApplyState.Receipt> targetReceipts = receipts.stream()
                .filter(candidate -> candidate.sourceRoot().equals(roots.sourceRootRelative())
                        && candidate.relativePath().equals(file.relativePath()))
                .toList();
        boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        Path receiptPath = ApplyState.receiptPath(roots.stateRoot(), roots.sourceRootRelative(), plan.planId());

        if (receipt != null) {
            if (!targetExists) {
                return recovery("OWNED_TARGET_MISSING",
                        "A completed receipt exists but its target is missing; re-create is not automatic",
                        plan.planId(), target, receiptPath, journalPath, file.sha256(), summary.sha256());
            }
            if (!isRegular(target) || !file.sha256().equals(hashOrConflict(target, "OWNED_TARGET_UNREADABLE"))) {
                return conflict("CONFLICT_MODIFIED",
                        "The target exists but is no longer the file recorded by this plan",
                        plan.planId(), target, receiptPath, journalPath, file.sha256(), summary.sha256());
            }
            summary.verifyUnchanged(summaryPath, ApplyStatus.REJECTED);
            return new ApplyResult(ApplyStatus.NOOP, "NOOP",
                    "The same plan and unchanged target are already applied",
                    receipt.applyId(), plan.planId(), target, receiptPath, journalPath,
                    file.sha256(), summary.sha256());
        }

        if (!targetReceipts.isEmpty()) {
            if (!targetExists) {
                return recovery("ORPHAN_RECEIPT",
                        "A receipt owns this target but the target is missing",
                        plan.planId(), target, receiptPath, journalPath, file.sha256(), summary.sha256());
            }
            return conflict("CONFLICT_OTHER_PLAN",
                    "The target is owned by a different plan receipt",
                    plan.planId(), target, receiptPath, journalPath, file.sha256(), summary.sha256());
        }
        if (targetExists) {
            return conflict("CONFLICT_UNOWNED",
                    "The target exists without a matching receipt; identical content is not ownership",
                    plan.planId(), target, receiptPath, journalPath, file.sha256(), summary.sha256());
        }
        return create(roots, target, plan, file, summary, summaryPath);
    }

    private ApplyResult create(ApplyPaths.Resolved roots, Path target, GenerationPlan plan,
                               GenerationFile file, CSummary summary, Path summaryPath)
            throws ApplyFailure {
        String applyId = UUID.randomUUID().toString();
        String createdAt = ApplyState.now();
        Path journalPath = ApplyState.journalPath(roots.stateRoot());
        Path receiptPath = ApplyState.receiptPath(roots.stateRoot(), roots.sourceRootRelative(), plan.planId());
        Path temp = target.getParent().resolve(".g1-" + applyId + ".tmp");
        Mutation mutation = new Mutation(applyId, plan, file, roots, target, temp, receiptPath,
                journalPath, summary.sha256(), createdAt);
        try {
            mutation.journal = new ApplyState.Journal(applyId, plan.planId(),
                    plan.recipe().id() + "@" + plan.recipe().version(), plan.generatorVersion(),
                    roots.sourceRootRelative(), file.relativePath(), file.sha256(), summary.sha256(),
                    ApplyState.Phase.PREPARED, roots.projectRoot().toString(), target.toString(),
                    temp.toString(), List.of(), createdAt);
            ApplyState.writeJournal(journalPath, mutation.journal);

            ApplyPaths.createParentDirectories(target.getParent(), roots.sourceRoot(), mutation.createdDirs);
            mutation.journal = mutation.journal.withCreatedDirs(
                            relativeDirectories(roots.projectRoot(), mutation.createdDirs))
                    .withPhase(ApplyState.Phase.DIRECTORIES);
            ApplyState.writeJournal(journalPath, mutation.journal);

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ApplyFailure(ApplyStatus.CONFLICT, "CONFLICT_LATE_TARGET",
                        "Target appeared before publication; existing data was preserved");
            }
            hooks.beforePublish(target);
            ApplyPaths.assertSafePublicationPath(target, roots);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ApplyFailure(ApplyStatus.CONFLICT, "CONFLICT_LATE_TARGET",
                        "Target appeared before publication; existing data was preserved");
            }

            byte[] content = file.content().getBytes(StandardCharsets.UTF_8);
            Files.write(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            force(temp);
            if (!file.sha256().equals(ApplyHash.sha256(temp))) {
                throw new ApplyFailure(ApplyStatus.ROLLED_BACK, "TEMP_HASH_MISMATCH",
                        "The temp file bytes do not match the reviewed plan");
            }
            mutation.journal = mutation.journal.withPhase(ApplyState.Phase.TEMP_WRITTEN);
            ApplyState.writeJournal(journalPath, mutation.journal);

            try {
                Files.createLink(target, temp);
            } catch (FileAlreadyExistsException ex) {
                throw new ApplyFailure(ApplyStatus.CONFLICT, "CONFLICT_LATE_TARGET",
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
            mutation.targetPublished = true;
            if (!isRegular(target) || !file.sha256().equals(ApplyHash.sha256(target))) {
                throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TARGET_HASH_MISMATCH",
                        "The published target cannot be proven to be this apply's content");
            }
            mutation.journal = mutation.journal.withPhase(ApplyState.Phase.PUBLISHED);
            ApplyState.writeJournal(journalPath, mutation.journal);

            hooks.afterPublish(target);
            summary.verifyUnchanged(summaryPath, ApplyStatus.ROLLED_BACK);

            try {
                Files.delete(temp);
            } catch (IOException ex) {
                throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TEMP_DELETE_FAILED",
                        "Target is published but its temp link could not be removed", ex);
            }

            ApplyState.Receipt receipt = new ApplyState.Receipt(applyId, plan.planId(),
                    plan.recipe().id() + "@" + plan.recipe().version(), plan.generatorVersion(),
                    roots.sourceRootRelative(), file.relativePath(), file.sha256(), summary.sha256(),
                    roots.projectRoot().toString(), target.toString(), createdAt);
            try {
                ApplyState.writeReceipt(receiptPath, receipt);
            } catch (FileAlreadyExistsException ex) {
                throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "RECEIPT_EXISTS",
                        "A receipt appeared concurrently; ownership is ambiguous", ex);
            } catch (IOException ex) {
                throw new ApplyFailure(ApplyStatus.ROLLED_BACK, "RECEIPT_WRITE_FAILED",
                        "Could not persist the apply receipt", ex);
            }
            summary.verifyUnchanged(summaryPath, ApplyStatus.ROLLED_BACK);

            try {
                Files.delete(journalPath);
            } catch (IOException ex) {
                return recoveryWithJournal("JOURNAL_DELETE_FAILED",
                        "Target and receipt exist but the journal could not be closed", mutation);
            }
            return new ApplyResult(ApplyStatus.APPLIED, "APPLIED",
                    "Created one Java source file and recorded its receipt",
                    applyId, plan.planId(), target, receiptPath, journalPath,
                    file.sha256(), summary.sha256());
        } catch (ApplyFailure failure) {
            return rollback(failure, mutation);
        } catch (IOException ex) {
            return rollback(new ApplyFailure(ApplyStatus.ROLLED_BACK, "APPLY_IO",
                    "File apply failed: " + ex.getMessage(), ex), mutation);
        }
    }

    private ApplyResult rollback(ApplyFailure failure, Mutation mutation) throws ApplyFailure {
        if (failure.status() == ApplyStatus.RECOVERY_REQUIRED) {
            return recoveryWithJournal(failure.code(), failure.getMessage(), mutation);
        }
        try {
            if (Files.exists(mutation.receiptPath, LinkOption.NOFOLLOW_LINKS)) {
                deleteOwnedReceipt(mutation);
            }
            if (mutation.targetPublished) {
                deleteOwnedTarget(mutation);
            }
            deleteOwnedTemp(mutation);
            deleteOwnedJournal(mutation);
            deleteCreatedDirectories(mutation);
        } catch (ApplyFailure cleanup) {
            return recoveryWithJournal(cleanup.code(),
                    failure.getMessage() + "; cleanup could not be proven: " + cleanup.getMessage(),
                    mutation);
        }
        return failureResult(failure, mutation.applyId, mutation.plan.planId(), mutation.target,
                mutation.receiptPath, mutation.journalPath, mutation.file.sha256(),
                mutation.summarySha256);
    }

    private void deleteOwnedReceipt(Mutation mutation) throws ApplyFailure {
        if (!Files.exists(mutation.receiptPath, LinkOption.NOFOLLOW_LINKS)) return;
        ApplyState.Receipt receipt = ApplyState.readReceipt(mutation.receiptPath);
        if (!receipt.applyId().equals(mutation.applyId) || !receipt.planId().equals(mutation.plan.planId())) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "RECEIPT_MODIFIED",
                    "The receipt no longer belongs to this apply");
        }
        try {
            Files.delete(mutation.receiptPath);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "RECEIPT_DELETE_FAILED",
                    "Cannot remove this apply's receipt", ex);
        }
    }

    private void deleteOwnedTarget(Mutation mutation) throws ApplyFailure {
        if (!Files.exists(mutation.target, LinkOption.NOFOLLOW_LINKS)) return;
        if (!isRegular(mutation.target)
                || !mutation.file.sha256().equals(hashOrRecovery(mutation.target, "TARGET_MODIFIED"))) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TARGET_MODIFIED",
                    "The target changed after publication; it is preserved");
        }
        try {
            Files.delete(mutation.target);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TARGET_DELETE_FAILED",
                    "Cannot remove this apply's unchanged target", ex);
        }
    }

    private void deleteOwnedTemp(Mutation mutation) throws ApplyFailure {
        if (!Files.exists(mutation.temp, LinkOption.NOFOLLOW_LINKS)) return;
        String hash = hashOrRecovery(mutation.temp, "TEMP_MODIFIED");
        if (!isRegular(mutation.temp) || !mutation.file.sha256().equals(hash)) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TEMP_MODIFIED",
                    "The temp file changed; it is preserved");
        }
        try {
            Files.delete(mutation.temp);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "TEMP_DELETE_FAILED",
                    "Cannot remove this apply's temp file", ex);
        }
    }

    private void deleteOwnedJournal(Mutation mutation) throws ApplyFailure {
        if (!Files.exists(mutation.journalPath, LinkOption.NOFOLLOW_LINKS)) return;
        ApplyState.Journal journal = ApplyState.readJournal(mutation.journalPath)
                .orElseThrow(() -> new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED,
                        "JOURNAL_INVALID", "The journal disappeared while reading it"));
        if (!journal.applyId().equals(mutation.applyId)) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_MODIFIED",
                    "The journal no longer belongs to this apply");
        }
        try {
            Files.delete(mutation.journalPath);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_DELETE_FAILED",
                    "Cannot remove this apply's journal", ex);
        }
    }

    private void deleteCreatedDirectories(Mutation mutation) {
        for (int index = mutation.createdDirs.size() - 1; index >= 0; index--) {
            Path directory = mutation.createdDirs.get(index);
            try {
                BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
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

    private GenerationFile validatePlanScope(GenerationPlan plan) throws ApplyFailure {
        String recipe = plan.recipe().id() + "@" + plan.recipe().version();
        if (!SUPPORTED_RECIPES.contains(recipe)) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "UNSUPPORTED_RECIPE",
                    "Only " + String.join(", ", SUPPORTED_RECIPES) + " are supported by this adapter");
        }
        if (plan.files().size() != 1) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "MULTI_FILE",
                    "Exactly one file entry is supported; multi-file is not a transaction");
        }
        GenerationFile file = plan.files().getFirst();
        if (file.operation() != GenerationFile.Operation.CREATE) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "UNSUPPORTED_OPERATION",
                    "Only CREATE is supported");
        }
        ApplyPaths.validateJavaRelativePath(file.relativePath());
        String hash = ApplyHash.sha256(file.content());
        if (!hash.equals(file.sha256())) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "PLAN_HASH_MISMATCH",
                    "GenerationFile.sha256 does not match its exact UTF-8 content");
        }
        return file;
    }

    private void validateFreshness(GenerationPlan plan, GenerationFile file, EntityDraft draft,
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
        if (!control.files().getFirst().relativePath().equals(file.relativePath())) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "PLAN_MISMATCH",
                    "The reviewed file entry differs from the current builder output");
        }
    }

    /** Production control rebuild: public semantic validator plus the shared recipe dispatcher. */
    private static GenerationPlan rebuildWithPublicGenerator(GenerationPlan reviewed, EntityDraft draft,
                                                              ProjectDesignContext context)
            throws ApplyFailure {
        ValidationResult validation = new DraftValidator().validate(draft, context);
        if (validation.hasErrors() || validation.checkedStructure().isEmpty()) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "CURRENT_INPUT_INVALID",
                    "The current draft/context has validation errors: " + truncate(validation.problems().toString()));
        }
        GenerationResult regenerated = "catalog-java-v1".equals(reviewed.generatorVersion())
                ? new org.ipro.design.generation.CatalogJavaGenerator().generate(validation.checkedStructure().orElseThrow(), draft, context)
                : new EntityJavaGenerator().generate(validation.checkedStructure().orElseThrow(), draft, context);
        if (!regenerated.succeeded()) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "CONTROL_PLAN_REJECTED",
                    "The current validator/generator rejects the reviewed input: "
                            + truncate(regenerated.diagnostics().toString()));
        }
        return regenerated.plan().orElseThrow();
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

    private String hashOrConflict(Path path, String code) throws ApplyFailure {
        try {
            return ApplyHash.sha256(path);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.CONFLICT, code,
                    "Cannot read the existing target; data is preserved: " + path, ex);
        }
    }

    private String hashOrRecovery(Path path, String code) throws ApplyFailure {
        try {
            return ApplyHash.sha256(path);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "Cannot read the path for safe recovery: " + path, ex);
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
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

    private static String truncate(String value) {
        return value.length() <= 800 ? value : value.substring(0, 800) + "...";
    }

    private ApplyResult conflict(String code, String message, String planId, Path target, Path receipt,
                                 Path journal, String contentSha, String summarySha) {
        return new ApplyResult(ApplyStatus.CONFLICT, code, message, null, planId, target, receipt,
                journal, contentSha, summarySha);
    }

    private ApplyResult recovery(String code, String message, String planId, Path target, Path receipt,
                                 Path journal, String contentSha, String summarySha) {
        return new ApplyResult(ApplyStatus.RECOVERY_REQUIRED, code, message, null, planId, target,
                receipt, journal, contentSha, summarySha);
    }

    private ApplyResult recoveryWithJournal(String code, String message, Mutation mutation) {
        return new ApplyResult(ApplyStatus.RECOVERY_REQUIRED, code, message, mutation.applyId,
                mutation.plan.planId(), mutation.target, mutation.receiptPath, mutation.journalPath,
                mutation.file.sha256(), mutation.summarySha256);
    }

    private static ApplyResult failureResult(ApplyFailure failure, String applyId, String planId,
                                             Path target, Path receipt, Path journal,
                                             String contentSha, String summarySha) {
        return new ApplyResult(failure.status(), failure.code(), failure.getMessage(), applyId, planId,
                target, receipt, journal, contentSha, summarySha);
    }

    private static final class Mutation {
        final String applyId;
        final GenerationPlan plan;
        final GenerationFile file;
        final ApplyPaths.Resolved roots;
        final Path target;
        final Path temp;
        final Path receiptPath;
        final Path journalPath;
        final String summarySha256;
        final String createdAt;
        final List<Path> createdDirs = new ArrayList<>();
        ApplyState.Journal journal;
        boolean targetPublished;

        Mutation(String applyId, GenerationPlan plan, GenerationFile file, ApplyPaths.Resolved roots,
                 Path target, Path temp, Path receiptPath, Path journalPath, String summarySha256,
                 String createdAt) {
            this.applyId = applyId;
            this.plan = plan;
            this.file = file;
            this.roots = roots;
            this.target = target;
            this.temp = temp;
            this.receiptPath = receiptPath;
            this.journalPath = journalPath;
            this.summarySha256 = summarySha256;
            this.createdAt = createdAt;
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
            Path lockFile = stateRoot.resolve(ApplyState.LOCK_FILE);
            if (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(lockFile, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
                        throw new ApplyFailure(ApplyStatus.REJECTED, "STATE_ROOT_UNSAFE",
                                "Apply lock path is not a regular file: " + lockFile);
                    }
                } catch (IOException ex) {
                    throw new ApplyFailure(ApplyStatus.REJECTED, "STATE_ROOT_UNSAFE",
                            "Cannot inspect apply lock: " + lockFile, ex);
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
                        "Cannot acquire the apply lock: " + lockFile, ex);
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

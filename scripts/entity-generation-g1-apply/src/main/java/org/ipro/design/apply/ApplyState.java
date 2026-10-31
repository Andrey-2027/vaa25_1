package org.ipro.design.apply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Project-local ledger, journal and atomic JSON files. State bytes are not a trust signature. */
final class ApplyState {
    static final String RECEIPT_KIND = "g1-apply-receipt-v1";
    static final String JOURNAL_KIND = "g1-apply-journal-v1";
    static final int VERSION = 1;
    static final String JOURNAL_FILE = "journal.json";
    static final String LOCK_FILE = "apply.lock";

    private ApplyState() {
    }

    enum Phase {
        PREPARED, DIRECTORIES, TEMP_WRITTEN, PUBLISHED
    }

    record Receipt(String applyId, String planId, String recipe, String generatorVersion,
                   String sourceRoot, String relativePath, String contentSha256, String cSummarySha256,
                   String projectRoot, String target, String createdAt) {
        String toJson() throws IOException {
            ObjectNode root = Json.object();
            root.put("kind", RECEIPT_KIND);
            root.put("stateVersion", VERSION);
            root.put("status", "APPLIED");
            root.put("applyId", applyId);
            root.put("planId", planId);
            root.put("recipe", recipe);
            root.put("generatorVersion", generatorVersion);
            root.put("sourceRoot", sourceRoot);
            root.put("relativePath", relativePath);
            root.put("contentSha256", contentSha256);
            root.put("cSummarySha256", cSummarySha256);
            root.put("projectRoot", projectRoot);
            root.put("target", target);
            root.put("createdAt", createdAt);
            return Json.write(root);
        }
    }

    record Journal(String applyId, String planId, String recipe, String generatorVersion,
                   String sourceRoot, String relativePath, String contentSha256, String cSummarySha256,
                   Phase phase, String projectRoot, String target, String temp, List<String> createdDirs,
                   String createdAt) {
        Journal withPhase(Phase value) {
            return new Journal(applyId, planId, recipe, generatorVersion, sourceRoot, relativePath,
                    contentSha256, cSummarySha256, value, projectRoot, target, temp, createdDirs, createdAt);
        }

        Journal withCreatedDirs(List<String> value) {
            return new Journal(applyId, planId, recipe, generatorVersion, sourceRoot, relativePath,
                    contentSha256, cSummarySha256, phase, projectRoot, target, temp, List.copyOf(value), createdAt);
        }

        String toJson() throws IOException {
            ObjectNode root = Json.object();
            root.put("kind", JOURNAL_KIND);
            root.put("stateVersion", VERSION);
            root.put("applyId", applyId);
            root.put("planId", planId);
            root.put("recipe", recipe);
            root.put("generatorVersion", generatorVersion);
            root.put("sourceRoot", sourceRoot);
            root.put("relativePath", relativePath);
            root.put("contentSha256", contentSha256);
            root.put("cSummarySha256", cSummarySha256);
            root.put("phase", phase.name());
            root.put("projectRoot", projectRoot);
            root.put("target", target);
            root.put("temp", temp);
            ArrayNode dirs = root.putArray("createdDirs");
            createdDirs.forEach(dirs::add);
            root.put("createdAt", createdAt);
            return Json.write(root);
        }
    }

    static Path receiptPath(Path stateRoot, String sourceRoot, String planId) {
        return stateRoot.resolve("receipts").resolve(ApplyPaths.sourceKey(sourceRoot)).resolve(planId + ".json");
    }

    static Path journalPath(Path stateRoot) {
        return stateRoot.resolve(JOURNAL_FILE);
    }

    static void writeReceipt(Path path, Receipt receipt) throws IOException, ApplyFailure {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(path.toString());
        }
        noReplaceWrite(path, receipt.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static void writeJournal(Path path, Journal journal) throws IOException {
        atomicWrite(path, journal.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static Optional<Journal> readJournal(Path path) throws ApplyFailure {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        JsonNode root = readObject(path, "JOURNAL_INVALID");
        requireText(root, "kind", JOURNAL_KIND, "JOURNAL_INVALID");
        requireInt(root, "stateVersion", VERSION, "JOURNAL_INVALID");
        String applyId = requiredText(root, "applyId", "JOURNAL_INVALID");
        parseUuid(applyId, "JOURNAL_INVALID");
        String planId = requiredText(root, "planId", "JOURNAL_INVALID");
        requirePlanId(planId, "JOURNAL_INVALID");
        String recipe = requiredText(root, "recipe", "JOURNAL_INVALID");
        String generatorVersion = requiredText(root, "generatorVersion", "JOURNAL_INVALID");
        String sourceRoot = requiredText(root, "sourceRoot", "JOURNAL_INVALID");
        validateStateDirectory(sourceRoot, "JOURNAL_INVALID");
        String relativePath = requiredText(root, "relativePath", "JOURNAL_INVALID");
        validateStateJavaPath(relativePath, "JOURNAL_INVALID");
        String contentSha = requiredText(root, "contentSha256", "JOURNAL_INVALID");
        requireHash(contentSha, "JOURNAL_INVALID");
        String summarySha = requiredText(root, "cSummarySha256", "JOURNAL_INVALID");
        requireHash(summarySha, "JOURNAL_INVALID");
        String phaseText = requiredText(root, "phase", "JOURNAL_INVALID");
        Phase phase;
        try {
            phase = Phase.valueOf(phaseText);
        } catch (IllegalArgumentException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_INVALID",
                    "Unknown journal phase: " + phaseText, ex);
        }
        String projectRoot = requiredText(root, "projectRoot", "JOURNAL_INVALID");
        String target = requiredText(root, "target", "JOURNAL_INVALID");
        String temp = requiredText(root, "temp", "JOURNAL_INVALID");
        List<String> createdDirs = new ArrayList<>();
        JsonNode dirs = root.get("createdDirs");
        if (dirs == null || !dirs.isArray()) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_INVALID",
                    "Journal createdDirs must be an array");
        }
        for (JsonNode dir : dirs) {
            if (!dir.isTextual()) {
                throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "JOURNAL_INVALID",
                        "Journal createdDirs entries must be strings");
            }
            validateStateDirectory(dir.asText(), "JOURNAL_INVALID");
            createdDirs.add(dir.asText());
        }
        String createdAt = requiredText(root, "createdAt", "JOURNAL_INVALID");
        return Optional.of(new Journal(applyId, planId, recipe, generatorVersion, sourceRoot,
                relativePath, contentSha, summarySha, phase, projectRoot, target, temp, createdDirs, createdAt));
    }

    static List<Receipt> readReceipts(Path stateRoot) throws ApplyFailure {
        Path root = stateRoot.resolve("receipts");
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "LEDGER_INVALID",
                    "Receipts path is not a directory: " + root);
        }
        List<Receipt> receipts = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream.toList();
            for (Path file : files) {
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(file, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                } catch (IOException ex) {
                    throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "LEDGER_INVALID",
                            "Cannot inspect ledger entry: " + file, ex);
                }
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "LEDGER_INVALID",
                            "Ledger contains a link/reparse alias: " + file);
                }
                if (attributes.isDirectory()) continue;
                if (!file.getFileName().toString().endsWith(".json")) continue;
                if (!attributes.isRegularFile()) {
                    throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "LEDGER_INVALID",
                            "Ledger entry is not a regular file: " + file);
                }
                Receipt receipt = readReceipt(file);
                if (!file.getFileName().toString().equals(receipt.planId() + ".json")) {
                    throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "LEDGER_INVALID",
                            "Ledger file name does not match its planId: " + file);
                }
                Path sourceDirectory = file.getParent() == null ? null : file.getParent().getFileName();
                if (sourceDirectory == null
                        || !sourceDirectory.toString().equals(ApplyPaths.sourceKey(receipt.sourceRoot()))) {
                    throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "LEDGER_INVALID",
                            "Ledger source directory does not match its sourceRoot: " + file);
                }
                receipts.add(receipt);
            }
        } catch (java.io.UncheckedIOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "LEDGER_INVALID",
                    "Cannot read apply ledger under " + root, ex);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "LEDGER_INVALID",
                    "Cannot read apply ledger under " + root, ex);
        }
        return List.copyOf(receipts);
    }

    static Receipt readReceipt(Path path) throws ApplyFailure {
        JsonNode root = readObject(path, "LEDGER_INVALID");
        requireText(root, "kind", RECEIPT_KIND, "LEDGER_INVALID");
        requireInt(root, "stateVersion", VERSION, "LEDGER_INVALID");
        requireText(root, "status", "APPLIED", "LEDGER_INVALID");
        String applyId = requiredText(root, "applyId", "LEDGER_INVALID");
        parseUuid(applyId, "LEDGER_INVALID");
        String planId = requiredText(root, "planId", "LEDGER_INVALID");
        requirePlanId(planId, "LEDGER_INVALID");
        String recipe = requiredText(root, "recipe", "LEDGER_INVALID");
        String generatorVersion = requiredText(root, "generatorVersion", "LEDGER_INVALID");
        String sourceRoot = requiredText(root, "sourceRoot", "LEDGER_INVALID");
        validateStateDirectory(sourceRoot, "LEDGER_INVALID");
        String relativePath = requiredText(root, "relativePath", "LEDGER_INVALID");
        validateStateJavaPath(relativePath, "LEDGER_INVALID");
        String contentSha = requiredText(root, "contentSha256", "LEDGER_INVALID");
        requireHash(contentSha, "LEDGER_INVALID");
        String summarySha = requiredText(root, "cSummarySha256", "LEDGER_INVALID");
        requireHash(summarySha, "LEDGER_INVALID");
        String projectRoot = requiredText(root, "projectRoot", "LEDGER_INVALID");
        String target = requiredText(root, "target", "LEDGER_INVALID");
        String createdAt = requiredText(root, "createdAt", "LEDGER_INVALID");
        return new Receipt(applyId, planId, recipe, generatorVersion, sourceRoot, relativePath,
                contentSha, summarySha, projectRoot, target, createdAt);
    }

    static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Target has no parent: " + target);
        Files.createDirectories(parent);
        Path temp = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            force(temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (FileAlreadyExistsException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void noReplaceWrite(Path target, byte[] bytes) throws IOException, ApplyFailure {
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Target has no parent: " + target);
        ApplyPaths.assertNoLinks(parent, "STATE_ROOT_UNSAFE");
        Files.createDirectories(parent);
        ApplyPaths.assertNoLinks(parent, "STATE_ROOT_UNSAFE");
        Path temp = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            force(temp);
            try {
                Files.createLink(target, temp);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (UnsupportedOperationException ex) {
            throw new IOException("No-replace receipt publication is unsupported", ex);
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    static void assertSafeStateFile(Path path, String code) throws ApplyFailure {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
                throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                        "State path is not a regular file: " + path);
            }
        } catch (java.nio.file.NoSuchFileException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "State file disappeared: " + path, ex);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "Cannot inspect state file: " + path, ex);
        }
    }

    private static void validateStateDirectory(String value, String code) throws ApplyFailure {
        try {
            ApplyPaths.validateRelativeDirectory(value, code);
        } catch (ApplyFailure failure) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "State path is invalid: " + failure.getMessage(), failure);
        }
    }

    private static void validateStateJavaPath(String value, String code) throws ApplyFailure {
        try {
            ApplyPaths.validateJavaRelativePath(value);
        } catch (ApplyFailure failure) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "State target path is invalid: " + failure.getMessage(), failure);
        }
    }

    private static JsonNode readObject(Path path, String code) throws ApplyFailure {
        assertSafeStateFile(path, code);
        try {
            if (Files.size(path) > 1_048_576) {
                throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                        "State file is too large: " + path);
            }
            JsonNode root = Json.MAPPER.readTree(Files.readAllBytes(path));
            if (root == null || !root.isObject()) {
                throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                        "State file root must be an object: " + path);
            }
            return root;
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "Cannot parse state file: " + path, ex);
        }
    }

    private static void requireText(JsonNode root, String field, String expected, String code)
            throws ApplyFailure {
        String value = requiredText(root, field, code);
        if (!value.equals(expected)) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "State field " + field + " is " + value + ", expected " + expected);
        }
    }

    private static void requireInt(JsonNode root, String field, int expected, String code)
            throws ApplyFailure {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.asInt() != expected) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "State field " + field + " must be " + expected);
        }
    }

    private static String requiredText(JsonNode root, String field, String code) throws ApplyFailure {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "State field is required and must be a non-blank string: " + field);
        }
        return value.asText();
    }

    private static void parseUuid(String value, String code) throws ApplyFailure {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "State applyId is not a UUID: " + value, ex);
        }
    }

    private static void requirePlanId(String value, String code) throws ApplyFailure {
        if (!value.matches("g1-[0-9a-f]{64}")) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "State planId is invalid: " + value);
        }
    }

    private static void requireHash(String value, String code) throws ApplyFailure {
        if (!ApplyHash.isSha256(value)) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "State SHA-256 is invalid: " + value);
        }
    }

    static String now() {
        return Instant.now().toString();
    }
}

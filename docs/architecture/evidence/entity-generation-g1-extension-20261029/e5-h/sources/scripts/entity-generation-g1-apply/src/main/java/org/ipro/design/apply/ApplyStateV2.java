package org.ipro.design.apply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.ipro.design.generation.GenerationManifest;

/**
 * Project-local v2 ledger and journal. The exact fields follow the E0.4 contract; state bytes are
 * evidence, not a trust signature.
 */
final class ApplyStateV2 {
    static final String RECEIPT_KIND = "g1-apply-receipt-v2";
    static final String JOURNAL_KIND = "g1-apply-journal-v2";
    static final int VERSION = 2;
    static final String JOURNAL_FILE = "journal.json";
    static final String LOCK_FILE = "apply.lock";

    private ApplyStateV2() {
    }

    enum Phase {
        PREPARED, DIRECTORIES, TEMP_WRITTEN, PUBLISHED, VERIFIED, RECEIPT_WRITTEN
    }

    record Dependency(String id, String sha256) {
    }

    record PublishedIdentity(String fileKey, String realPath) {
    }

    record Entry(String relativePath, String target, String temp, String expectedSha256, long bytes,
                 PublishedIdentity publishedIdentity, String publishedSha256) {
        Entry published(PublishedIdentity identity, String publishedSha256) {
            return new Entry(relativePath, target, temp, expectedSha256, bytes, identity, publishedSha256);
        }

        Entry withTemp(String value) {
            return new Entry(relativePath, target, value, expectedSha256, bytes, publishedIdentity,
                    publishedSha256);
        }
    }

    record Receipt(String applyId, GenerationManifest manifest, String cSummarySha256,
                   List<Dependency> verifiedDependencies, String projectRoot, String sourceRoot,
                   String createdAt, List<String> createdDirs, List<Entry> entries) {
        String toJson() throws IOException {
            ObjectNode root = base(RECEIPT_KIND, applyId, manifest, cSummarySha256,
                    verifiedDependencies, projectRoot, sourceRoot, createdAt, createdDirs, entries);
            root.put("status", "APPLIED");
            return Json.write(root);
        }
    }

    record Journal(String applyId, GenerationManifest manifest, String cSummarySha256,
                   List<Dependency> verifiedDependencies, String projectRoot, String sourceRoot,
                   String createdAt, List<String> createdDirs, List<Entry> entries,
                   Phase phase, int entryIndex) {
        Journal withPhase(Phase value, int index) {
            return new Journal(applyId, manifest, cSummarySha256, verifiedDependencies, projectRoot,
                    sourceRoot, createdAt, createdDirs, entries, value, index);
        }

        Journal withEntry(int index, Entry entry) {
            List<Entry> copy = new ArrayList<>(entries);
            copy.set(index, entry);
            return new Journal(applyId, manifest, cSummarySha256, verifiedDependencies, projectRoot,
                    sourceRoot, createdAt, createdDirs, List.copyOf(copy), phase, index);
        }

        Journal withCreatedDirs(List<String> value) {
            return new Journal(applyId, manifest, cSummarySha256, verifiedDependencies, projectRoot,
                    sourceRoot, createdAt, List.copyOf(value), entries, phase, entryIndex);
        }

        Journal withEntries(List<Entry> value) {
            return new Journal(applyId, manifest, cSummarySha256, verifiedDependencies, projectRoot,
                    sourceRoot, createdAt, createdDirs, List.copyOf(value), phase, entryIndex);
        }

        String toJson() throws IOException {
            ObjectNode root = base(JOURNAL_KIND, applyId, manifest, cSummarySha256,
                    verifiedDependencies, projectRoot, sourceRoot, createdAt, createdDirs, entries);
            root.put("phase", phase.name());
            root.put("entryIndex", entryIndex);
            return Json.write(root);
        }
    }

    private static ObjectNode base(String kind, String applyId, GenerationManifest manifest,
                                   String cSummarySha256, List<Dependency> dependencies,
                                   String projectRoot, String sourceRoot, String createdAt,
                                   List<String> createdDirs, List<Entry> entries) throws IOException {
        ObjectNode root = Json.object();
        root.put("kind", kind);
        root.put("stateVersion", VERSION);
        root.put("applyId", applyId);
        root.set("manifest", Json.MAPPER.valueToTree(manifest));
        root.put("cSummarySha256", cSummarySha256);
        ArrayNode deps = root.putArray("verifiedDependencies");
        for (Dependency dependency : dependencies) {
            ObjectNode node = deps.addObject();
            node.put("id", dependency.id());
            node.put("sha256", dependency.sha256());
        }
        root.put("projectRoot", projectRoot);
        root.put("sourceRoot", sourceRoot);
        root.put("createdAt", createdAt);
        ArrayNode dirs = root.putArray("createdDirs");
        createdDirs.forEach(dirs::add);
        ArrayNode entryNodes = root.putArray("entries");
        for (Entry entry : entries) {
            entryNodes.add(entryNode(entry));
        }
        return root;
    }

    private static ObjectNode entryNode(Entry entry) {
        ObjectNode node = Json.object();
        node.put("relativePath", entry.relativePath());
        node.put("target", entry.target());
        if (entry.temp() == null) {
            node.putNull("temp");
        } else {
            node.put("temp", entry.temp());
        }
        node.put("expectedSha256", entry.expectedSha256());
        node.put("bytes", entry.bytes());
        if (entry.publishedIdentity() == null) {
            node.putNull("publishedIdentity");
        } else {
            ObjectNode identity = node.putObject("publishedIdentity");
            identity.put("fileKey", entry.publishedIdentity().fileKey());
            identity.put("realPath", entry.publishedIdentity().realPath());
        }
        if (entry.publishedSha256() == null) {
            node.putNull("publishedSha256");
        } else {
            node.put("publishedSha256", entry.publishedSha256());
        }
        return node;
    }

    static Path receiptPath(Path stateRoot, String sourceRoot, String planId) {
        return stateRoot.resolve("receipts").resolve(ApplyPaths.sourceKey(sourceRoot))
                .resolve(planId + ".json");
    }

    static Path journalPath(Path stateRoot) {
        return stateRoot.resolve(JOURNAL_FILE);
    }

    static void writeReceipt(Path path, Receipt receipt) throws IOException, ApplyFailure {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(path.toString());
        }
        noReplaceWrite(path, receipt.toJson().getBytes(StandardCharsets.UTF_8));
    }

    static void writeJournal(Path path, Journal journal) throws IOException {
        atomicWrite(path, journal.toJson().getBytes(StandardCharsets.UTF_8));
    }

    static Optional<Journal> readJournal(Path path) throws ApplyFailure {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        JsonNode root = readObject(path, "JOURNAL_INVALID");
        requireText(root, "kind", JOURNAL_KIND, "JOURNAL_INVALID");
        requireInt(root, "stateVersion", VERSION, "JOURNAL_INVALID");
        Phase phase;
        try {
            phase = Phase.valueOf(requiredText(root, "phase", "JOURNAL_INVALID"));
        } catch (IllegalArgumentException ex) {
            throw invalid("JOURNAL_INVALID", "Unknown journal phase", ex);
        }
        return Optional.of(new Journal(
                requiredText(root, "applyId", "JOURNAL_INVALID"),
                manifest(root, "JOURNAL_INVALID"),
                requiredHash(root, "cSummarySha256", "JOURNAL_INVALID"),
                dependencies(root, "JOURNAL_INVALID"),
                requiredText(root, "projectRoot", "JOURNAL_INVALID"),
                requiredText(root, "sourceRoot", "JOURNAL_INVALID"),
                requiredText(root, "createdAt", "JOURNAL_INVALID"),
                strings(root, "createdDirs", "JOURNAL_INVALID"),
                entries(root, "JOURNAL_INVALID"),
                phase,
                requiredInt(root, "entryIndex", "JOURNAL_INVALID")));
    }

    static Receipt readReceipt(Path path) throws ApplyFailure {
        JsonNode root = readObject(path, "LEDGER_INVALID");
        requireText(root, "kind", RECEIPT_KIND, "LEDGER_INVALID");
        requireInt(root, "stateVersion", VERSION, "LEDGER_INVALID");
        requireText(root, "status", "APPLIED", "LEDGER_INVALID");
        return new Receipt(
                requiredText(root, "applyId", "LEDGER_INVALID"),
                manifest(root, "LEDGER_INVALID"),
                requiredHash(root, "cSummarySha256", "LEDGER_INVALID"),
                dependencies(root, "LEDGER_INVALID"),
                requiredText(root, "projectRoot", "LEDGER_INVALID"),
                requiredText(root, "sourceRoot", "LEDGER_INVALID"),
                requiredText(root, "createdAt", "LEDGER_INVALID"),
                strings(root, "createdDirs", "LEDGER_INVALID"),
                entries(root, "LEDGER_INVALID"));
    }

    static List<Receipt> readReceipts(Path stateRoot) throws ApplyFailure {
        Path root = stateRoot.resolve("receipts");
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Receipt> result = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".json")).toList()) {
                result.add(readReceipt(path));
            }
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, "LEDGER_UNREADABLE",
                    "Cannot enumerate the v2 ledger: " + root, ex);
        }
        return List.copyOf(result);
    }

    private static GenerationManifest manifest(JsonNode root, String code) throws ApplyFailure {
        JsonNode node = root.get("manifest");
        if (node == null || !node.isObject()) {
            throw invalid(code, "manifest must be an object", null);
        }
        try {
            return Json.MAPPER.treeToValue(node, GenerationManifest.class);
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid(code, "manifest is invalid: " + ex.getMessage(), ex);
        }
    }

    private static List<Dependency> dependencies(JsonNode root, String code) throws ApplyFailure {
        JsonNode node = root.get("verifiedDependencies");
        if (node == null || !node.isArray()) {
            throw invalid(code, "verifiedDependencies must be an array", null);
        }
        List<Dependency> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) throw invalid(code, "Each dependency must be an object", null);
            String id = requiredText(item, "id", code);
            String hash = requiredHash(item, "sha256", code);
            result.add(new Dependency(id, hash));
        }
        return List.copyOf(result);
    }

    private static List<Entry> entries(JsonNode root, String code) throws ApplyFailure {
        JsonNode node = root.get("entries");
        if (node == null || !node.isArray()) {
            throw invalid(code, "entries must be an array", null);
        }
        List<Entry> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) throw invalid(code, "Each entry must be an object", null);
            PublishedIdentity identity = null;
            JsonNode identityNode = item.get("publishedIdentity");
            if (identityNode != null && !identityNode.isNull()) {
                if (!identityNode.isObject()) {
                    throw invalid(code, "publishedIdentity must be an object or null", null);
                }
                identity = new PublishedIdentity(
                        requiredText(identityNode, "fileKey", code),
                        requiredText(identityNode, "realPath", code));
            }
            JsonNode temp = item.get("temp");
            JsonNode publishedHash = item.get("publishedSha256");
            result.add(new Entry(
                    requiredText(item, "relativePath", code),
                    requiredText(item, "target", code),
                    temp == null || temp.isNull() ? null : requiredText(item, "temp", code),
                    requiredHash(item, "expectedSha256", code),
                    requiredLong(item, "bytes", code),
                    identity,
                    publishedHash == null || publishedHash.isNull()
                            ? null : requiredHash(item, "publishedSha256", code)));
        }
        return List.copyOf(result);
    }

    private static List<String> strings(JsonNode root, String field, String code) throws ApplyFailure {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            throw invalid(code, field + " must be an array", null);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) throw invalid(code, field + " items must be strings", null);
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static void requireInt(JsonNode root, String field, int expected, String code)
            throws ApplyFailure {
        if (requiredInt(root, field, code) != expected) {
            throw invalid(code, field + " must be " + expected, null);
        }
    }

    private static int requiredInt(JsonNode root, String field, String code) throws ApplyFailure {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid(code, field + " must be an integer", null);
        }
        return node.asInt();
    }

    private static long requiredLong(JsonNode root, String field, String code) throws ApplyFailure {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber()) {
            throw invalid(code, field + " must be an integer", null);
        }
        return node.asLong();
    }

    private static void requireText(JsonNode root, String field, String expected, String code)
            throws ApplyFailure {
        if (!requiredText(root, field, code).equals(expected)) {
            throw invalid(code, field + " must be " + expected, null);
        }
    }

    private static String requiredText(JsonNode root, String field, String code) throws ApplyFailure {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw invalid(code, field + " is required", null);
        }
        return node.asText();
    }

    private static String requiredHash(JsonNode root, String field, String code) throws ApplyFailure {
        String value = requiredText(root, field, code);
        if (!ApplyHash.isSha256(value)) {
            throw invalid(code, field + " must be 64 lowercase hex characters", null);
        }
        return value;
    }

    private static JsonNode readObject(Path path, String code) throws ApplyFailure {
        ApplyState.assertSafeStateFile(path, code);
        try {
            long size = Files.size(path);
            if (size <= 0 || size > 1_048_576) {
                throw invalid(code, "State file size is outside the supported range: " + path, null);
            }
            JsonNode root = Json.MAPPER.readTree(Files.readAllBytes(path));
            if (root == null || !root.isObject()) {
                throw invalid(code, "State file root must be a JSON object: " + path, null);
            }
            return root;
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code,
                    "Cannot parse state file: " + path, ex);
        }
    }

    private static ApplyFailure invalid(String code, String message, Throwable cause) {
        return new ApplyFailure(ApplyStatus.RECOVERY_REQUIRED, code, message, cause);
    }

    static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temp = parent.resolve("." + target.getFileName() + ".tmp");
        Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void noReplaceWrite(Path target, byte[] bytes) throws IOException, ApplyFailure {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temp = parent.resolve("." + target.getFileName() + ".tmp");
        Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.createLink(target, temp);
        } catch (FileAlreadyExistsException ex) {
            Files.deleteIfExists(temp);
            throw ex;
        } catch (UnsupportedOperationException ex) {
            Files.deleteIfExists(temp);
            throw new ApplyFailure(ApplyStatus.REJECTED, "STATE_PUBLICATION_UNSUPPORTED",
                    "The filesystem does not support no-replace state publication", ex);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String now() {
        return Instant.now().toString();
    }
}

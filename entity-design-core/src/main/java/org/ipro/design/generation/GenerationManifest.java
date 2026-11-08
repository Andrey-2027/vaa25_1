package org.ipro.design.generation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.ipro.design.RecipeRef;

/** Shared A/C/D transport metadata; content stays in the reviewable plan. No filesystem IO. */
public record GenerationManifest(String kind, String planId, RecipeRef recipe, String generatorVersion,
                                 String fingerprintProfile, List<Entry> entries, String manifestSha256) {
    public static final String KIND = "g1-generation-manifest-v2";
    public static final String HASH_PROFILE = "g1-file-set-sha256-v2";
    public static final String LEGACY_FINGERPRINT = "plan-fingerprint-v1";
    public static final String EXTENSION_FINGERPRINT = "plan-fingerprint-v2";
    public static final String REGISTER_FINGERPRINT = "plan-fingerprint-v3";
    public static final String FORM_CONFIGURATION_FINGERPRINT = "plan-fingerprint-v5";
    public static final String REGISTER_RLS_FINGERPRINT = "plan-fingerprint-v4";

    public record Entry(GenerationFile.Operation operation, String relativePath, String sha256, long bytes) {
        public Entry {
            Objects.requireNonNull(operation);
            // Reuse the canonical Java path and digest syntax without accepting source content here.
            new GenerationFile(operation, relativePath, "// manifest\n", sha256);
            if (bytes <= 0) throw new IllegalArgumentException("Source bytes must be positive");
        }
    }

    public GenerationManifest {
        if (!KIND.equals(kind)) throw new IllegalArgumentException("Unsupported manifest kind");
        if (planId == null || !planId.matches("g1-[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid planId");
        Objects.requireNonNull(recipe);
        if (generatorVersion == null || generatorVersion.isBlank()) throw new IllegalArgumentException("Missing generator version");
        if (!LEGACY_FINGERPRINT.equals(fingerprintProfile) && !EXTENSION_FINGERPRINT.equals(fingerprintProfile)
                && !FORM_CONFIGURATION_FINGERPRINT.equals(fingerprintProfile) && !REGISTER_FINGERPRINT.equals(fingerprintProfile) && !REGISTER_RLS_FINGERPRINT.equals(fingerprintProfile))
            throw new IllegalArgumentException("Unsupported fingerprint profile");
        entries = List.copyOf(entries);
        var paths = new HashSet<String>();
        if (entries.isEmpty()) throw new IllegalArgumentException("Empty manifest");
        for (var entry : entries)
            if (!paths.add(entry.relativePath().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate source path");
        if (!hash(entries).equals(manifestSha256)) throw new IllegalArgumentException("Manifest hash mismatch");
    }

    public static GenerationManifest from(GenerationPlan plan, String fingerprintProfile) {
        var entries = plan.files().stream().map(file -> {
            if (!GenerationHash.sha256(file.content()).equals(file.sha256()))
                throw new IllegalArgumentException("Source hash mismatch: " + file.relativePath());
            return new Entry(file.operation(), file.relativePath(), file.sha256(),
                    file.content().getBytes(StandardCharsets.UTF_8).length);
        }).toList();
        return new GenerationManifest(KIND, plan.planId(), plan.recipe(), plan.generatorVersion(),
                fingerprintProfile, entries, hash(entries));
    }

    /** Exact ordered equality rejects missing, extra, reordered and changed files. */
    public void verify(GenerationPlan plan, String expectedFingerprintProfile) {
        if (!equals(from(plan, expectedFingerprintProfile))) throw new IllegalArgumentException("Manifest does not match plan");
    }

    /** SHA-256: framed UTF-8 profile, int32 entry count, then path/op/int64 bytes/hash per entry. */
    public static String hash(List<Entry> entries) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            text(digest, HASH_PROFILE);
            digest.update(ByteBuffer.allocate(4).putInt(entries.size()).array());
            for (var entry : entries) {
                text(digest, entry.relativePath());
                text(digest, entry.operation().name());
                digest.update(ByteBuffer.allocate(8).putLong(entry.bytes()).array());
                text(digest, entry.sha256());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void text(MessageDigest digest, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
}

package org.ipro.design.generation;

import java.util.Objects;
import java.util.regex.Pattern;

/** A data-only generated source file entry. It performs no IO and carries no absolute path. */
public record GenerationFile(Operation operation, String relativePath, String content, String sha256) {
    public enum Operation { CREATE }

    private static final Pattern RELATIVE_PATH = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:/[A-Za-z_][A-Za-z0-9_]*)*/[A-Za-z_][A-Za-z0-9_]*\\.java");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SCHEMA_PATH = Pattern.compile("schema/[A-Za-z_][A-Za-z0-9_]*\\.sql");

    public GenerationFile {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sha256, "sha256");
        if (!RELATIVE_PATH.matcher(relativePath).matches() && !SCHEMA_PATH.matcher(relativePath).matches())
            throw new IllegalArgumentException("Expected a Java source or schema/<Class>.sql path: " + relativePath);
        if (content.isEmpty() || content.contains("\r") || !content.endsWith("\n") || content.endsWith("\n\n"))
            throw new IllegalArgumentException("Content must be non-empty UTF-8 text with LF and exactly one final LF");
        if (!SHA256.matcher(sha256).matches())
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
    }
}

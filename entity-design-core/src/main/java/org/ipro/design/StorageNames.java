package org.ipro.design;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Names the database sees: the shared 63-character identifier limit of the supported databases. */
public final class StorageNames {
    private StorageNames() {}
    /** The longest unquoted PostgreSQL identifier; the draft storage profile uses the same limit. */
    public static final int MAX_LENGTH = 63;
    private static final int KEPT = 54, HASH = 8;

    /**
     * A constraint or index name that fits the identifier limit. A name up to 63 characters is
     * returned unchanged; a longer one keeps its first 54 characters, {@code _} and 8 hex digits of
     * the SHA-256 of the whole name, so two long names with a common start stay different.
     */
    public static String constraint(String name) {
        if (name.length() <= MAX_LENGTH) return name;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            return name.substring(0, KEPT) + "_" + HexFormat.of().formatHex(digest).substring(0, HASH);
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}

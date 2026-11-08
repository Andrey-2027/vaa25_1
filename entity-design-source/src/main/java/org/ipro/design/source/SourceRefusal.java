package org.ipro.design.source;

import java.util.Objects;

/** An addressed refusal: what went wrong, where, and why. Never a guess in place of a refusal. */
public final class SourceRefusal extends RuntimeException {
    public enum Code {
        FORM_COLUMNS_UNSUPPORTED, FORM_COLUMNS_CONFLICT,
        MISSING_JDK_COMPILER, SOURCE_ENCODING_INVALID, SOURCE_UNREADABLE, SOURCE_UNPARSABLE, NOT_AN_ENTITY_CLASS,
        /** The patch broke a guarantee: bytes outside the edits changed, or the result does not parse. */
        PATCH_SELF_CHECK_FAILED,
        /** The change cannot be made in this file without a guess. */
        MODIFICATION_UNSUPPORTED,
        /** A name the patch needs is already taken by another type. */
        IMPORT_CONFLICT,
        /** The file is not the one the entity was read from. */
        STALE_BASE,
        /** The table or the target of a reference cannot be determined from the sources, so no SQL is proposed. */
        SCHEMA_UNKNOWN,
        /** The edited draft does not differ from the file. */
        NOTHING_TO_DO,
        /** A plan needs the classpath of the project to check the patched file. */
        CLASSPATH_MISSING,
        /** The patched file does not compile on the given classpath. */
        COMPILE_FAILED,
        /** A plan directory is incomplete, altered or not a plan. */
        PLAN_INVALID
    }
    private final Code code;
    private final String target, reason;

    public SourceRefusal(Code code, String target, String reason) {
        super(code + " " + target + ": " + reason);
        this.code = Objects.requireNonNull(code); this.target = Objects.requireNonNull(target); this.reason = Objects.requireNonNull(reason);
    }
    public Code code() { return code; }
    public String target() { return target; }
    public String reason() { return reason; }
}

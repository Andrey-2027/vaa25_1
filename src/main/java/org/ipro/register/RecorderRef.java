package org.ipro.register;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable logical identity of one recorder document. Resolving the reference to a document
 * remains a separate operation so register reads do not implicitly read the document.
 */
@Embeddable
@Access(AccessType.FIELD)
public class RecorderRef {
    public static final int TYPE_CODE_MAX_LENGTH = 64;
    private static final Pattern CODE_PATTERN = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    @Column(name = "recorder_type", nullable = false, updatable = false, length = TYPE_CODE_MAX_LENGTH)
    private String typeCode;

    @Column(name = "recorder_id", nullable = false, updatable = false)
    private Long id;

    /** Required by JPA. */
    protected RecorderRef() {
    }

    public RecorderRef(String typeCode, Long id) {
        this.typeCode = requireTypeCode(typeCode);
        this.id = Objects.requireNonNull(id, "recorder id must not be null");
    }

    public String getTypeCode() {
        return typeCode;
    }

    public Long getId() {
        return id;
    }

    public static boolean isValidTypeCode(String value) {
        return value != null && value.length() <= TYPE_CODE_MAX_LENGTH && CODE_PATTERN.matcher(value).matches();
    }

    private static String requireTypeCode(String value) {
        if (!isValidTypeCode(value)) {
            throw new IllegalArgumentException("Recorder type code must be lowercase ASCII kebab-case (1.."
                + TYPE_CODE_MAX_LENGTH + " characters)");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RecorderRef that)) return false;
        return Objects.equals(typeCode, that.typeCode) && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeCode, id);
    }
}

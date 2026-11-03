package org.ipro.design.generation;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.ipro.design.RecipeCatalog.FieldType;

/**
 * Java text of one entity member, shared by the generator that writes a whole file and by the
 * patcher that adds one field to an existing file. Both must produce the same text for the same
 * field, so there is one implementation. Lines end with {@code \n}; the caller converts them.
 */
public final class JavaMemberFragments {
    public static final String INDENT = "    ";

    private JavaMemberFragments() { }

    /** A Java type as written in a field declaration, and the import it needs, if any. */
    public record JavaType(String name, String importName) { }

    /** What a field is: everything its declaration says except how it is published. */
    public record FieldSpec(String name, String label, FieldType type, JavaType javaType, String columnName,
                            boolean serverRequired, Integer length, Integer precision, Integer scale) {
        public FieldSpec {
            Objects.requireNonNull(name); Objects.requireNonNull(label); Objects.requireNonNull(type);
            Objects.requireNonNull(javaType); Objects.requireNonNull(columnName);
        }
    }

    /** {@code grid = @GridColumn(visible = ..., order = ...)}; a null part is not written. */
    public record Grid(Boolean visible, Integer order) { }

    /**
     * How a field appears: the {@code order} of its {@code @FieldMetadata}, the required mode name
     * ({@code AUTO} is not written), whether it is hidden from the form, and its grid column
     * (null: no {@code grid} argument at all).
     */
    public record Publication(Integer order, String requiredMode, boolean hidden, Grid grid) { }

    public static JavaType javaType(FieldType type) {
        return switch (type) {
            case STRING -> new JavaType("String", null);
            case INTEGER -> new JavaType("Integer", null);
            case LONG -> new JavaType("Long", null);
            case DECIMAL -> new JavaType("BigDecimal", "java.math.BigDecimal");
            case BOOLEAN -> new JavaType("Boolean", null);
            case DATE -> new JavaType("LocalDate", "java.time.LocalDate");
            case DATETIME -> new JavaType("LocalDateTime", "java.time.LocalDateTime");
            case REFERENCE -> throw new IllegalArgumentException("REFERENCE is outside the first catalog generator scope");
        };
    }

    /** A reference is written with its fully qualified source name, which resolves same-name collisions. */
    public static JavaType referenceType(String sourceTypeName) { return new JavaType(sourceTypeName, null); }

    /** The annotations and the declaration of one field, ending with a line break. */
    public static String field(FieldSpec field, Publication publication) {
        var out = new StringBuilder();
        if (field.serverRequired()) out.append(INDENT).append("@NotNull\n");
        switch (field.type()) {
            case STRING -> out.append(INDENT).append("@Size(max = ")
                    .append(required(field.length(), "STRING length")).append(")\n");
            case DECIMAL -> {
                int precision = required(field.precision(), "DECIMAL precision");
                int scale = required(field.scale(), "DECIMAL scale");
                if (scale > precision) throw new IllegalStateException("DECIMAL scale exceeds precision");
                out.append(INDENT).append("@Digits(integer = ").append(precision - scale)
                        .append(", fraction = ").append(scale).append(")\n");
            }
            default -> { }
        }
        if (field.type() == FieldType.REFERENCE) {
            out.append(INDENT).append("@ManyToOne(fetch = FetchType.LAZY, optional = ")
                    .append(!field.serverRequired()).append(")\n");
        }
        out.append(INDENT).append(field.type() == FieldType.REFERENCE ? "@JoinColumn(name = " : "@Column(name = ")
                .append(literal(field.columnName()));
        if (field.serverRequired()) out.append(", nullable = false");
        switch (field.type()) {
            case STRING -> out.append(", length = ").append(required(field.length(), "STRING length"));
            case DECIMAL -> out.append(", precision = ").append(required(field.precision(), "DECIMAL precision"))
                    .append(", scale = ").append(required(field.scale(), "DECIMAL scale"));
            default -> { }
        }
        out.append(")\n");
        out.append(INDENT).append("@FieldMetadata(label = ").append(literal(field.label()));
        if (publication.order() != null) out.append(", order = ").append(publication.order());
        if (!"AUTO".equals(publication.requiredMode())) out.append(", required = RequiredMode.").append(publication.requiredMode());
        if (publication.hidden()) out.append(", hidden = true");
        var grid = publication.grid();
        if (grid != null) {
            out.append(", grid = @GridColumn(");
            boolean first = true;
            if (grid.visible() != null) { out.append("visible = ").append(grid.visible()); first = false; }
            if (grid.order() != null) out.append(first ? "" : ", ").append("order = ").append(grid.order());
            out.append(')');
        }
        out.append(")\n");
        out.append(INDENT).append("private ").append(field.javaType().name()).append(' ').append(field.name()).append(";\n");
        return out.toString();
    }

    /** Getter and setter, without a leading blank line. */
    public static String accessor(String name, String type) {
        var out = new StringBuilder();
        var suffix = capitalized(name);
        out.append(INDENT).append("public ").append(type).append(" get").append(suffix).append("() {\n");
        out.append(INDENT).append(INDENT).append("return ").append(name).append(";\n");
        out.append(INDENT).append("}\n\n");
        out.append(INDENT).append("public void set").append(suffix).append('(').append(type)
                .append(' ').append(name).append(") {\n");
        out.append(INDENT).append(INDENT).append("this.").append(name)
                .append(" = ").append(name).append(";\n");
        out.append(INDENT).append("}\n");
        return out.toString();
    }

    /** The imports {@link #field} relies on, as fully qualified names. */
    public static Set<String> imports(FieldSpec field, Publication publication) {
        var imports = new TreeSet<String>();
        imports.add("jakarta.persistence.Column");
        imports.add("org.ipro.metadata.annotation.FieldMetadata");
        if (publication.grid() != null) imports.add("org.ipro.metadata.annotation.GridColumn");
        if (field.type() == FieldType.REFERENCE) {
            imports.add("jakarta.persistence.ManyToOne");
            imports.add("jakarta.persistence.FetchType");
            imports.add("jakarta.persistence.JoinColumn");
        }
        if (field.javaType().importName() != null) imports.add(field.javaType().importName());
        if (field.serverRequired()) imports.add("jakarta.validation.constraints.NotNull");
        switch (field.type()) {
            case STRING -> imports.add("jakarta.validation.constraints.Size");
            case DECIMAL -> imports.add("jakarta.validation.constraints.Digits");
            default -> { }
        }
        if (!"AUTO".equals(publication.requiredMode())) imports.add("org.ipro.metadata.annotation.RequiredMode");
        return imports;
    }

    public static String literal(String value) {
        Objects.requireNonNull(value, "Java literal value");
        var out = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (character < 0x20) out.append(unicodeEscape(character));
                    else out.append(character);
                }
            }
        }
        return out.append('"').toString();
    }

    static String capitalized(String name) {
        if (name.isEmpty()) throw new IllegalStateException("Field name is required");
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static int required(Integer value, String what) {
        if (value == null) throw new IllegalStateException(what + " is required");
        return value;
    }

    private static String unicodeEscape(char character) {
        var hex = Integer.toHexString(character);
        return "\\u" + "0".repeat(4 - hex.length()) + hex;
    }
}

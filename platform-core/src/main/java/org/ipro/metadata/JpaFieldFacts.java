package org.ipro.metadata;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.lang.reflect.Field;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;

/**
 * Annotation-only JPA/Bean Validation facts about a declared field or JPA getter.
 *
 * <p>Consumers that only need to display storage and size facts can use this helper without
 * importing JPA annotation types themselves. The values are declared annotation values,
 * including annotation defaults; they are not inspected database DDL.</p>
 */
public final class JpaFieldFacts {

    private JpaFieldFacts() {
    }

    /** Declaration supplying requiredness: UI on the field, JPA on its actual member,
     * Bean Validation on either (field wins when both declare it). */
    public static String requiredSymbol(Field field, AnnotatedElement persistentMember, FactOrigin origin) {
        if (origin == FactOrigin.PLATFORM_DEFAULT || origin == FactOrigin.UNKNOWN) return "";
        AnnotatedElement source = switch (origin) {
            case JPA_MAPPING -> persistentMember;
            case BEAN_VALIDATION -> field != null && (field.isAnnotationPresent(NotNull.class)
                || field.isAnnotationPresent(NotBlank.class) || field.isAnnotationPresent(NotEmpty.class))
                    ? field : persistentMember;
            default -> field;
        };
        return source instanceof Member member
            ? member.getDeclaringClass().getName() + "#" + member.getName() : "";
    }

    /** Declared {@link Size} constraints in declaration order. */
    public static List<SizeFact> sizeFacts(Field field) {
        return sizeFacts((AnnotatedElement) field);
    }

    /** Constraints on the actual JPA member (field or getter). */
    public static List<SizeFact> sizeFacts(AnnotatedElement field) {
        if (field == null) {
            return List.of();
        }
        List<SizeFact> facts = new ArrayList<>();
        for (Size size : field.getAnnotationsByType(Size.class)) {
            facts.add(new SizeFact(size.min(), size.max(), List.of(size.groups())));
        }
        return List.copyOf(facts);
    }

    /** Declared nullability and column facts; absent {@code @Column} stays absent. */
    public static StorageFact storageFacts(Field field) {
        return storageFacts((AnnotatedElement) field);
    }

    /** Mapping declarations on the actual JPA member (field or getter). */
    public static StorageFact storageFacts(AnnotatedElement field) {
        if (field == null) {
            return new StorageFact(List.of(), null);
        }
        List<NullabilityFact> nullability = new ArrayList<>();
        Column column = field.getAnnotation(Column.class);
        if (column != null) {
            nullability.add(new NullabilityFact(NullabilityKind.COLUMN_NULLABLE, column.nullable()));
        }
        JoinColumn join = field.getAnnotation(JoinColumn.class);
        if (join != null) {
            nullability.add(new NullabilityFact(NullabilityKind.JOIN_COLUMN_NULLABLE, join.nullable()));
        }
        Basic basic = field.getAnnotation(Basic.class);
        if (basic != null) {
            nullability.add(new NullabilityFact(NullabilityKind.BASIC_OPTIONAL, basic.optional()));
        }
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        if (manyToOne != null) {
            nullability.add(new NullabilityFact(NullabilityKind.MANY_TO_ONE_OPTIONAL, manyToOne.optional()));
        }
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        if (oneToOne != null) {
            nullability.add(new NullabilityFact(NullabilityKind.ONE_TO_ONE_OPTIONAL, oneToOne.optional()));
        }
        return new StorageFact(nullability,
            column == null ? null : new ColumnFact(column.length(), column.precision(), column.scale()));
    }

    public record SizeFact(int min, int max, List<Class<?>> groups) {
        public SizeFact {
            groups = List.copyOf(groups);
        }
    }

    public enum NullabilityKind {
        COLUMN_NULLABLE, JOIN_COLUMN_NULLABLE, BASIC_OPTIONAL, MANY_TO_ONE_OPTIONAL, ONE_TO_ONE_OPTIONAL
    }

    public record NullabilityFact(NullabilityKind kind, boolean allowsNull) {
    }

    /** Values from {@link Column}, including annotation defaults. */
    public record ColumnFact(int length, int precision, int scale) {
    }

    public record StorageFact(List<NullabilityFact> nullability, ColumnFact column) {
        public StorageFact {
            nullability = List.copyOf(nullability);
        }
    }
}

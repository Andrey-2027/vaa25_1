package org.ipro.metadata;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Version;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.ipro.metadata.annotation.FieldMetadata;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Строит {@link StructuralAttribute} из JPA metamodel.
 *
 * <p>Сканирование выполняется только по уже существующему {@link Metamodel};
 * для access mode и аннотаций члена используется member из JPA-атрибута, а не
 * повторное угадывание по reflection. Второй classpath scan и собственный
 * discovery-порт здесь намеренно не создаются (это граница ET3). Scanner не
 * является Spring-бином и не кэширует результат: его кэширует {@link MetadataResolver}.</p>
 */
final class StructuralAttributeScanner {

    private StructuralAttributeScanner() {
    }

    /**
     * @throws IllegalArgumentException если {@code entityClass} не является managed JPA-типом
     */
    static List<StructuralAttribute> scan(Class<?> entityClass, Metamodel metamodel) {
        Objects.requireNonNull(entityClass, "entityClass");
        Objects.requireNonNull(metamodel, "metamodel");

        ManagedType<?> managedType;
        try {
            managedType = metamodel.managedType(entityClass);
        } catch (IllegalArgumentException notManaged) {
            throw new IllegalArgumentException(
                    "Class " + entityClass.getName() + " is not a managed JPA type", notManaged);
        }

        List<StructuralAttribute> attributes = new ArrayList<>();
        for (Attribute<?, ?> attribute : managedType.getAttributes()) {
            attributes.add(toStructuralAttribute(entityClass, attribute));
        }
        attributes.sort(Comparator.comparing(StructuralAttribute::name));
        return List.copyOf(attributes);
    }

    private static StructuralAttribute toStructuralAttribute(Class<?> entityClass, Attribute<?, ?> attribute) {
        String name = attribute.getName();
        Class<?> declaringType = attribute.getDeclaringType().getJavaType();
        JpaMember jpaMember = member(attribute);
        AnnotatedElement member = jpaMember.element();

        boolean nullable = resolveNullable(attribute, member);
        Required required = resolveRequired(attribute.getJavaType(), nullable, member,
            findField(entityClass, name));

        return new StructuralAttribute(
                name,
                declaringType,
                attribute.getJavaType(),
                persistentKind(attribute),
                jpaMember.accessMode(),
                technicalPurpose(member),
                nullable,
                required.value(),
                required.origin(),
                findFieldMetadata(entityClass, name),
                member
        );
    }

    private static StructuralAttribute.PersistentKind persistentKind(Attribute<?, ?> attribute) {
        if (attribute instanceof PluralAttribute<?, ?, ?>) {
            return StructuralAttribute.PersistentKind.ASSOCIATION_TO_MANY;
        }
        return switch (attribute.getPersistentAttributeType()) {
            case BASIC, EMBEDDED -> StructuralAttribute.PersistentKind.BASIC;
            case MANY_TO_ONE, ONE_TO_ONE -> StructuralAttribute.PersistentKind.ASSOCIATION_TO_ONE;
            case ONE_TO_MANY, MANY_TO_MANY, ELEMENT_COLLECTION ->
                    StructuralAttribute.PersistentKind.ASSOCIATION_TO_MANY;
        };
    }

    private static boolean resolveNullable(Attribute<?, ?> attribute, AnnotatedElement member) {
        if (attribute.getJavaType().isPrimitive()) {
            return false;
        }
        if (attribute instanceof PluralAttribute<?, ?, ?>) {
            return true;
        }
        if (attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.MANY_TO_ONE
                || attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.ONE_TO_ONE) {
            ManyToOne manyToOne = annotation(member, ManyToOne.class);
            if (manyToOne != null && !manyToOne.optional()) {
                return false;
            }
            OneToOne oneToOne = annotation(member, OneToOne.class);
            if (oneToOne != null && !oneToOne.optional()) {
                return false;
            }
            JoinColumn joinColumn = annotation(member, JoinColumn.class);
            return joinColumn == null || joinColumn.nullable();
        }
        Column column = annotation(member, Column.class);
        if (column != null && !column.nullable()) {
            return false;
        }
        Basic basic = annotation(member, Basic.class);
        return basic == null || basic.optional();
    }

    private static Required resolveRequired(Class<?> javaType, boolean nullable, AnnotatedElement member,
            Field backingField) {
        if (hasAny(member, NotNull.class, NotBlank.class, NotEmpty.class)
                || hasAny(backingField, NotNull.class, NotBlank.class, NotEmpty.class)) {
            return new Required(true, FactOrigin.BEAN_VALIDATION);
        }
        if (!javaType.isPrimitive() && !nullable) {
            return new Required(true, FactOrigin.JPA_MAPPING);
        }
        return new Required(false, FactOrigin.PLATFORM_DEFAULT);
    }

    private static StructuralAttribute.TechnicalPurpose technicalPurpose(AnnotatedElement member) {
        if (hasAny(member, Id.class, EmbeddedId.class)) {
            return StructuralAttribute.TechnicalPurpose.IDENTIFIER;
        }
        if (hasAny(member, Version.class)) {
            return StructuralAttribute.TechnicalPurpose.VERSION;
        }
        if (hasAny(member, CreatedDate.class, CreatedBy.class, LastModifiedDate.class, LastModifiedBy.class)) {
            return StructuralAttribute.TechnicalPurpose.AUDIT;
        }
        return StructuralAttribute.TechnicalPurpose.BUSINESS;
    }

    private static JpaMember member(Attribute<?, ?> attribute) {
        Member member = attribute.getJavaMember();
        if (member instanceof Field field) {
            return new JpaMember(StructuralAttribute.AccessMode.FIELD, field);
        }
        if (member instanceof Method method) {
            return new JpaMember(StructuralAttribute.AccessMode.PROPERTY, method);
        }
        throw new IllegalStateException("JPA metamodel attribute "
                + attribute.getDeclaringType().getJavaType().getName() + "#" + attribute.getName()
                + " has no supported Java member: " + member);
    }

    private static FieldMetadata findFieldMetadata(Class<?> entityClass, String attributeName) {
        Field field = findField(entityClass, attributeName);
        return field == null ? null : field.getAnnotation(FieldMetadata.class);
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // continue with superclass
            }
        }
        return null;
    }

    private static boolean hasAny(AnnotatedElement element, Class<? extends Annotation>... annotations) {
        if (element == null) {
            return false;
        }
        for (Class<? extends Annotation> annotation : annotations) {
            if (element.isAnnotationPresent(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static <A extends Annotation> A annotation(AnnotatedElement element, Class<A> type) {
        return element == null ? null : element.getAnnotation(type);
    }

    private record Required(boolean value, FactOrigin origin) {
    }

    private record JpaMember(StructuralAttribute.AccessMode accessMode, AnnotatedElement element) {
    }
}

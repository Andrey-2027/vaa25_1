package org.ipro.metadata;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.annotation.CatalogOwner;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.TableSectionMetadata;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Разбор объявленной owner-capability справочника.
 *
 * <p>Правило выводится из объявления, а не из имени поля: {@link CatalogOwner} называет
 * единственное поле-владельца, и только оно становится capability. Обычная ссылка с именем
 * {@code owner}/{@code parent} остаётся обычной ссылкой; parent owned-секции владельцем root
 * не становится.</p>
 *
 * <p>Разбор не бросает исключений: результат несёт либо resolved
 * {@link CatalogOwnerDescriptor}, либо адресные {@link MetadataDiagnostic}, которые
 * startup-проверка превращает в остановку старта. Тип без объявления даёт пустой результат —
 * это не ошибка.</p>
 *
 * <p>Кэш принадлежит экземпляру; аннотации и JPA-маппинг неизменяемы после старта, поэтому
 * повторный разбор не нужен. Для тестов достаточен новый экземпляр.</p>
 */
public final class CatalogOwnerResolver {

    private final Map<Class<?>, CatalogOwnerResolution> cache = new ConcurrentHashMap<>();

    /** Разобрать owner-capability типа. */
    public CatalogOwnerResolution resolve(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        return cache.computeIfAbsent(type, CatalogOwnerResolver::resolveUncached);
    }

    /** Resolved owner только для валидного объявления; пусто для обычного справочника. */
    public Optional<CatalogOwnerDescriptor> ownerOf(Class<?> type) {
        return resolve(type).descriptor();
    }

    private static CatalogOwnerResolution resolveUncached(Class<?> type) {
        List<Class<?>> declaring = declaringClasses(type);
        if (declaring.isEmpty()) {
            return CatalogOwnerResolution.none();
        }
        if (declaring.size() > 1) {
            List<String> properties = new ArrayList<>(declaring.size());
            for (Class<?> declared : declaring) {
                properties.add(declared.getSimpleName() + "#"
                    + declared.getAnnotation(CatalogOwner.class).property());
            }
            return error(MetadataDiagnosticCodes.CATALOG_OWNER_DUPLICATE, type, "",
                "capability объявлена более одного раза в иерархии: " + properties
                    + "; @CatalogOwner не наследуется и должен быть объявлен ровно один раз");
        }

        String property = declaring.getFirst().getAnnotation(CatalogOwner.class).property();
        if (property == null || property.isBlank()) {
            return error(MetadataDiagnosticCodes.CATALOG_OWNER_PROPERTY_MISSING, type, "",
                "@CatalogOwner.property пуст: имя поля-владельца обязательно");
        }

        String subjectProblem = subjectProblem(type);
        if (subjectProblem != null) {
            return error(MetadataDiagnosticCodes.CATALOG_OWNER_SUBJECT_INVALID, type, property,
                subjectProblem);
        }

        Field field = declaredField(type, property);
        if (field == null) {
            return error(MetadataDiagnosticCodes.CATALOG_OWNER_PROPERTY_MISSING, type, property,
                "поле-владелец '" + property + "' не найдено в " + type.getName()
                    + " и его superclass'ах");
        }

        String associationProblem = associationProblem(field);
        if (associationProblem != null) {
            return error(MetadataDiagnosticCodes.CATALOG_OWNER_ASSOCIATION_INVALID, type, property,
                associationProblem);
        }

        String requiredProblem = requiredProblem(field);
        if (requiredProblem != null) {
            return error(MetadataDiagnosticCodes.CATALOG_OWNER_REQUIRED_MISMATCH, type, property,
                requiredProblem);
        }

        String targetProblem = targetProblem(type, field.getType());
        if (targetProblem != null) {
            return error(MetadataDiagnosticCodes.CATALOG_OWNER_TARGET_INVALID, type, property,
                targetProblem);
        }

        return new CatalogOwnerResolution(
            new CatalogOwnerDescriptor(property, field.getType().getName()), List.of());
    }

    /** Классы иерархии, объявившие capability. Без @Inherited: наследования нет. */
    private static List<Class<?>> declaringClasses(Class<?> type) {
        List<Class<?>> result = new ArrayList<>(2);
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            if (current.isAnnotationPresent(CatalogOwner.class)) {
                result.add(current);
            }
        }
        return result;
    }

    /** Capability допустима только у CATALOG root: не документ, не строка секции. */
    private static String subjectProblem(Class<?> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return "capability объявлена на abstract/interface типе: владельцем может быть только"
                + " конкретный управляемый catalog root";
        }
        if (type.isAnnotationPresent(TableSectionMetadata.class)) {
            return "capability объявлена у строки owned-секции: parent строки — не root owner";
        }
        if (!type.isAnnotationPresent(Entity.class)) {
            return "capability объявлена у типа без @Entity: владельцем может быть только"
                + " управляемый JPA-тип";
        }
        if (StandardDocumentEntity.class.isAssignableFrom(type)
                || declaredKind(type) == EntityKind.DOCUMENT) {
            return "capability объявлена у документа: owner документа не входит в этот контракт";
        }
        if (!isCatalogRoot(type)) {
            return "capability объявлена у типа без CATALOG-семантики: требуется"
                + " StandardCatalogEntity или @EntityMetadata(kind = CATALOG)";
        }
        return null;
    }

    private static boolean isCatalogRoot(Class<?> type) {
        return StandardCatalogEntity.class.isAssignableFrom(type)
            || declaredKind(type) == EntityKind.CATALOG;
    }

    private static EntityKind declaredKind(Class<?> type) {
        EntityMetadata metadata = type.getAnnotation(EntityMetadata.class);
        return metadata == null ? EntityKind.AUTO : metadata.kind();
    }

    private static Field declaredField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Поле может быть объявлено выше: идём по иерархии.
            }
        }
        return null;
    }

    private static String associationProblem(Field field) {
        if (field.isAnnotationPresent(OneToMany.class)
                || field.isAnnotationPresent(ManyToMany.class)
                || field.isAnnotationPresent(ElementCollection.class)
                || field.isAnnotationPresent(OneToOne.class)
                || Collection.class.isAssignableFrom(field.getType())
                || Map.class.isAssignableFrom(field.getType())) {
            return "поле-владелец должно быть одиночной @ManyToOne-связью, а не "
                + "коллекцией/иной ассоциацией";
        }
        if (field.getAnnotation(ManyToOne.class) == null) {
            return "поле-владелец '" + field.getName() + "' не объявлено @ManyToOne";
        }
        if (field.getType().isPrimitive()) {
            return "поле-владелец не может быть примитивом";
        }
        return null;
    }

    /**
     * Обязательность первого owner-профиля проверяется по всем объявлениям сразу:
     * JPA association, явная колонка, Bean Validation и field metadata не должны противоречить
     * контракту.
     */
    private static String requiredProblem(Field field) {
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        if (manyToOne == null || manyToOne.optional()) {
            return "владелец обязателен: требуется @ManyToOne(optional = false)";
        }
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        if (joinColumn == null) {
            return "владелец обязателен: требуется явный @JoinColumn(nullable = false)";
        }
        if (joinColumn.nullable()) {
            return "владелец обязателен: @JoinColumn(nullable = true) противоречит контракту";
        }
        if (!field.isAnnotationPresent(NotNull.class)) {
            return "владелец обязателен: отсутствует @NotNull на ассоциации";
        }
        FieldMetadata metadata = field.getAnnotation(FieldMetadata.class);
        if (metadata != null && metadata.required() == RequiredMode.OPTIONAL) {
            return "FieldMetadata(required = OPTIONAL) противоречит обязательному владельцу;"
                + " допустимы AUTO и REQUIRED";
        }
        return null;
    }

    private static String targetProblem(Class<?> child, Class<?> target) {
        if (target.equals(child)) {
            return "владелец не может ссылаться сам на себя";
        }
        if (!target.isAnnotationPresent(Entity.class)) {
            return "цель " + target.getName() + " не является JPA @Entity";
        }
        if (target.isAnnotationPresent(TableSectionMetadata.class)) {
            return "цель " + target.getName() + " — строка owned-секции, а не catalog root";
        }
        if (target.isInterface() || Modifier.isAbstract(target.getModifiers())) {
            return "цель " + target.getName() + " abstract/interface: нужен конкретный catalog root";
        }
        if (!isCatalogRoot(target)) {
            return "цель " + target.getName() + " не является CATALOG root"
                + " (нужен StandardCatalogEntity или @EntityMetadata(kind = CATALOG))";
        }
        if (!hasLongIdentity(target)) {
            return "цель " + target.getName() + " не имеет Long-идентификатора";
        }
        return null;
    }

    private static boolean hasLongIdentity(Class<?> target) {
        if (!IdentifiableEntity.class.isAssignableFrom(target)) {
            return false;
        }
        try {
            Method getId = target.getMethod("getId");
            return getId.getReturnType() == Long.class || getId.getReturnType() == long.class;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    private static CatalogOwnerResolution error(String code, Class<?> type, String field,
                                                String message) {
        return new CatalogOwnerResolution(null, List.of(new MetadataDiagnostic(
            MetadataDiagnostic.Severity.ERROR, code, type.getName(), field,
            "@CatalogOwner", message)));
    }
}

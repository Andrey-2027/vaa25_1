package org.ipro.numbering;

import org.ipro.metadata.AnnotationClassScanner;
import org.ipro.metadata.ManagedEntityTypes;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.numbering.annotation.Numbered;
import org.ipro.numbering.annotation.NumberingPolicy;
import org.ipro.numbering.annotation.NumberingRole;
import org.springframework.beans.factory.InitializingBean;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.*;

/**
 * Каталог нумеруемых полей приложения — реестр всех {@code @Numbered}-полей в сущностях с
 * effective metadata intent в общем PU-снимке managed-типов. Core supplies the pure applicability
 * policy, including annotationless Standard*; standalone legacy constructors retain annotation discovery (каталог
 * админ-экрана «Нумерация» = то же множество сущностей, что и каталог форм).
 *
 * <p>Это структурный слой (что нумеруется) — в отличие от {@link NumberingRuleService}, который
 * отвечает на вопрос «как сейчас нумеруется» (дефолты аннотации + перекрытия администратора).</p>
 */
public class NumberingMetadataRegistry implements InitializingBean {

    public record NumberedFieldInfo(
            Class<?> entityClass,
            String fieldName,
            Field field,
            Numbered annotation,
            NumberingDefinition definition) {
        public String key() {
            return entityClass.getSimpleName() + "." + fieldName;
        }
    }

    private final String basePackage;
    private final ManagedEntityTypes managedEntityTypes;
    private final org.ipro.metadata.EntityMetadataApplicability applicability;
    private List<NumberedFieldInfo> fields = List.of();

    /**
     * Legacy standalone constructor for callers that have not adopted platform wiring.
     * Production wiring supplies {@link ManagedEntityTypes}, so it performs no classpath scan.
     */
    @Deprecated(forRemoval = false)
    public NumberingMetadataRegistry(String basePackage) {
        this.basePackage = Objects.requireNonNull(basePackage, "basePackage must not be null");
        this.managedEntityTypes = null;
        this.applicability = type -> type.isAnnotationPresent(EntityMetadata.class);
    }

    public NumberingMetadataRegistry(ManagedEntityTypes managedEntityTypes) {
        this(managedEntityTypes, type -> type.isAnnotationPresent(EntityMetadata.class));
    }

    public NumberingMetadataRegistry(ManagedEntityTypes managedEntityTypes,
            org.ipro.metadata.EntityMetadataApplicability applicability) {
        this.basePackage = null;
        this.applicability = Objects.requireNonNull(applicability);
        this.managedEntityTypes = Objects.requireNonNull(
            managedEntityTypes, "managedEntityTypes must not be null");
    }

    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    public void rebuild() {
        List<NumberedFieldInfo> result = new ArrayList<>();
        List<Class<?>> entityClasses = managedEntityTypes == null
            ? AnnotationClassScanner.scanAnnotated(basePackage, EntityMetadata.class)
            : managedEntityTypes.all().stream().filter(applicability::isApplicable).toList();
        for (Class<?> entityClass : entityClasses) {
            Map<NumberingRole, NumberingPolicy> policies = policiesOf(entityClass);
            Set<NumberingRole> resolvedRoles = EnumSet.noneOf(NumberingRole.class);
            for (Field field : fieldsInHierarchy(entityClass)) {
                Numbered annotation = field.getAnnotation(Numbered.class);
                if (annotation != null) {
                    validateNumberedField(entityClass, field, annotation, resolvedRoles);
                    NumberingPolicy policy = policies.get(annotation.role());
                    NumberingDefinition definition = policy == null
                        ? NumberingDefinition.from(annotation)
                        : NumberingDefinition.from(annotation, policy);
                    validateDateField(entityClass, field, definition);
                    field.setAccessible(true);
                    result.add(new NumberedFieldInfo(
                        entityClass, field.getName(), field, annotation, definition));
                }
            }
            Set<NumberingRole> unmatched = EnumSet.noneOf(NumberingRole.class);
            unmatched.addAll(policies.keySet());
            unmatched.removeAll(resolvedRoles);
            if (!unmatched.isEmpty()) {
                throw new IllegalStateException("@NumberingPolicy " + unmatched + " на "
                    + entityClass.getName() + " не соответствует ни одному @Numbered-полю");
            }
        }
        result.sort(Comparator.comparing(NumberedFieldInfo::key));
        this.fields = List.copyOf(result);
    }

    public List<NumberedFieldInfo> all() {
        return fields;
    }

    public List<NumberedFieldInfo> forEntity(Class<?> entityClass) {
        return fields.stream().filter(info -> info.entityClass().equals(entityClass)).toList();
    }

    public Optional<NumberedFieldInfo> find(Class<?> entityClass, String fieldName) {
        return fields.stream()
            .filter(info -> info.entityClass().equals(entityClass) && info.fieldName().equals(fieldName))
            .findFirst();
    }

    private static Map<NumberingRole, NumberingPolicy> policiesOf(Class<?> entityClass) {
        Map<NumberingRole, NumberingPolicy> result = new EnumMap<>(NumberingRole.class);
        for (NumberingPolicy policy : entityClass.getAnnotationsByType(NumberingPolicy.class)) {
            if (policy.role() == NumberingRole.CUSTOM) {
                throw new IllegalStateException("@NumberingPolicy(role=CUSTOM) запрещена на "
                    + entityClass.getName() + "; CUSTOM-поле настраивается через @Numbered");
            }
            if (result.putIfAbsent(policy.role(), policy) != null) {
                throw new IllegalStateException("Дублирующая @NumberingPolicy для роли "
                    + policy.role() + " на " + entityClass.getName());
            }
        }
        return result;
    }

    private static List<Field> fieldsInHierarchy(Class<?> entityClass) {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Class<?> type = entityClass; type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    Field shadowingField = fields.putIfAbsent(field.getName(), field);
                    if (shadowingField != null
                            && (field.getAnnotation(Numbered.class) != null
                                || shadowingField.getAnnotation(Numbered.class) != null)) {
                        throw new IllegalStateException("Нельзя затенять @Numbered-поле "
                            + field.getDeclaringClass().getName() + "." + field.getName()
                            + " полем " + shadowingField.getDeclaringClass().getName()
                            + "." + shadowingField.getName()
                            + "; используйте @NumberingPolicy по семантической роли");
                    }
                }
            }
        }
        return List.copyOf(fields.values());
    }

    private static void validateNumberedField(Class<?> entityClass, Field field, Numbered annotation,
            Set<NumberingRole> resolvedRoles) {
        if (field.getType() != String.class) {
            throw new IllegalStateException("@Numbered поддерживает только String: "
                + entityClass.getName() + "." + field.getName());
        }
        if (annotation.role() != NumberingRole.CUSTOM && !resolvedRoles.add(annotation.role())) {
            throw new IllegalStateException("Роль нумерации " + annotation.role()
                + " назначена нескольким полям " + entityClass.getName());
        }
    }

    private static void validateDateField(Class<?> entityClass, Field numberedField,
            NumberingDefinition definition) {
        if (definition.dateField().isBlank()) {
            if (definition.period() != NumberingPeriod.NEVER) {
                throw new IllegalStateException("Для периодической нумерации "
                    + entityClass.getName() + "." + numberedField.getName()
                    + " требуется dateField");
            }
            return;
        }
        Field date = findField(entityClass, definition.dateField()).orElseThrow(() ->
            new IllegalStateException("dateField=\"" + definition.dateField() + "\" для "
                + entityClass.getName() + "." + numberedField.getName() + " не найден"));
        if (date.getType() != LocalDate.class) {
            throw new IllegalStateException("dateField " + entityClass.getName() + "."
                + date.getName() + " должен иметь тип LocalDate");
        }
    }

    static Optional<Field> findField(Class<?> entityClass, String fieldName) {
        for (Class<?> type = entityClass; type != null && type != Object.class;
                type = type.getSuperclass()) {
            try {
                return Optional.of(type.getDeclaredField(fieldName));
            } catch (NoSuchFieldException ignored) {
                // Продолжаем поиск в mapped superclass.
            }
        }
        return Optional.empty();
    }

}

package org.ipro.register;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable per-persistence-unit catalog of explicitly declared recorder types.
 * It uses the managed entity snapshot; it does not scan the classpath.
 */
public final class RecorderTypeRegistry {
    private final ManagedEntityCatalog managedEntities;
    private final EntityManagerFactory entityManagerFactory;
    private final MetadataResolver metadataResolver;
    private volatile Snapshot snapshot;

    public RecorderTypeRegistry(ManagedEntityCatalog managedEntities,
                                EntityManagerFactory entityManagerFactory,
                                MetadataResolver metadataResolver) {
        this.managedEntities = Objects.requireNonNull(managedEntities, "managedEntities must not be null");
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory,
            "entityManagerFactory must not be null");
        this.metadataResolver = Objects.requireNonNull(metadataResolver, "metadataResolver must not be null");
    }

    /** Validate all declarations before publishing one complete immutable snapshot. */
    public synchronized void initialize() {
        if (snapshot != null) return;

        Map<String, Class<?>> byCode = new LinkedHashMap<>();
        Map<Class<?>, String> byType = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        managedEntities.managedEntityClasses().stream()
            .filter(type -> type.isAnnotationPresent(RecorderType.class))
            .sorted(Comparator.comparing(Class::getName))
            .forEach(type -> {
                String code = type.getAnnotation(RecorderType.class).value();
                List<String> typeErrors = validate(type);

                if (!RecorderRef.isValidTypeCode(code)) {
                    typeErrors.add("код типа должен быть в формате lowercase ASCII kebab-case длиной 1.."
                        + RecorderRef.TYPE_CODE_MAX_LENGTH);
                } else {
                    Class<?> previous = byCode.putIfAbsent(code, type);
                    if (previous != null) {
                        typeErrors.add("код типа '" + code + "' уже объявлен для " + previous.getName());
                    }
                }

                if (typeErrors.isEmpty()) {
                    byType.put(type, code);
                } else {
                    typeErrors.forEach(message -> errors.add(type.getName() + ": " + message));
                }
            });

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Некорректные типы-регистраторы:\n" + String.join("\n", errors));
        }

        Map<String, Class<?>> completeByCode = new LinkedHashMap<>();
        byType.forEach((type, code) -> completeByCode.put(code, type));
        snapshot = new Snapshot(Map.copyOf(completeByCode), Map.copyOf(byType));
    }

    /** Return the managed document class declared for a stable type code. */
    public Optional<Class<?>> findType(String typeCode) {
        Objects.requireNonNull(typeCode, "typeCode must not be null");
        return Optional.ofNullable(requireSnapshot().byCode().get(typeCode));
    }

    public Class<?> requireType(String typeCode) {
        return findType(typeCode).orElseThrow(() -> new IllegalArgumentException(
            "Неизвестный тип регистратора: " + typeCode));
    }

    /** Return the stable code for a registered managed document class. */
    public Optional<String> findCode(Class<?> documentType) {
        Objects.requireNonNull(documentType, "documentType must not be null");
        return Optional.ofNullable(requireSnapshot().byType().get(documentType));
    }

    public String requireCode(Class<?> documentType) {
        return findCode(documentType).orElseThrow(() -> new IllegalArgumentException(
            documentType.getName() + " не объявлен как managed-тип регистратора"));
    }

    /** Build a reference after validating the type; the id is the existing Long id. */
    public RecorderRef referenceFor(Class<?> documentType, Long id) {
        return new RecorderRef(requireCode(documentType), id);
    }

    /** Resolve only the type. Loading and authorizing a document are separate responsibilities. */
    public Class<?> resolveType(RecorderRef reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        return requireType(reference.getTypeCode());
    }

    /** Recorder types in stable code order. */
    public List<Class<?>> types() {
        return requireSnapshot().byCode().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .toList();
    }

    private Snapshot requireSnapshot() {
        initialize();
        return snapshot;
    }

    private List<String> validate(Class<?> type) {
        List<String> errors = new ArrayList<>();
        if (!type.isAnnotationPresent(Entity.class)) {
            errors.add("тип с @RecorderType обязан быть @Entity");
            return errors;
        }
        if (!BaseEntity.class.isAssignableFrom(type)) {
            errors.add("тип регистратора должен наследовать BaseEntity для общей Long version/lock boundary");
        }

        try {
            EntityType<?> entity = entityManagerFactory.getMetamodel().entity(type);
            if (!entity.hasSingleIdAttribute()) {
                errors.add("тип регистратора должен иметь один Long id");
            } else if (entity.getIdType() == null || entity.getIdType().getJavaType() != Long.class) {
                errors.add("id типа регистратора должен иметь тип java.lang.Long");
            }
        } catch (RuntimeException exception) {
            errors.add("тип не входит в metamodel текущего persistence unit или его id не поддерживается");
            return errors;
        }

        try {
            if (metadataResolver.resolve(type).getEntityKind() != EntityKind.DOCUMENT) {
                errors.add("effective EntityKind должен быть DOCUMENT");
            }
        } catch (RuntimeException exception) {
            errors.add("не удалось разрешить EntityMetadata: " + exception.getMessage());
        }
        return errors;
    }

    private record Snapshot(Map<String, Class<?>> byCode, Map<Class<?>, String> byType) {
    }
}

package org.ipro.metadata;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable, persistence-unit-scoped snapshot of the JPA entity classes.
 *
 * <p>The snapshot is built from one {@link EntityManagerFactory} metamodel and can be shared
 * by metadata, numbering, Explorer, and other catalog consumers. It contains JPA entities
 * only; it does not classify application roots or publish entities to user-facing channels.
 * A new persistence unit gets a separate instance.</p>
 */
public final class ManagedEntityTypes {

    private final List<Class<?>> entityClasses;
    private final Set<Class<?>> entityClassSet;
    private final Map<Class<?>, String> entityNamesByClass;

    public ManagedEntityTypes(EntityManagerFactory entityManagerFactory) {
        this(snapshotOf(entityManagerFactory));
    }

    /** Build a snapshot from an already resolved set, primarily for adapters and focused tests. */
    public ManagedEntityTypes(Collection<Class<?>> entityClasses) {
        this(snapshotOf(entityClasses));
    }

    private static EntityTypeSnapshot snapshotOf(Collection<Class<?>> entityClasses) {
        Objects.requireNonNull(entityClasses, "entityClasses must not be null");
        List<EntityTypeEntry> entries = new ArrayList<>();
        for (Class<?> entityClass : entityClasses) {
            Class<?> type = Objects.requireNonNull(entityClass, "entityClass must not be null");
            Entity entity = type.getAnnotation(Entity.class);
            String entityName = entity == null || entity.name().isBlank()
                ? type.getSimpleName() : entity.name();
            entries.add(new EntityTypeEntry(type, entityName));
        }
        return new EntityTypeSnapshot(entries);
    }

    private ManagedEntityTypes(EntityTypeSnapshot snapshot) {
        Map<String, EntityTypeEntry> byName = new TreeMap<>();
        for (EntityTypeEntry entry : snapshot.entries()) {
            EntityTypeEntry previous = byName.putIfAbsent(entry.javaType().getName(), entry);
            if (previous != null && previous.javaType() != entry.javaType()) {
                throw new IllegalArgumentException("Persistence unit contains two entity classes named "
                    + entry.javaType().getName());
            }
            if (previous != null && !previous.entityName().equals(entry.entityName())) {
                throw new IllegalArgumentException("Persistence unit assigns two entity names to "
                    + entry.javaType().getName());
            }
        }
        List<EntityTypeEntry> orderedEntries = List.copyOf(byName.values());
        this.entityClasses = orderedEntries.stream().map(EntityTypeEntry::javaType).toList();
        this.entityClassSet = Collections.unmodifiableSet(new LinkedHashSet<>(this.entityClasses));
        Map<Class<?>, String> entityNames = new java.util.LinkedHashMap<>();
        for (EntityTypeEntry entry : orderedEntries) {
            entityNames.put(entry.javaType(), entry.entityName());
        }
        this.entityNamesByClass = Map.copyOf(entityNames);
    }

    /** All entity classes in deterministic fully-qualified-name order. */
    public List<Class<?>> all() {
        return entityClasses;
    }

    /** Read-only membership view for consumers that need class membership checks. */
    public Set<Class<?>> asSet() {
        return entityClassSet;
    }

    /** Managed entity classes carrying the requested declaration, in snapshot order. */
    public List<Class<?>> annotatedWith(Class<? extends Annotation> annotationType) {
        Objects.requireNonNull(annotationType, "annotationType must not be null");
        return entityClasses.stream()
            .filter(entityClass -> entityClass.isAnnotationPresent(annotationType))
            .toList();
    }

    /** JPA entity name used in JPQL; rejects classes outside this persistence unit. */
    public String entityName(Class<?> entityClass) {
        Class<?> type = Objects.requireNonNull(entityClass, "entityClass must not be null");
        String name = entityNamesByClass.get(type);
        if (name == null) {
            throw new IllegalArgumentException("Class " + type.getName()
                + " is not managed by this persistence unit");
        }
        return name;
    }

    public boolean contains(Class<?> entityClass) {
        return entityClassSet.contains(entityClass);
    }

    private static EntityTypeSnapshot snapshotOf(EntityManagerFactory entityManagerFactory) {
        EntityManagerFactory factory = Objects.requireNonNull(
            entityManagerFactory, "entityManagerFactory must not be null");
        List<EntityTypeEntry> entries = factory.getMetamodel().getEntities().stream()
            .map(entityType -> new EntityTypeEntry(entityType.getJavaType(), entityType.getName()))
            .toList();
        return new EntityTypeSnapshot(entries);
    }

    private record EntityTypeEntry(Class<?> javaType, String entityName) { }

    private record EntityTypeSnapshot(List<EntityTypeEntry> entries) {
        private EntityTypeSnapshot {
            entries = List.copyOf(entries);
        }
    }
}

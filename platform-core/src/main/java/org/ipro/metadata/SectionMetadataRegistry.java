package org.ipro.metadata;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.crud.TableSectionService;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.metadata.annotation.TableSections;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Startup-каталог resolved owned-секций. В production он обнаруживает владельцев и строки
 * в общем PU-снимке, является единственным множеством секций для metadata-driven save engine
 * и проверяет обе стороны root/row декларации.
 */
public final class SectionMetadataRegistry implements InitializingBean {

    private final String basePackage;
    private final ManagedEntityTypes managedEntityTypes;
    private final MetadataResolver metadataResolver;
    private List<TableSectionMetadataInfo> sections = List.of();

    public SectionMetadataRegistry(
            String basePackage,
            MetadataResolver metadataResolver) {
        this.basePackage = basePackage;
        this.managedEntityTypes = null;
        this.metadataResolver = metadataResolver;
    }

    /** Production constructor: discover owners and rows in the shared persistence-unit snapshot. */
    public SectionMetadataRegistry(
            ManagedEntityTypes managedEntityTypes,
            MetadataResolver metadataResolver) {
        this.basePackage = null;
        this.managedEntityTypes = Objects.requireNonNull(
            managedEntityTypes, "managedEntityTypes must not be null");
        this.metadataResolver = Objects.requireNonNull(
            metadataResolver, "metadataResolver must not be null");
    }

    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    public void rebuild() {
        Map<Class<?>, TableSectionMetadataInfo> byRow = new LinkedHashMap<>();
        List<Class<?>> owners = managedEntityTypes == null
            ? AnnotationClassScanner.scanAnnotated(basePackage, TableSections.class)
            : new ArrayList<>(managedEntityTypes.annotatedWith(TableSections.class));
        owners.sort(Comparator.comparing(Class::getName));
        for (Class<?> owner : owners) {
            validateOwner(owner);
            for (TableSectionMetadataInfo descriptor : metadataResolver.resolveTableSections(owner)) {
                validateDescriptor(descriptor);
                TableSectionMetadataInfo previous = byRow.putIfAbsent(
                    descriptor.getRowClass(), descriptor);
                if (previous != null) {
                    throw new IllegalStateException("Section row " + descriptor.getRowClass().getName()
                        + " is owned by both " + previous.getOwnerClass().getName() + " and "
                        + descriptor.getOwnerClass().getName());
                }
            }
        }

        List<Class<?>> declaredRows = managedEntityTypes == null
            ? AnnotationClassScanner.scanAnnotated(basePackage, TableSectionMetadata.class)
            : managedEntityTypes.annotatedWith(TableSectionMetadata.class);
        for (Class<?> row : declaredRows) {
            if (!byRow.containsKey(row)) {
                TableSectionMetadata metadata = row.getAnnotation(TableSectionMetadata.class);
                if (managedEntityTypes != null && !managedEntityTypes.contains(metadata.parentEntity())) {
                    throw new IllegalStateException("Owned-section owner must be managed by the current persistence unit: "
                        + metadata.parentEntity().getName());
                }
                throw new IllegalStateException("Orphan @TableSectionMetadata on " + row.getName()
                    + ": owner " + metadata.parentEntity().getName()
                    + " does not list this row in @TableSections");
            }
        }

        List<TableSectionMetadataInfo> result = new ArrayList<>(byRow.values());
        result.sort(Comparator.comparing(TableSectionMetadataInfo::getKey));
        sections = List.copyOf(result);
    }

    public List<TableSectionMetadataInfo> all() {
        return sections;
    }

    public List<TableSectionMetadataInfo> forOwner(Class<?> ownerClass) {
        return sections.stream()
            .filter(section -> section.getOwnerClass().equals(ownerClass))
            .toList();
    }

    public Optional<TableSectionMetadataInfo> findByRow(Class<?> rowClass) {
        return sections.stream().filter(section -> section.getRowClass().equals(rowClass)).findFirst();
    }

    private static void validateOwner(Class<?> owner) {
        if (!owner.isAnnotationPresent(Entity.class)) {
            throw new IllegalStateException("Owned-section root must be a JPA @Entity: "
                + owner.getName());
        }
        if (!MetadataResolver.isApplicable(owner)) {
            throw new IllegalStateException("Owned-section root must declare @EntityMetadata or extend Standard*: "
                + owner.getName());
        }
        if (!IdentifiableEntity.class.isAssignableFrom(owner)) {
            throw new IllegalStateException("Owned-section root must implement IdentifiableEntity: "
                + owner.getName());
        }
    }

    private void validateDescriptor(TableSectionMetadataInfo descriptor) {
        Class<?> row = descriptor.getRowClass();
        if (managedEntityTypes != null && !managedEntityTypes.contains(row)) {
            throw new IllegalStateException("Owned-section row must be managed by the current persistence unit: "
                + row.getName());
        }
        if (!row.isAnnotationPresent(Entity.class)) {
            throw new IllegalStateException("Owned-section row must be a JPA @Entity: " + row.getName());
        }
        if (!IdentifiableEntity.class.isAssignableFrom(row)) {
            throw new IllegalStateException("Owned-section row must implement IdentifiableEntity: "
                + row.getName());
        }
        if (!descriptor.getParentField().isAnnotationPresent(ManyToOne.class)) {
            throw new IllegalStateException("Parent field " + row.getName() + "."
                + descriptor.getParentFieldName() + " must declare @ManyToOne");
        }
        Class<?> serviceClass = descriptor.getServiceClass();
        if (serviceClass != void.class && !TableSectionService.class.isAssignableFrom(serviceClass)) {
            throw new IllegalStateException("serviceClass " + serviceClass.getName() + " for "
                + descriptor.getKey() + " must implement TableSectionService");
        }
    }
}

package org.ipro.metadata;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.crud.TableSectionService;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.metadata.annotation.TableSections;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Startup-каталог resolved owned-секций. Он является единственным множеством секций для
 * будущего metadata-driven save engine и проверяет обе стороны root/row декларации.
 */
public final class SectionMetadataRegistry implements InitializingBean {

    private final String basePackage;
    private final MetadataResolver metadataResolver;
    private List<TableSectionMetadataInfo> sections = List.of();

    public SectionMetadataRegistry(
            @Value("${platform.subsystem-scan-package:org.ip}") String basePackage,
            MetadataResolver metadataResolver) {
        this.basePackage = basePackage;
        this.metadataResolver = metadataResolver;
    }

    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    public void rebuild() {
        Map<Class<?>, TableSectionMetadataInfo> byRow = new LinkedHashMap<>();
        List<Class<?>> owners = AnnotationClassScanner.scanAnnotated(basePackage, TableSections.class);
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

        List<Class<?>> declaredRows = AnnotationClassScanner.scanAnnotated(
            basePackage, TableSectionMetadata.class);
        for (Class<?> row : declaredRows) {
            if (!byRow.containsKey(row)) {
                TableSectionMetadata metadata = row.getAnnotation(TableSectionMetadata.class);
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
        if (!owner.isAnnotationPresent(EntityMetadata.class)) {
            throw new IllegalStateException("Owned-section root must declare @EntityMetadata: "
                + owner.getName());
        }
        if (!IdentifiableEntity.class.isAssignableFrom(owner)) {
            throw new IllegalStateException("Owned-section root must implement IdentifiableEntity: "
                + owner.getName());
        }
    }

    private static void validateDescriptor(TableSectionMetadataInfo descriptor) {
        Class<?> row = descriptor.getRowClass();
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

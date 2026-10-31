package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Subsystem;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SubsystemRegistry implements InitializingBean {

    private final String basePackage;
    private final ManagedEntityTypes managedEntityTypes;
    private final MetadataResolver metadataResolver;
    private List<SubsystemNode> roots = List.of();

    /**
     * Standalone compatibility path for tools/tests that have not adopted platform wiring.
     * Spring configuration uses the constructor with the PU snapshot and shared resolver.
     */
    @Deprecated(forRemoval = false)
    public SubsystemRegistry(String basePackage, MetadataResolver metadataResolver) {
        this.basePackage = Objects.requireNonNull(basePackage, "basePackage must not be null");
        this.managedEntityTypes = null;
        this.metadataResolver = Objects.requireNonNull(
            metadataResolver, "metadataResolver must not be null");
    }

    public SubsystemRegistry(String basePackage, ManagedEntityTypes managedEntityTypes,
            MetadataResolver metadataResolver) {
        this.basePackage = Objects.requireNonNull(basePackage, "basePackage must not be null");
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
        Map<Class<?>, SubsystemNode> nodesByMarker = scanSubsystemNodes();
        linkParentChild(nodesByMarker);

        List<EntityMetadataInfo> entities = scanEntities();
        for (EntityMetadataInfo entity : entities) {
            Class<?> subsystemMarker = entity.getSubsystem();
            if (subsystemMarker == Subsystem.NoSubsystem.class) {
                continue;
            }
            SubsystemNode node = nodesByMarker.get(subsystemMarker);
            if (node == null) {
                throw new IllegalStateException(
                    "Entity " + entity.getEntityClass().getName() + " references subsystem " +
                    subsystemMarker.getName() + " via @EntityMetadata.subsystem(), but that class " +
                    "is not annotated with @Subsystem (or is outside scanned base package: " +
                    basePackage + ").");
            }
            node.addEntity(entity);
        }

        List<SubsystemNode> topLevel = new ArrayList<>();
        for (SubsystemNode node : nodesByMarker.values()) {
            node.sortOwnEntities();
            if (node.isRoot()) {
                topLevel.add(node);
            }
        }
        topLevel.sort(Comparator.comparingInt(SubsystemNode::getOrder)
            .thenComparing(node -> node.getMarkerClass().getName()));
        topLevel.forEach(SubsystemNode::sortChildren);

        this.roots = List.copyOf(topLevel);
    }

    public List<SubsystemNode> getRoots() {
        return roots;
    }

    public Optional<SubsystemNode> findByMarker(Class<?> markerClass) {
        return findRecursive(roots, markerClass);
    }

    private Optional<SubsystemNode> findRecursive(List<SubsystemNode> nodes, Class<?> markerClass) {
        for (SubsystemNode node : nodes) {
            if (node.getMarkerClass() == markerClass) return Optional.of(node);
            Optional<SubsystemNode> inChildren = findRecursive(node.getChildren(), markerClass);
            if (inChildren.isPresent()) return inChildren;
        }
        return Optional.empty();
    }

    private Map<Class<?>, SubsystemNode> scanSubsystemNodes() {
        Map<Class<?>, SubsystemNode> result = new LinkedHashMap<>();
        List<Class<?>> markerClasses = AnnotationClassScanner.scanAnnotated(basePackage, Subsystem.class);
        markerClasses.sort(Comparator.comparing(markerClass -> markerClass.getName()));
        for (Class<?> markerClass : markerClasses) {
            Subsystem annotation = markerClass.getAnnotation(Subsystem.class);
            result.put(markerClass, new SubsystemNode(markerClass, annotation));
        }
        return result;
    }

    private void linkParentChild(Map<Class<?>, SubsystemNode> nodesByMarker) {
        for (SubsystemNode node : nodesByMarker.values()) {
            Class<?> parentMarker = node.getMarkerClass().getAnnotation(Subsystem.class).parent();
            if (parentMarker == Subsystem.NoSubsystem.class) {
                continue;
            }
            SubsystemNode parent = nodesByMarker.get(parentMarker);
            if (parent == null) {
                throw new IllegalStateException(
                    "Subsystem " + node.getMarkerClass().getName() + " declares parent " +
                    parentMarker.getName() + " which is not annotated with @Subsystem " +
                    "(or is outside scanned base package: " + basePackage + ").");
            }
            node.setParent(parent);
            parent.addChild(node);
        }
    }

    private List<EntityMetadataInfo> scanEntities() {
        List<EntityMetadataInfo> result = new ArrayList<>();
        List<Class<?>> entityClasses = managedEntityTypes == null
            ? AnnotationClassScanner.scanAnnotated(basePackage, EntityMetadata.class)
            : managedEntityTypes.all().stream().filter(MetadataResolver::isApplicable).toList();
        for (Class<?> entityClass : entityClasses) {
            result.add(metadataResolver.resolve(entityClass));
        }
        return result;
    }
}

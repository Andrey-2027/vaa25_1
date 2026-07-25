package org.ip.metadata;

import org.ip.metadata.annotation.EntityMetadata;
import org.ip.metadata.annotation.Subsystem;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SubsystemRegistry implements InitializingBean {

    private final String basePackage;
    private List<SubsystemNode> roots = List.of();

    public SubsystemRegistry(
            @Value("${platform.subsystem-scan-package:org.ip}") String basePackage) {
        this.basePackage = basePackage;
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
            Class<?> subsystemMarker = entity.getAnnotation().subsystem();
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
        topLevel.sort(Comparator.comparingInt(SubsystemNode::getOrder));
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
        for (Class<?> markerClass : scanAnnotated(Subsystem.class)) {
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
        MetadataResolver resolver = new MetadataResolver();
        for (Class<?> entityClass : scanAnnotated(EntityMetadata.class)) {
            result.add(resolver.resolve(entityClass));
        }
        return result;
    }

    private List<Class<?>> scanAnnotated(Class<? extends java.lang.annotation.Annotation> annotationClass) {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false) {
                @Override
                protected boolean isCandidateComponent(
                        org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                    return true;
                }
            };
        scanner.addIncludeFilter(new AnnotationTypeFilter(annotationClass));

        List<Class<?>> result = new ArrayList<>();
        scanner.findCandidateComponents(basePackage).forEach(candidate -> {
            try {
                result.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                    "Failed to load class found during @" + annotationClass.getSimpleName() +
                    " classpath scan: " + candidate.getBeanClassName(), e);
            }
        });
        return result;
    }
}

package org.ip.metadata;

import org.ip.metadata.annotation.Subsystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SubsystemNode {

    private final Class<?> markerClass;
    private final Subsystem annotation;
    private SubsystemNode parent;
    private final List<SubsystemNode> children = new ArrayList<>();
    private final List<EntityMetadataInfo> entities = new ArrayList<>();

    public SubsystemNode(Class<?> markerClass, Subsystem annotation) {
        this.markerClass = markerClass;
        this.annotation = annotation;
    }

    public Class<?> getMarkerClass() {
        return markerClass;
    }

    public String getTitle() {
        return annotation.title();
    }

    public String getIcon() {
        return annotation.icon();
    }

    public int getOrder() {
        return annotation.order();
    }

    public SubsystemNode getParent() {
        return parent;
    }

    void setParent(SubsystemNode parent) {
        this.parent = parent;
    }

    public boolean isRoot() {
        return parent == null;
    }

    public List<SubsystemNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    void addChild(SubsystemNode child) {
        children.add(child);
    }

    void sortChildren() {
        children.sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        children.forEach(SubsystemNode::sortChildren);
    }

    public List<EntityMetadataInfo> getOwnEntities() {
        return Collections.unmodifiableList(entities);
    }

    void addEntity(EntityMetadataInfo entity) {
        entities.add(entity);
    }

    void sortOwnEntities() {
        entities.sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
    }

    public List<EntityGroup> getEntityGroupsRecursive() {
        List<EntityGroup> result = new ArrayList<>();
        if (!entities.isEmpty()) {
            result.add(new EntityGroup(this, getOwnEntities()));
        }
        for (SubsystemNode child : children) {
            result.addAll(child.getEntityGroupsRecursive());
        }
        return result;
    }

    @Override
    public String toString() {
        return "SubsystemNode{" + markerClass.getSimpleName() + ", title='" + getTitle() + "'}";
    }

    public record EntityGroup(SubsystemNode node, List<EntityMetadataInfo> entities) {
    }
}

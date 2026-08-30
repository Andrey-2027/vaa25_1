package org.ipro.reportstudio.query.constructor;

import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Узел дерева каталога конструктора. Намеренно обычный класс, а не record:
 * TreeData/TreeGrid идентифицирует элементы через equals, и поля с одинаковыми
 * caption/name у разных сущностей должны оставаться различимыми (identity).
 */
final class ConstructorTreeNode {

    enum Kind { ENTITY, PROPERTY, ASSOCIATION }

    private final Kind kind;
    /** Отображаемое имя (в стиле 1С — техническое имя сущности/поля/связи). */
    private final String caption;
    /** Сущность узла: для ENTITY — сама, для PROPERTY/ASSOCIATION — сущность-владелец. */
    private final QueryBuilderMetadataCatalog.Entity entity;
    private final QueryBuilderMetadataCatalog.Field field;
    private final QueryBuilderMetadataCatalog.Association association;
    private final List<ConstructorTreeNode> children;
    /** Alias таблицы — только для узлов-таблиц центрального дерева (корень/JOIN). */
    private final String tableAlias;
    private ConstructorTreeNode parent;

    ConstructorTreeNode(Kind kind, String caption, QueryBuilderMetadataCatalog.Entity entity,
                        QueryBuilderMetadataCatalog.Field field,
                        QueryBuilderMetadataCatalog.Association association,
                        List<ConstructorTreeNode> children) {
        this(kind, caption, entity, field, association, children, null);
    }

    ConstructorTreeNode(Kind kind, String caption, QueryBuilderMetadataCatalog.Entity entity,
                        QueryBuilderMetadataCatalog.Field field,
                        QueryBuilderMetadataCatalog.Association association,
                        List<ConstructorTreeNode> children, String tableAlias) {
        this.kind = kind;
        this.caption = caption;
        this.entity = entity;
        this.field = field;
        this.association = association;
        this.children = List.copyOf(children == null ? List.of() : children);
        this.tableAlias = tableAlias;
        for (ConstructorTreeNode child : this.children) {
            child.parent = this;
        }
    }

    Kind kind() { return kind; }
    String caption() { return caption; }
    QueryBuilderMetadataCatalog.Entity entity() { return entity; }
    QueryBuilderMetadataCatalog.Field field() { return field; }
    QueryBuilderMetadataCatalog.Association association() { return association; }
    List<ConstructorTreeNode> children() { return children; }
    ConstructorTreeNode parent() { return parent; }
    String tableAlias() { return tableAlias; }

    /** Ближайший узел-сущность вверх по цепочке (владелец поля/связи). */
    ConstructorTreeNode ownerEntity() {
        ConstructorTreeNode current = this;
        while (current != null && current.kind() != Kind.ENTITY) {
            current = current.parent();
        }
        return current;
    }

    /** Цепочка ассоциаций от узла-владельца до этого узла (в порядке «снаружи внутрь»). */
    List<QueryBuilderMetadataCatalog.Association> chainToOwner() {
        List<QueryBuilderMetadataCatalog.Association> chain = new ArrayList<>();
        ConstructorTreeNode current = this;
        while (current != null && current.kind() != Kind.ENTITY) {
            if (current.kind() == Kind.ASSOCIATION) {
                chain.add(0, current.association());
            }
            current = current.parent();
        }
        return chain;
    }

    /** Все конечные поля поддерева узла-сущности (для переноса «>>»). */
    List<ConstructorTreeNode> directFields() {
        List<ConstructorTreeNode> fields = new ArrayList<>();
        for (ConstructorTreeNode child : children) {
            if (child.kind() == Kind.PROPERTY) fields.add(child);
        }
        return fields;
    }
}

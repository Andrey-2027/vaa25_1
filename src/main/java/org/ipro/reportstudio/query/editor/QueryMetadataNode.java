package org.ipro.reportstudio.query.editor;

import java.util.List;

/**
 * Узел безопасного каталога метаданных, отображаемого редактором JPQL.
 *
 * <p>Это намеренно обычный класс, а не {@code record}: TreeData/TreeGrid
 * идентифицирует добавленные элементы через {@link Object#equals(Object)}.
 * Поля с одинаковыми caption/token/type (например, {@code code:String})
 * легитимно встречаются у разных сущностей, поэтому им нужна identity-based
 * семантика, а не структурное равенство record.</p>
 */
public final class QueryMetadataNode {

    private final Kind kind;
    private final String caption;
    private final String token;
    private final String javaType;
    private final boolean selectable;
    private final List<QueryMetadataNode> children;

    public QueryMetadataNode(Kind kind, String caption, String token, String javaType,
                             boolean selectable, List<QueryMetadataNode> children) {
        this.kind = kind;
        this.caption = caption;
        this.token = token;
        this.javaType = javaType;
        this.selectable = selectable;
        this.children = children == null ? List.of() : List.copyOf(children);
    }

    public Kind kind() {
        return kind;
    }

    public String caption() {
        return caption;
    }

    public String token() {
        return token;
    }

    public String javaType() {
        return javaType;
    }

    public boolean selectable() {
        return selectable;
    }

    public List<QueryMetadataNode> children() {
        return children;
    }

    public enum Kind {
        ENTITY,
        PROPERTY,
        ASSOCIATION
    }
}

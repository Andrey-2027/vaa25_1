package org.ipro.rest.catalog;

import jakarta.persistence.metamodel.Attribute;

import java.util.List;
import java.util.Objects;

/** Immutable persistent path resolved from one persistence-unit metamodel and Hibernate mapping. */
public record ResolvedRestPath(String source, List<Segment> segments, Class<?> terminalType,
                               boolean nullable) {

    public ResolvedRestPath {
        Objects.requireNonNull(source, "source must not be null");
        segments = List.copyOf(segments);
        Objects.requireNonNull(terminalType, "terminalType must not be null");
    }

    public List<String> associationPaths() {
        StringBuilder path = new StringBuilder();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Segment segment : segments) {
            if (path.length() > 0) path.append('.');
            path.append(segment.name());
            if (segment.association()) result.add(path.toString());
        }
        return List.copyOf(result);
    }

    /** One JPA metamodel segment, including its effective nullable/association classification. */
    public record Segment(String name, Class<?> declaringType, Class<?> javaType,
                          Attribute.PersistentAttributeType persistentType,
                          boolean nullable, boolean association) {
        public Segment {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(declaringType, "declaringType must not be null");
            Objects.requireNonNull(javaType, "javaType must not be null");
            Objects.requireNonNull(persistentType, "persistentType must not be null");
        }
    }
}

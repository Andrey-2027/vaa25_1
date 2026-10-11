package org.ipro.ig4fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSectionMetadata;

/** Isolated persistence unit for the generic identity aggregate regression. */
public final class AggregateFixtures {
    private AggregateFixtures() { }

    @Entity(name = "Ig4Root")
    @EntityMetadata(listFormTitle = "IG4 root")
    public static class Root extends BaseEntity {
        public String title;
    }

    @Entity(name = "Ig4Row")
    @TableSectionMetadata(parentEntity = Root.class, parentField = "parent", lineNumberField = "line")
    public static class Row extends BaseEntity {
        @ManyToOne(optional = false) public Root parent;
        public Integer line;
        public String title;
    }

    @Entity(name = "Ig4Movement")
    public static class Movement extends BaseEntity {
        public String title;
    }
}

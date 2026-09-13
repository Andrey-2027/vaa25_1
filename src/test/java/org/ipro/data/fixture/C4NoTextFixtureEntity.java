package org.ipro.data.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.GridColumn;

/** Test-only standard root for the blank-search case where metadata has no text columns. */
@Entity(name = "C4NoTextFixtureEntity")
@EntityMetadata(listFormTitle = "C4 no-text fixture")
public class C4NoTextFixtureEntity implements IdentifiableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @FieldMetadata(label = "Активна", type = FieldType.BOOLEAN,
        grid = @GridColumn(order = 1, width = "100px"))
    private boolean active;

    public C4NoTextFixtureEntity() {
    }

    public C4NoTextFixtureEntity(boolean active) {
        this.active = active;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    public boolean isActive() {
        return active;
    }
}

package org.ipro.data.ownerfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;

/** Catalog-root fixture — владелец подчинённых договоров. */
@Entity(name = "OwnerContractor")
@EntityMetadata(kind = EntityKind.CATALOG, listFormTitle = "Контрагенты")
public class OwnerContractor implements IdentifiableEntity<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @FieldMetadata(label = "Наименование", order = 10)
    private String name;

    public OwnerContractor() {
    }

    public OwnerContractor(String name) {
        this.name = name;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}

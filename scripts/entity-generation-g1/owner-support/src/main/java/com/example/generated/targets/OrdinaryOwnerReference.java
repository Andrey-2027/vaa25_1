package com.example.generated.targets;

import jakarta.persistence.*;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.*;

/** Existing control root: a reference named owner, deliberately without CatalogOwner capability. */
@Entity @Table(name="ordinary_owner_reference")
@EntityMetadata(listColumns={"code","name","owner"},selectColumns={"code","name","owner"})
public class OrdinaryOwnerReference extends StandardCatalogEntity {
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="owner_id") @FieldMetadata(label="Обычная ссылка")
    private ReferenceTarget owner;
    public ReferenceTarget getOwner(){return owner;}
    public void setOwner(ReferenceTarget value){owner=value;}
}

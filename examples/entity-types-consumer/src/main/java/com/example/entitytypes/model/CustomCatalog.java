package com.example.entitytypes.model;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.*;

@Entity @EntityMetadata(kind = EntityKind.CATALOG, listFormTitle = "Внешние обозначения")
public class CustomCatalog extends BaseEntity {
    @NotBlank @Column(length = 12, unique = true) @FieldMetadata(label = "Внешний ключ") private String externalKey;
    @FieldMetadata(label = "Подпись") private String caption;
    public String getExternalKey() { return externalKey; }
    public void setExternalKey(String externalKey) { this.externalKey = externalKey; }
    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }
}

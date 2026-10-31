package com.example.entitytypes.model;
import jakarta.persistence.Entity;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.FieldMetadata;

@Entity
public class ExtendedProduct extends StandardCatalogEntity {
    @FieldMetadata(label = "Описание", order = 30)
    private String description;
    private String storageOnly;
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStorageOnly() { return storageOnly; }
    public void setStorageOnly(String storageOnly) { this.storageOnly = storageOnly; }
}

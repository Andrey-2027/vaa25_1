package com.example.materialrequests.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import org.ipro.crud.BaseEntity;
import org.ipro.fetch.instance.InstanceName;
import org.ipro.metadata.annotation.*;
import org.ipro.metadata.HasDisplayName;

@Entity
@Table(name = "consumer_material", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
@EntityMetadata(kind = EntityKind.CATALOG, listFormTitle = "Материалы", itemFormTitle = "Материал", selectionFormTitle = "Выбор материала", subsystem = MaterialsSubsystem.class)
@InstanceName(value = {"code", "name"}, separator = " — ")
public class Material extends BaseEntity implements HasDisplayName {
    @NotBlank @Column(nullable = false, length = 40)
    @FieldMetadata(label = "Код", order = 10)
    private String code;

    @NotBlank @Column(nullable = false)
    @FieldMetadata(label = "Наименование", order = 20)
    private String name;

    public Material() {}
    public Material(String code, String name) { this.code = code; this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    @Override public String getDisplayName() { return code + " — " + name; }
}

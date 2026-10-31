package com.example.generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;

@Entity
@Table(name = "control_catalog")
@EntityMetadata(
    listFormTitle = "Контрольные справочники",
    itemFormTitle = "Контрольный справочник",
    selectionFormTitle = "Выбор контрольного справочника",
    icon = "FILE",
    listColumns = {"code", "name", "note"},
    selectColumns = {"code", "name", "note"}
)
public class ControlCatalog extends StandardCatalogEntity {
    @Size(max = 30)
    @Column(name = "note", length = 30)
    @FieldMetadata(label = "Заметка", order = 30, grid = @GridColumn(visible = false))
    private String note;

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}

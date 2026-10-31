package com.example.generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;

@Entity
@Table(name = "control_document")
@EntityMetadata(
    listFormTitle = "Контрольные документы",
    itemFormTitle = "Контрольный документ",
    selectionFormTitle = "Выбор контрольного документа",
    icon = "FILE",
    listColumns = {"number", "date", "note"},
    selectColumns = {"number", "date", "note"}
)
public class ControlDocument extends StandardDocumentEntity {
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

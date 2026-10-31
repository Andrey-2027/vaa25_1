package com.example.generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.TableSectionMetadata;

@Entity
@Table(name = "multi_file_row")
@TableSectionMetadata(
    parentEntity = MultiFileRoot.class,
    parentField = "owner",
    title = "Позиции",
    rowFormTitle = "Позиция комплекта",
    order = 10,
    lineNumberField = "lineNumber"
)
public class MultiFileRow extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(nullable = false)
    private MultiFileRoot owner;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Size(max = 30)
    @Column(name = "note", length = 30)
    @FieldMetadata(label = "Заметка", order = 10)
    private String note;

    public MultiFileRoot getOwner() {
        return owner;
    }

    public void setOwner(MultiFileRoot owner) {
        this.owner = owner;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}

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
@Table(name = "multi_file_note")
@TableSectionMetadata(
    parentEntity = MultiFileRoot.class,
    parentField = "owner",
    title = "Примечания",
    rowFormTitle = "Примечание комплекта",
    order = 20,
    lineNumberField = "lineNumber"
)
public class MultiFileNote extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(nullable = false)
    private MultiFileRoot owner;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Size(max = 40)
    @Column(name = "text", length = 40)
    @FieldMetadata(label = "Текст", order = 10)
    private String text;

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

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}

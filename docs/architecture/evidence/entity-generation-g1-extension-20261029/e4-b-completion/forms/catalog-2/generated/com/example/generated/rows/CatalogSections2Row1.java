package com.example.generated.rows;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.SectionPersistenceMode;
import org.ipro.metadata.annotation.SectionRlsPolicy;
import org.ipro.metadata.annotation.TableSectionMetadata;

@Entity
@Table(name = "catalog_2_row_1")
@TableSectionMetadata(parentEntity = com.example.generated.CatalogSections2.class, parentField = "catalog", title = "Строки 1", rowFormTitle = "Строки 1", order = 10, lineNumberField = "lineNumber", persistence = SectionPersistenceMode.MUTABLE_REPLACE_ALL, rlsPolicy = SectionRlsPolicy.INHERIT_ROOT)
public class CatalogSections2Row1 extends BaseEntity {
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "catalog_id", nullable = false)
    private com.example.generated.CatalogSections2 catalog;

    @Column(name = "line_number")
    private Integer lineNumber;

    @NotNull
    @Size(max = 60)
    @Column(name = "note", nullable = false, length = 60)
    @FieldMetadata(label = "Текст", order = 10, required = RequiredMode.REQUIRED, grid = @GridColumn(visible = true, order = 10))
    private String note;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "target_id")
    @FieldMetadata(label = "Ссылка", order = 20, required = RequiredMode.OPTIONAL, grid = @GridColumn(visible = true, order = 20))
    private com.example.generated.targets.ReferenceTarget target;

    public com.example.generated.CatalogSections2 getCatalog() {
        return catalog;
    }

    public void setCatalog(com.example.generated.CatalogSections2 catalog) {
        this.catalog = catalog;
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

    public com.example.generated.targets.ReferenceTarget getTarget() {
        return target;
    }

    public void setTarget(com.example.generated.targets.ReferenceTarget target) {
        this.target = target;
    }
}

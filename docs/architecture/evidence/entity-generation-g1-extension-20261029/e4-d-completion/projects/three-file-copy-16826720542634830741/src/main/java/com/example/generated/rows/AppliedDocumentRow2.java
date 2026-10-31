package com.example.generated.rows;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.SectionPersistenceMode;
import org.ipro.metadata.annotation.SectionRlsPolicy;
import org.ipro.metadata.annotation.TableSectionMetadata;

@Entity
@Table(name = "applied_document_row2")
@TableSectionMetadata(parentEntity = com.example.generated.AppliedDocument.class, parentField = "document", title = "Rows 2", rowFormTitle = "Rows 2", order = 20, lineNumberField = "lineNumber", persistence = SectionPersistenceMode.MUTABLE_REPLACE_ALL, rlsPolicy = SectionRlsPolicy.INHERIT_ROOT)
public class AppliedDocumentRow2 extends BaseEntity {
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private com.example.generated.AppliedDocument document;

    @Column(name = "line_number")
    private Integer lineNumber;

    public com.example.generated.AppliedDocument getDocument() {
        return document;
    }

    public void setDocument(com.example.generated.AppliedDocument document) {
        this.document = document;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
    }
}

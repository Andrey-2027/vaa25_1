package org.ipro.registerfixtures;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.registerfixtures.RecorderPersistenceFixtures.RecorderFixtureDocumentA;

@Entity
@Table(name = "recorder_fixture_document_a_section")
@TableSectionMetadata(parentEntity = RecorderFixtureDocumentA.class,
    parentField = "document", lineNumberField = "lineNumber")
public class RecorderFixtureDocumentASection extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private RecorderFixtureDocumentA document;

    @Column(name = "line_no", nullable = false)
    private Integer lineNumber;

    @NotNull
    @Column(name = "section_value", nullable = false, length = 64)
    private String value;

    public RecorderFixtureDocumentASection() { }

    public RecorderFixtureDocumentASection(String value) {
        this.value = value;
    }

    public RecorderFixtureDocumentA getDocument() { return document; }
    public void setDocument(RecorderFixtureDocumentA document) { this.document = document; }
    public Integer getLineNumber() { return lineNumber; }
    public void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}

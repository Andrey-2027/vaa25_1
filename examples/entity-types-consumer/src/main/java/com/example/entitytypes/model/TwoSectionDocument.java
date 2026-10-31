package com.example.entitytypes.model;
import jakarta.persistence.Entity;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.TableSections;
import java.time.LocalDate;

@Entity @TableSections({Position.class, CommentRow.class})
public class TwoSectionDocument extends StandardDocumentEntity {
    public TwoSectionDocument() { setDate(LocalDate.now()); }
}

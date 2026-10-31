package com.example.entitytypes.model;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.*;

@Entity
@TableSectionMetadata(parentEntity = TwoSectionDocument.class, parentField = "owner", title = "Комментарии", order = 20)
public class CommentRow extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(nullable = false) private TwoSectionDocument owner;
    @NotBlank @FieldMetadata(label = "Текст") private String text;
    public TwoSectionDocument getOwner() { return owner; }
    public void setOwner(TwoSectionDocument owner) { this.owner = owner; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}

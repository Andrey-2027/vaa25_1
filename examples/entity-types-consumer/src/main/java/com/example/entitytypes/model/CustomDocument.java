package com.example.entitytypes.model;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.*;
import java.time.LocalDateTime;

@Entity @EntityMetadata(kind = EntityKind.DOCUMENT, listFormTitle = "События с точным временем")
public class CustomDocument extends BaseEntity {
    @NotNull @FieldMetadata(label = "Время события") private LocalDateTime occurredAt = LocalDateTime.now();
    @FieldMetadata(label = "Внешний номер") private String externalNumber;
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public String getExternalNumber() { return externalNumber; }
    public void setExternalNumber(String externalNumber) { this.externalNumber = externalNumber; }
}

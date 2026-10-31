package com.example.entitytypes.model;
import jakarta.persistence.Entity;
import org.ipro.crud.StandardDocumentEntity;
import java.time.LocalDate;

@Entity
public class Note extends StandardDocumentEntity {
    public Note() { setDate(LocalDate.now()); }
}

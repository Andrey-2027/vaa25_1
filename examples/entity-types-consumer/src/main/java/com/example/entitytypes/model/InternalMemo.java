package com.example.entitytypes.model;
import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;

@Entity
public class InternalMemo extends BaseEntity {
    private String payload;
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}

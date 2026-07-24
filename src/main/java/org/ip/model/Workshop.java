package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

@Entity
@Table(name = "workshop")
@Audited
public class Workshop extends BaseEntity implements HasDisplayName {

    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, unique = true)
    private String code;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false)
    private String name;

    public Workshop() {
    }

    public Workshop(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return code + " - " + name;
    }

    @Override
    public String getDisplayName() {
        return code;
    }
}

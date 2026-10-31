package com.example.entitytypes.model;

import jakarta.persistence.*;
import org.ipro.crud.*;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.EntityMetadata;
import java.util.Locale;

/** ET8 minimal full profile: one domain key, one declaration, no capability bean. */
@Entity
@Table(name="interned_label",uniqueConstraints=@UniqueConstraint(name="uk_interned_label",columnNames="canonical"))
@EntityMetadata
@InternedContract(creation="NaturalKeyCreateSupport with canonical CREATE",genericCreate=true,
    keys=@InternedContract.Key(constraint="uk_interned_label",properties="canonical",
        normalization="strip then uppercase Locale.ROOT; blank input rejected by InternedLabel.of"))
public class InternedLabel extends BaseEntity implements InternedEntity, HasDisplayName {
    @Column(name="canonical",nullable=false,length=100)
    private String canonical;
    protected InternedLabel() { }
    public static InternedLabel of(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Label must not be blank");
        var label=new InternedLabel();
        label.canonical=input.strip().toUpperCase(Locale.ROOT);
        if (label.canonical.length()>100) throw new IllegalArgumentException("Label is too long");
        return label;
    }
    public String getCanonical() { return canonical; }
    public String interningKey() { return canonical; }
    public String getDisplayName() { return canonical; }
}

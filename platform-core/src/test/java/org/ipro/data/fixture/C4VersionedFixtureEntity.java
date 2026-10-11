package org.ipro.data.fixture;

import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.EntityMetadata;

/** Versioned recorder-root fixture for the canonical delete coordination test. */
@Entity(name = "C4VersionedFixtureEntity")
@EntityMetadata(listFormTitle = "C4 versioned fixture")
public class C4VersionedFixtureEntity extends BaseEntity {

    private String code;

    public C4VersionedFixtureEntity() {
    }

    public C4VersionedFixtureEntity(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}

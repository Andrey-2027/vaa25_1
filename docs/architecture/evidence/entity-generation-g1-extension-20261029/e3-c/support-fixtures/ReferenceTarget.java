package com.example.generated.targets;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.EntityMetadata;

/** Existing target support fixture, packaged separately from generated business sources. */
@Entity @Table(name = "existing_reference_target")
@EntityMetadata(listColumns = {"code", "name"}, selectColumns = {"code", "name"})
public class ReferenceTarget extends StandardCatalogEntity { }

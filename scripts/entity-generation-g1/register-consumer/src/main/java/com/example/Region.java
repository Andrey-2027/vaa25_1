package com.example;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardCatalogEntity;

/** Consumer-owned numeric reference target for the additional NONE RLS route. */
@Entity
@Table(name = "register_region")
public class Region extends StandardCatalogEntity {
}

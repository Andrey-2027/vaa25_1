package com.example.registerconsumer.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardCatalogEntity;

/**
 * Consumer-owned catalog and the reference target of the DAY register. This is the minimal
 * declaration on purpose: the reference contract needs a standard managed root with a Long id,
 * not a copy of platform infrastructure.
 */
@Entity
@Table(name = "register_item")
public class Item extends StandardCatalogEntity {
}

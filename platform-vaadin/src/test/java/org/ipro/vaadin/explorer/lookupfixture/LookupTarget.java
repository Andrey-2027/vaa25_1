package org.ipro.vaadin.explorer.lookupfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.ipro.metadata.annotation.EntityMetadata;

/** Цель выбора для проб аспекта связи: класс, на который смотрят ссылки полей пробы. */
@Entity
@Table(name = "lookup_probe_target")
@EntityMetadata(listFormTitle = "Пробная цель")
public class LookupTarget {

    @Id
    private Long id;
}

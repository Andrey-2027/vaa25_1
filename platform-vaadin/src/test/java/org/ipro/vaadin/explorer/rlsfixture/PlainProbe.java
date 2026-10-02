package org.ipro.vaadin.explorer.rlsfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.ipro.metadata.annotation.EntityMetadata;

/**
 * Проба типа без объявленных измерений: в RLS он не участвует, и это факт, а не отсутствие данных.
 * Ни строки аспекта, ни диагностики у него быть не должно — «измерений нет» и «регистрация
 * сломана» различимы.
 */
@Entity
@Table(name = "rls_probe_plain")
@EntityMetadata(listFormTitle = "Пробный тип без RLS")
public class PlainProbe {

    @Id
    private Long id;
}

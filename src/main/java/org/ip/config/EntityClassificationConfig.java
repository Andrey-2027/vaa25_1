package org.ip.config;

import org.ip.model.SklNomOpa;
import org.ip.model.SklNomOpaValue;
import org.ipro.data.EntityExposure;
import org.ipro.data.EntityExposureOverride;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * Прикладные классификации persistence types, которые нельзя вывести из аннотаций
 * (C4, ADR-0007 §2 и находка {@code c4-inventory.md} §2.3).
 *
 * <p>{@link SklNomOpaValue} — структурная строка агрегата {@link SklNomOpa}: у неё нет
 * {@code @TableSectionMetadata}, потому что её состав ведёт typed {@code SklNomOpaService},
 * а не generic section machinery. Без явной классификации тип попал бы в
 * {@code INTERNAL_STORE}, и JPA-metamodel membership когда-нибудь выдал бы ему автономный
 * data handle. Явный override фиксирует {@code OWNED_ROW}: автономного list/detail/lookup
 * у строки нет.</p>
 *
 * <p>ET8: ограничения записи AttributeValue и SklNomOpa теперь выводятся из их
 * {@link org.ipro.crud.InternedContract}. Отдельные capability-бины для интернирования
 * не нужны; различие CREATE и owner-managed создания остаётся в предметном объявлении.</p>
 */
@Configuration(proxyBeanMethods = false)
public class EntityClassificationConfig {

    @Bean
    public EntityExposureOverride sklNomOpaValueIsAnOwnedRow() {
        return new EntityExposureOverride(SklNomOpaValue.class, EntityExposure.OWNED_ROW,
            SklNomOpa.class,
            "структурная строка агрегата SklNomOpa, состав ведёт typed use case");
    }

}

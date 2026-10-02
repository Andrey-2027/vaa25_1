package org.ipro.vaadin.explorer.lookupfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.Lookup;

/**
 * Проба аспекта связи (E3.2.0 шаг 4): объявленная цель, цель из типа ассоциации, вариант формы
 * выбора и поле без цели.
 *
 * <p><b>Почему на настоящих JPA-аннотациях.</b> Происхождение цели у владельца метаданных —
 * следствие маппинга ({@code @ManyToOne}) и объявления {@code @Lookup.entity}: проба без
 * ассоциации не воспроизвела бы ветку {@code JPA_MAPPING} вообще. Пробы проходят те же
 * контракты {@code FieldMetadataInfo}, что боевые типы, включая диагностику избыточного
 * объявления.</p>
 *
 * <p><b>Порядок объявления намеренно не совпадает с алфавитным</b> ({@code order} задан в обратном
 * порядке): так видно, что строки сортирует потребитель, а не владелец метаданных.</p>
 */
@Entity
@Table(name = "lookup_probe")
@EntityMetadata(listFormTitle = "Проба связи")
public class LookupProbe {

    @Id
    private Long id;

    /** Объявленная цель, совпавшая с типом ссылки: факт есть, новой информации объявление не несёт. */
    @ManyToOne(fetch = FetchType.LAZY)
    @FieldMetadata(label = "Объявленная цель", order = 3,
        lookup = @Lookup(entity = LookupTarget.class))
    private LookupTarget declared;

    /** Цель выведена из типа ассоциации: {@code @Lookup} объявляет только сценарий выбора. */
    @ManyToOne(fetch = FetchType.LAZY)
    @FieldMetadata(label = "Выведенная цель", order = 2,
        lookup = @Lookup(fetch = {"displayName"}))
    private LookupTarget inferred;

    /** Вариант формы выбора — часть факта цели, а не текст. */
    @ManyToOne(fetch = FetchType.LAZY)
    @FieldMetadata(label = "Цель с вариантом", order = 1,
        lookup = @Lookup(entity = LookupTarget.class, variant = "probe"))
    private LookupTarget variantTarget;

    /** Не ссылка и без цели: строки аспекта связи у поля быть не должно. */
    @FieldMetadata(label = "Просто текст", order = 0)
    private String plainText;
}

package rlsparity.consistent;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.rls.RlsDimension;

/**
 * Фикстура положительного случая: custom FILTERABLE-измерение объявляет read-предикат той
 * же константой, которая стоит в {@code @Filter}. Реестр должен собраться без отказа —
 * то есть проверка custom-политики не «всегда падает», а именно сверяет.
 *
 * Пакет вне {@code org.ip}, чтобы боевой скан приложения (basePackage=org.ip) не видел
 * фикстуру в тестовом рантайме. Подпакеты отлажены так, что в каждом лежит РОВНО одна
 * фикстура: сканирование basePackage рекурсивно, и две сломанные фикстуры в одном
 * корне дали бы недетерминированное сообщение отказа (порядок кандидатов не гарантирован).
 */
public class ConsistentCustomDimension {

    public static final String READ_CONDITION = "owner_id in (:allowedIds)";

    @RlsDimension(value = "PARITY_OK", custom = true, readCondition = READ_CONDITION)
    @FilterDef(name = "PARITY_OK", parameters = @ParamDef(name = "allowedIds", type = Long.class))
    @Filter(name = "PARITY_OK", condition = READ_CONDITION)
    public static class Entity {
    }
}

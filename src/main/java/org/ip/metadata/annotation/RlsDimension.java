package org.ip.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает сущность как защищённую RLS по указанному измерению — значение должно совпадать
 * с именем Hibernate-фильтра, объявленного тут же через @FilterDef/@Filter, и с
 * AccessGrant.dimension в таблице прав (одна и та же строка везде, не три
 * синхронизируемых вручную).
 *
 * Отдельной аннотации "эта сущность вообще под RLS" (типа @RlsProtected) не заводим —
 * сам факт наличия @Filter уже об этом говорит; @RlsDimension добавляет только то, чего
 * @Filter не может выразить сам — КАКИМ измерением защищена сущность, чтобы
 * ensureRlsEnabled/RlsFilterActivator знали, какой список id резолвить и в какой фильтр
 * его положить.
 *
 * Пример:
 * <pre>
 * {@code
 * @Entity
 * @RlsDimension("JOURNAL")
 * @FilterDef(name = "JOURNAL", parameters = @ParamDef(name = "allowedIds", type = Long.class))
 * @Filter(name = "JOURNAL", condition = "journal_id in (:allowedIds)")
 * public class PrdSpec extends BaseEntity { ... }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RlsDimension {
    String value();
}

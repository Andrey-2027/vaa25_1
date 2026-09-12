package org.ipro.rls;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Объявляет, что сущность участвует в измерении RLS с данным именем — только для того,
 * чтобы {@link RlsDimensionRegistry} (classpath-скан) знал, какие измерения вообще
 * существуют в приложении и какого они рода (см. {@link RlsDimensionKind}).
 *
 * Значение должно совпадать с AccessGrant.dimension везде — одна и та же строка, не
 * синхронизируемая вручную. Для FILTERABLE-измерений — ещё и с именем @Filter/@FilterDef.
 *
 * Descriptor, собранный из этой аннотации, задаёт полный набор измерений и их kind для
 * read/write/delete enforcement. Значения конкретной записи предоставляет
 * {@link RlsDimensionValue#getRlsChecks()}; runtime сверяет, что набор ключей совпадает
 * с descriptor, и при расхождении отказывает (fail-closed).
 *
 * @Repeatable — сущность может участвовать в нескольких измерениях сразу, возможно
 * разного рода (например, документ и по JOURNAL/BRANCH — FILTERABLE, и по
 * "ENTITY:ReceivingDocument" — CHECK_ONLY):
 * <pre>{@code
 * @RlsDimension("JOURNAL")
 * @RlsDimension("BRANCH")
 * @RlsDimension(value = "ENTITY:ReceivingDocument", kind = RlsDimensionKind.CHECK_ONLY)
 * public class ReceivingDocument extends org.ipro.crud.BaseEntity { ... }
 * }</pre>
 *
 * Одноизмеренческий случай (Journal/PrdSpec/Branch/Workshop) пишется как раньше, без
 * указания kind — дефолт FILTERABLE, менять их не нужно:
 * <pre>{@code
 * @RlsDimension("JOURNAL")
 * @FilterDef(name = "JOURNAL", parameters = @ParamDef(name = "allowedIds", type = Long.class))
 * @Filter(name = "JOURNAL", condition = "journal_id in (:allowedIds)")
 * public class PrdSpec extends org.ipro.crud.BaseEntity { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RlsDimensions.class)
public @interface RlsDimension {
    String value();

    RlsDimensionKind kind() default RlsDimensionKind.FILTERABLE;

    /** Entity itself is the catalog of grantable values for this dimension. */
    boolean grantValues() default false;

    /** Property paths whose numeric ids are checked for write/delete. */
    String[] valuePaths() default {"id"};

    /** A null path means that this dimension does not apply to this row. */
    boolean nullsNotApplicable() default false;

    /** Complex policy supplies values through {@link RlsDimensionValue}. */
    boolean custom() default false;
}

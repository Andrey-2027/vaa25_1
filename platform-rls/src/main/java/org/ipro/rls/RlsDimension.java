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

    /**
     * Read-предикат сложной (custom) политики — условие, которое обязано стоять в
     * {@code @Filter(condition = ...)} для этого измерения.
     *
     * Зачем он нужен именно для custom: у стандартного измерения read-предикат
     * ВЫВОДИТСЯ из {@link #valuePaths()}/{@link #nullsNotApplicable()} и сверяется с
     * фактическим {@code @Filter} при старте (checked duplication, ADX-06). У сложной
     * политики вывести его нечем — там подзапросы и конъюнкция нескольких путей, —
     * поэтому без явного объявления read-предикат вообще нигде не заявлен как intent:
     * правка SQL фильтра и правка {@link RlsDimensionValue#getRlsChecks()} расходятся
     * молча, и расходятся в самом опасном направлении (видно меньше, чем можно менять,
     * либо набор строк записи шире читаемого).
     *
     * Объявлять следует так, чтобы расхождение было физически невозможно — одной
     * константой в обоих местах:
     * <pre>{@code
     * public static final String BRANCH_READ = "receiving_workshop_id in (...)";
     *
     * @RlsDimension(value = "BRANCH", custom = true, readCondition = BRANCH_READ)
     * @Filter(name = "BRANCH", condition = BRANCH_READ)
     * }</pre>
     * Реестр всё равно сверяет объявленное с фактическим {@code @Filter} и падает при
     * старте приложения, если они разошлись: гарантия нужна для любой custom-политики,
     * включая написанную копипастой, а не только для написанной через константу.
     *
     * Атрибут имеет смысл ТОЛЬКО при {@code custom = true} и {@code kind = FILTERABLE}:
     * <ul>
     * <li>у стандартного измерения предикат выводится из {@link #valuePaths()}, и
     *     объявленное значение было бы молча проигнорировано (looklike-переопределение);</li>
     * <li>у CHECK_ONLY-измерения фильтра нет вообще, сверять предикат не с чем —
     *     «условие на будущее» опаснее отсутствующего, потому что выглядит как контракт.</li>
     * </ul>
     * Оба случая — отказ при старте, а не no-op.
     */
    String readCondition() default "";
}

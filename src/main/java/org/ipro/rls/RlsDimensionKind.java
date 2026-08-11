package org.ipro.rls;

/**
 * Различает два способа участия измерения в RLS (см. обсуждение — "Журнал/Филиал"
 * построчно фильтруют SELECT, а "доступ к виду документа целиком" — нет и не может):
 *
 * <ul>
 * <li>{@link #FILTERABLE} — измерению соответствует Hibernate {@code @Filter}/
 *     {@code @FilterDef} с ТЕМ ЖЕ именем. {@link RlsFilterActivator} включает его на
 *     сессии через {@code session.enableFilter(dimension)}. Это единственный вид
 *     измерения, который существовал до появления CHECK_ONLY (JOURNAL, BRANCH) —
 *     дефолтное значение {@link RlsDimension#kind()} ради обратной совместимости.</li>
 * <li>{@link #CHECK_ONLY} — измерение участвует ТОЛЬКО в write-guard'е сервисов
 *     (см. AbstractBaseService.checkRls) и в {@link AccessService#getReadableIds} (для
 *     скрытия навигации/меню — см. RlsNavigationAccess.hasAnyAccess). Никакого
 *     {@code @Filter} для него нет и не должно быть; {@link RlsFilterActivator} НЕ
 *     вызывает {@code enableFilter} для таких измерений — иначе Hibernate бросит
 *     {@code UnknownFilterException}, потому что соответствующего {@code @FilterDef}
 *     физически не существует.
 *     По {@code AccessGrant.dimensionValueId} для CHECK_ONLY-измерений — всегда
 *     {@code null} (wildcard): построчных грантов у измерения "доступ к виду сущности
 *     целиком" не бывает по определению, там нечем разграничивать построчно.</li>
 * </ul>
 *
 * Соглашение по именованию (не проверяется программно, но принято в проекте): у
 * CHECK_ONLY-измерений префикс {@code "ENTITY:"} (например, {@code "ENTITY:Order"}) —
 * чтобы в AccessGrant/админ-матрице не путать их с FILTERABLE-измерениями по бизнес-
 * атрибуту (JOURNAL, BRANCH).
 */
public enum RlsDimensionKind {
    FILTERABLE,
    CHECK_ONLY
}
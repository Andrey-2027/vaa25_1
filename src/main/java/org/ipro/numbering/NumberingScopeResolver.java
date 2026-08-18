package org.ipro.numbering;

/**
 * Контракт извлечения значения scope-измерения из сущности для ключа счётчика.
 *
 * <p>Платформа не знает конкретных измерений предметной области (JOURNAL/BRANCH/...), а
 * работает со строками-ключами — ровно как RLS работает с именами измерений. Дефолт-реализация
 * ({@link org.ipro.rls.RlsScopeResolver}) достаёт значение из {@code RlsDimensionValue.getRlsChecks()}
 * там, где scope нумерации совпадает с измерением доступа; сущность без RLS-измерения может
 * предоставить свой резолвер.</p>
 */
@FunctionalInterface
public interface NumberingScopeResolver {

    /**
     * Значение измерения {@code dimension} для сущности, или {@code null} если сущность в
     * этом измерении не участвует (не GLOBAL-семантика: участвует в ключе как {@code null}).
     */
    Long scopeValue(String dimension, Object entity);
}

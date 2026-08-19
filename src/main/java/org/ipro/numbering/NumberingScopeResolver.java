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

    /**
     * Умеет ли резолвер обслуживать измерение {@code dimension}. Default {@code true} —
     * кастомный резолвер отвечает за свои измерения; {@link org.ipro.rls.RlsScopeResolver}
     * переопределяет: TRUE только для измерений, реально зарегистрированных в RLS, поэтому
     * стартовый fail-fast (NumberingService.afterPropertiesSet) ловит типичную ошибку
     * конфигурации — {@code @Numbered(scope="JOURNAL"), где "JOURNAL" не измерение, а опечатка}.
     */
    default boolean canResolve(String dimension) {
        return true;
    }
}

package org.ipro.rls;

import java.util.List;

/**
 * Источник grantable-записей для ОДНОГО измерения RLS — то, что раньше было
 * захардкожено в AccessGrantAdminService под Journal (см. его прежний javadoc: "когда
 * появится вторая @RlsDimension-сущность..." — вот этот момент наступил с появлением
 * BRANCH). Каждая реализация — простой бин, Spring собирает все сразу списком
 * (см. AccessGrantAdminService), без правки самого сервиса при добавлении измерения.
 */
public interface RlsDimensionValueSource<T> {

    /** Имя измерения — то же, что в AccessGrant.dimension / @Filter.name(). */
    String dimension();

    /**
     * ВСЕ записи, независимо от RLS-ограничений ТЕКУЩЕГО (админского) пользователя —
     * иначе часть записей молча выпала бы из матрицы, которую админ настраивает для
     * ДРУГИХ (см. RlsFilterActivator.withRlsDisabled — обычный ensureRlsEnabled тут не
     * годится, если фильтр этого измерения уже включён раньше в этом round-trip).
     */
    List<T> allIgnoringRls();

    Long idOf(T value);

    String displayCode(T value);

    String displayName(T value);
}
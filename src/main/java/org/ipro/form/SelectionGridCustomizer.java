package org.ipro.form;

import org.ipro.filtergrid.jpa.JpaFilterGrid;

/**
 * Донастройка собранного грида Формы Выбора без переписывания сборки.
 *
 * Вызывается из {@link SelectionFormAssembler} после добавления колонок из
 * резолвленного набора, но до создания {@link SelectionForm}: можно добавить колонки,
 * фильтры, поменять ширину, задать дополнительную спецификацию.
 *
 * Реализации — обычные Spring-бины (платформа собирает их списком, сортирует по
 * {@code @Order}/{@code Ordered}). Правило — как везде в формах: работа над уже
 * собранным объектом, никакого нового DSL поверх.
 */
public interface SelectionGridCustomizer {

    /**
     * Применим ли кастомайзер к данной сущности/варианту.
     *
     * @param entityClass класс целевой сущности
     * @param variant имя варианта Формы Выбора (null = default)
     */
    boolean supports(Class<?> entityClass, String variant);

    /**
     * Донастроить грид. Вызывается один раз на сборку, после колонок из метаданных.
     *
     * @param grid уже созданный грид с колонками
     * @param entityClass класс целевой сущности
     * @param variant имя варианта Формы Выбора (null = default)
     */
    <T> void customize(JpaFilterGrid<T> grid, Class<?> entityClass, String variant);
}

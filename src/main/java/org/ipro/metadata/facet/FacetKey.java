package org.ipro.metadata.facet;

import java.util.Objects;

/**
 * Стабильный адрес факта метаданных — ключ, по которому будущее хранилище переопределений
 * (роль 3) сможет адресовать переопределение без перестройки модели.
 *
 * <p>Состав зависит от грани:</p>
 * <ul>
 *   <li>entity-уровень ({@link FacetKind#ENTITY_LIST_TITLE} и др.) — {@code entityClass},
 *       {@code fieldName = null}, {@code variant = null};</li>
 *   <li>поле ({@link FacetKind#FIELD_LABEL}, {@code FIELD_STRUCTURE}) — {@code entityClass}
 *       + {@code fieldName};</li>
 *   <li>колонка ({@link FacetKind#GRID_COLUMN_HEADER}) — {@code entityClass} + {@code fieldName}
 *       = путь колонки (имя поля или путь через точку);</li>
 *   <li>вариант-специфичная грань ({@link FacetKind#CONTEXT_FILTER_LABEL}) — дополнительно
 *       {@code variant} (null = общий ряд сущности).</li>
 * </ul>
 *
 * @param kind        вид грани (несёт и флаг переопределяемости)
 * @param entityClass сущность, которой принадлежит факт
 * @param fieldName   имя поля/путь колонки; null для entity-уровневых граней
 * @param variant     имя варианта (формы/ряда); null = default/общий
 */
public record FacetKey(FacetKind kind, Class<?> entityClass, String fieldName, String variant) {

    public FacetKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(entityClass, "entityClass");
    }

    /** Ключ entity-уровневой грани (без поля и варианта). */
    public static FacetKey of(FacetKind kind, Class<?> entityClass) {
        return new FacetKey(kind, entityClass, null, null);
    }

    /** Ключ грани поля (без варианта). */
    public static FacetKey of(FacetKind kind, Class<?> entityClass, String fieldName) {
        return new FacetKey(kind, entityClass, fieldName, null);
    }

    /** Полный ключ: грань + поле + вариант (для вариант-специфичных граней). */
    public static FacetKey of(FacetKind kind, Class<?> entityClass, String fieldName, String variant) {
        return new FacetKey(kind, entityClass, fieldName, variant);
    }
}

package org.ipro.query.model;

import java.util.Objects;

/**
 * Объявление параметра запроса — без JPA-сущности, отчётного шаблона и
 * UniDR-модели. Поля покрывают обе текущие модели параметров:
 * <ul>
 *   <li>{@code id} — стабильный идентификатор (у UDR совпадает с именем);</li>
 *   <li>{@code name} — имя binding'а в JPQL;</li>
 *   <li>{@code kind} — скаляр/диапазон/сущность/список сущностей;</li>
 *   <li>{@code scalarType} — типизированный ввод, если он известен;</li>
 *   <li>{@code entityClass} — canonical-имя класса для ENTITY/ENTITY_LIST;</li>
 *   <li>{@code valueSource}, {@code required}, {@code defaultValue},
 *       {@code computed}, {@code contextKey} — источник и правила значения.</li>
 * </ul>
 *
 * <p>Диапазон связывает два имени: {@code name + "From"} и {@code name + "To"}.
 * Это правило принадлежит контракту запроса, а не конкретному движку.</p>
 */
public record QueryParameter(
        String id,
        String name,
        String caption,
        QueryParameterKind kind,
        QueryScalarType scalarType,
        String entityClass,
        QueryValueSource valueSource,
        boolean required,
        boolean showOnForm,
        String defaultValue,
        QueryComputedValue computed,
        String contextKey,
        int position) {

    public QueryParameter {
        Objects.requireNonNull(name, "name");
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,49}")) {
            throw new IllegalArgumentException("Некорректное имя параметра: " + name);
        }
        id = id == null || id.isBlank() ? name : id;
        caption = caption == null ? "" : caption;
        kind = kind == null ? QueryParameterKind.SCALAR : kind;
        scalarType = scalarType == null ? QueryScalarType.UNRESOLVED : scalarType;
        entityClass = entityClass == null || entityClass.isBlank() ? null : entityClass;
        valueSource = valueSource == null ? QueryValueSource.FORM : valueSource;
        defaultValue = defaultValue == null || defaultValue.isBlank() ? null : defaultValue;
        computed = computed == null ? QueryComputedValue.NONE : computed;
        contextKey = contextKey == null || contextKey.isBlank() ? null : contextKey;
    }

    /** Упрощённое объявление для сценариев, где тип и источник не заданы. */
    public QueryParameter(String name) {
        this(name, name, name, QueryParameterKind.SCALAR, QueryScalarType.UNRESOLVED,
                null, QueryValueSource.FORM, false, true, null, QueryComputedValue.NONE, null, 0);
    }

    /** Обязательный скалярный параметр формы — типовой случай отбора. */
    public static QueryParameter required(String name) {
        return new QueryParameter(name, name, name, QueryParameterKind.SCALAR,
                QueryScalarType.UNRESOLVED, null, QueryValueSource.FORM, true, true,
                null, QueryComputedValue.NONE, null, 0);
    }

    public String fromBinding() {
        return name + "From";
    }

    public QueryParameter withName(String name) {
        return new QueryParameter(id, name, caption, kind, scalarType, entityClass, valueSource,
                required, showOnForm, defaultValue, computed, contextKey, position);
    }

    public QueryParameter withCaption(String caption) {
        return new QueryParameter(id, name, caption, kind, scalarType, entityClass, valueSource,
                required, showOnForm, defaultValue, computed, contextKey, position);
    }

    public QueryParameter withKind(QueryParameterKind kind) {
        return new QueryParameter(id, name, caption, kind, scalarType, entityClass, valueSource,
                required, showOnForm, defaultValue, computed, contextKey, position);
    }

    public QueryParameter withEntityClass(String entityClass) {
        return new QueryParameter(id, name, caption, kind, scalarType, entityClass, valueSource,
                required, showOnForm, defaultValue, computed, contextKey, position);
    }

    public QueryParameter withValueSource(QueryValueSource valueSource) {
        return new QueryParameter(id, name, caption, kind, scalarType, entityClass, valueSource,
                required, showOnForm, defaultValue, computed, contextKey, position);
    }

    public QueryParameter withDefaultValue(String defaultValue) {
        return new QueryParameter(id, name, caption, kind, scalarType, entityClass, valueSource,
                required, showOnForm, defaultValue, computed, contextKey, position);
    }

    public QueryParameter withComputed(QueryComputedValue computed) {
        return new QueryParameter(id, name, caption, kind, scalarType, entityClass, valueSource,
                required, showOnForm, defaultValue, computed, contextKey, position);
    }

    public QueryParameter withRequired(boolean required) {
        return new QueryParameter(id, name, caption, kind, scalarType, entityClass, valueSource,
                required, showOnForm, defaultValue, computed, contextKey, position);
    }

    public QueryParameter withShowOnForm(boolean showOnForm) {
        return new QueryParameter(id, name, caption, kind, scalarType, entityClass, valueSource,
                required, showOnForm, defaultValue, computed, contextKey, position);
    }

    public QueryParameter withPosition(int position) {
        return new QueryParameter(id, name, caption, kind, scalarType, entityClass, valueSource,
                required, showOnForm, defaultValue, computed, contextKey, position);
    }

    public String toBinding() {
        return name + "To";
    }
}

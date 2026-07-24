package org.ip.form.registry;

import java.util.Objects;

/**
 * Ключ для идентификации формы в реестре.
 *
 * Комбинация:
 *   - entityClass — класс сущности (например, Nomenclature.class)
 *   - formType — тип формы (LIST, ITEM, SELECTION)
 *   - variant — имя варианта (например, "archived", "compact", "extended")
 *                null означает "default" вариант
 */
public record FormKey(
    Class<?> entityClass,
    FormType formType,
    String variant
) {
    public FormKey {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        Objects.requireNonNull(formType, "formType cannot be null");
    }

    /**
     * Создать ключ для default варианта (variant = null).
     */
    public static FormKey ofDefault(Class<?> entityClass, FormType formType) {
        return new FormKey(entityClass, formType, null);
    }

    /**
     * Создать ключ для именованного варианта.
     */
    public static FormKey of(Class<?> entityClass, FormType formType, String variant) {
        return new FormKey(entityClass, formType, variant);
    }

    /**
     * Является ли этот ключ default вариантом.
     */
    public boolean isDefault() {
        return variant == null;
    }
}

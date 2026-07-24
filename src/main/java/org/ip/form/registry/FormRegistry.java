package org.ip.form.registry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр кастомных форм.
 *
 * Хранит зарегистрированные варианты форм (ListForm, ItemForm, SelectionForm)
 * и предоставляет API для их регистрации и поиска.
 *
 * Использование:
 * <pre>
 * {@code @Configuration}
 * public class FormsConfiguration {
 *     {@code @Bean}
 *     public FormRegistry formRegistry() {
 *         FormRegistry registry = new FormRegistry();
 *
 *         // Регистрация кастомной формы списка
 *         registry.register(
 *             Nomenclature.class,
 *             FormType.LIST,
 *             "archived",
 *             context -> new ArchivedNomenclatureListForm(context)
 *         );
 *
 *         return registry;
 *     }
 * }
 * </pre>
 */
public class FormRegistry {

    private final Map<FormKey, FormFactory> forms = new ConcurrentHashMap<>();

    /**
     * Зарегистрировать кастомную форму.
     *
     * @param entityClass класс сущности
     * @param formType тип формы
     * @param variant имя варианта (null = default)
     * @param factory фабрика для создания формы
     */
    public void register(Class<?> entityClass, FormType formType, String variant, FormFactory factory) {
        FormKey key = new FormKey(entityClass, formType, variant);
        forms.put(key, factory);
    }

    /**
     * Зарегистрировать форму списка.
     */
    public void registerListForm(Class<?> entityClass, String variant, FormFactory factory) {
        register(entityClass, FormType.LIST, variant, factory);
    }

    /**
     * Зарегистрировать форму элемента.
     */
    public void registerItemForm(Class<?> entityClass, String variant, FormFactory factory) {
        register(entityClass, FormType.ITEM, variant, factory);
    }

    /**
     * Зарегистрировать форму выбора.
     */
    public void registerSelectionForm(Class<?> entityClass, String variant, FormFactory factory) {
        register(entityClass, FormType.SELECTION, variant, factory);
    }

    /**
     * Найти зарегистрированную форму.
     *
     * @param entityClass класс сущности
     * @param formType тип формы
     * @param variant имя варианта (null = default)
     * @return фабрика формы или null, если не найдена
     */
    public FormFactory find(Class<?> entityClass, FormType formType, String variant) {
        FormKey key = new FormKey(entityClass, formType, variant);
        return forms.get(key);
    }

    /**
     * Найти форму списка.
     */
    public FormFactory findListForm(Class<?> entityClass, String variant) {
        return find(entityClass, FormType.LIST, variant);
    }

    /**
     * Найти форму элемента.
     */
    public FormFactory findItemForm(Class<?> entityClass, String variant) {
        return find(entityClass, FormType.ITEM, variant);
    }

    /**
     * Найти форму выбора.
     */
    public FormFactory findSelectionForm(Class<?> entityClass, String variant) {
        return find(entityClass, FormType.SELECTION, variant);
    }

    /**
     * Проверить, зарегистрирована ли форма.
     */
    public boolean has(Class<?> entityClass, FormType formType, String variant) {
        return find(entityClass, formType, variant) != null;
    }

    /**
     * Удалить зарегистрированную форму.
     */
    public void unregister(Class<?> entityClass, FormType formType, String variant) {
        FormKey key = new FormKey(entityClass, formType, variant);
        forms.remove(key);
    }

    /**
     * Очистить все регистрации.
     */
    public void clear() {
        forms.clear();
    }

    /**
     * Количество зарегистрированных форм.
     */
    public int size() {
        return forms.size();
    }
}

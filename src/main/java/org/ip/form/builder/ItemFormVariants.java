package org.ip.form.builder;

import org.ip.form.registry.FormFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Собирает варианты Формы Элемента для одной сущности — используется внутри
 * {@link ItemFormCustomization#configure(ItemFormVariants)}.
 *
 * Один класс-конфиг (реализация {@code ItemFormCustomization}) описывает СРАЗУ все свои
 * варианты через этот коллектор, а не заводит по отдельному классу на каждый вариант — так
 * количество классов не растёт вместе с числом вариантов одной сущности.
 */
public final class ItemFormVariants {

    private final Map<String, ItemFormBuilder<?>> builders = new LinkedHashMap<>();
    private final Map<String, FormFactory> customFactories = new LinkedHashMap<>();

    /**
     * Добавить default-вариант (тот, что используется, когда открытие формы не указывает
     * конкретный вариант явно) — через дерево {@link ItemFormBuilder}.
     */
    public ItemFormVariants addDefault(ItemFormBuilder<?> builder) {
        return add(null, builder);
    }

    /**
     * Добавить именованный вариант через дерево {@link ItemFormBuilder}.
     */
    public ItemFormVariants add(String variant, ItemFormBuilder<?> builder) {
        builders.put(variant, builder);
        return this;
    }

    /**
     * Escape hatch: зарегистрировать default-вариант напрямую через {@link FormFactory} — когда
     * дерева {@link ItemFormBuilder} (addField/addPanel/addTabSheet/addCustom) недостаточно и
     * форму нужно написать вручную целиком (например, свой подкласс {@code ItemForm} с
     * дополнительной логикой). Фабрика получает {@code FormContext} с уже положенными
     * {@code metadataResolver}/{@code fieldFactory} — теми же, что использует
     * {@code ItemFormBuilder.build()}.
     */
    public ItemFormVariants addDefaultCustom(FormFactory factory) {
        return addCustom(null, factory);
    }

    /**
     * Escape hatch: зарегистрировать именованный вариант напрямую через {@link FormFactory}.
     */
    public ItemFormVariants addCustom(String variant, FormFactory factory) {
        customFactories.put(variant, factory);
        return this;
    }

    /** Только для {@link ItemFormCustomizationRegistrar}. */
    Map<String, ItemFormBuilder<?>> getBuilders() {
        return builders;
    }

    /** Только для {@link ItemFormCustomizationRegistrar}. */
    Map<String, FormFactory> getCustomFactories() {
        return customFactories;
    }
}

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
 *
 * Регистрация — напрямую через {@link FormFactory} (обычная Java-функция
 * {@code FormContext -> ItemForm}), без промежуточного дерева layout'а: сборка формы —
 * это код, использующий FieldFactory/ItemForm/FormBindingRegistry напрямую, а не
 * декларация через ещё один DSL поверх них (раньше здесь было дерево ItemFormBuilder
 * addField/addPanel/addTabSheet — на практике каждый реальный случай кастомизации всё
 * равно проваливался в escape hatch, поэтому дерево убрано, остался только escape hatch,
 * ставший основным путём).
 */
public final class ItemFormVariants {

    private final Map<String, FormFactory> factories = new LinkedHashMap<>();

    /** Default-вариант (используется, когда открытие формы не указывает вариант явно). */
    public ItemFormVariants addDefault(FormFactory factory) {
        return add(null, factory);
    }

    /** Именованный вариант. */
    public ItemFormVariants add(String variant, FormFactory factory) {
        factories.put(variant, factory);
        return this;
    }

    /** Только для {@link ItemFormCustomizationRegistrar}. */
    Map<String, FormFactory> getFactories() {
        return factories;
    }
}

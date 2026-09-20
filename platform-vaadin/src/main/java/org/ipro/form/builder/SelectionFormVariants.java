package org.ipro.form.builder;

import org.ipro.form.registry.FormFactory;
import org.ipro.form.registry.SelectionColumnsDef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Коллектор вариантов Формы Выбора одной сущности. Передаётся в
 * {@link SelectionFormCustomization#configure} и аккумулирует data-варианты
 * (наборы колонок) + полные фабрики диалогов.
 *
 * Используется {@link SelectionFormCustomizationRegistrar} для регистрации всех
 * вариантов в {@code FormRegistry}.
 *
 * Один класс-конфиг описывает СРАЗУ все варианты своей сущности через этот коллектор,
 * а не заводит по отдельному классу на вариант.
 */
public class SelectionFormVariants {

    private final Map<String, FormFactory> factories = new LinkedHashMap<>();
    private final Map<String, SelectionColumnsDef> columns = new LinkedHashMap<>();
    private final Map<String, List<ContextFilterField>> contextFilterRows = new LinkedHashMap<>();

    /** Default-вариант: полностью свой диалог (фабрика {@code FormContext -> SelectionForm}). */
    public SelectionFormVariants addDefault(FormFactory factory) {
        return add(null, factory);
    }

    /** Именованный вариант: полностью свой диалог. */
    public SelectionFormVariants add(String variant, FormFactory factory) {
        factories.put(variant, factory);
        return this;
    }

    /** Default-набор колонок (перекрывает selectColumns/listColumns метаданных). */
    public SelectionFormVariants addDefaultColumns(String... columns) {
        return addColumns(null, List.of(columns), null);
    }

    /** Именованный набор колонок. */
    public SelectionFormVariants addColumns(String variant, String... columns) {
        return addColumns(variant, List.of(columns), null);
    }

    /** Именованный набор колонок с заголовком диалога. */
    public SelectionFormVariants addColumns(String variant, List<String> columns, String title) {
        this.columns.put(variant, SelectionColumnsDef.of(columns, title));
        return this;
    }

    /**
     * Ряд контекст-фильтров только этого варианта выбора (замена прочих рядов,
     * не дополнение). Не задан — лестница диалога: свой ряд → поля с
     * {@code allListVariants()}.
     */
    public SelectionFormVariants contextFilters(String variant, List<ContextFilterField> fields) {
        contextFilterRows.put(variant, List.copyOf(fields));
        return this;
    }

    // Package-visible для Registrar
    Map<String, FormFactory> getFactories() {
        return factories;
    }

    Map<String, List<ContextFilterField>> getContextFilterRows() {
        return contextFilterRows;
    }

    Map<String, SelectionColumnsDef> getColumns() {
        return columns;
    }
}

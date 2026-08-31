package org.ip.form.builder;

import com.vaadin.flow.component.Component;
import org.ip.form.registry.FormFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Коллектор вариантов Формы Списка одной сущности. Передаётся в
 * {@link ListFormCustomization#configure} и аккумулирует default-вариант + именованные варианты.
 *
 * Используется {@link ListFormCustomizationRegistrar} для регистрации всех вариантов в
 * {@code FormRegistry}.
 *
 * Два способа задать вариант:
 *   - {@link #addDefault(FormFactory)}/{@link #add(String, FormFactory)} — обычная функция
 *     {@code FormContext -> ListForm}, использующая FieldFactory/ColumnPath/сервис напрямую;     *   - {@link #addDefaultView(Class)}/{@link #addView(String, Class)} — когда список нужно
     *     не сгенерировать из метаданных, а собрать композицией (ListForm внутри обычного
     *     Vaadin-компонента вместе с другим UI: ComboBox-контекст + ListForm.setContextFilter(...)).
     *     Если View реализует {@code ListFormViewContextAware}, координатор передаст ему
     *     параметры открытия через {@code init(FormContext)}.

 *     Простые «контекстные» случаи (например выбор журнала/матрицы для реестра) лучше выражать
 *     декларативно через {@link ListFormCustomization#contextFilters()} — панель контекст-фильтров
 *     ListForm.
 */
public class ListFormVariants {

    private final Map<String, FormFactory> factories = new LinkedHashMap<>();
    private final Map<String, FormFactory> viewFactories = new LinkedHashMap<>();
    private final Map<String, Class<? extends Component>> views = new LinkedHashMap<>();

    public ListFormVariants addDefault(FormFactory factory) {
        return add(null, factory);
    }

    public ListFormVariants add(String variant, FormFactory factory) {
        factories.put(variant, factory);
        return this;
    }

    /** Default-вариант, открывающий не generic ListForm, а указанный View-класс целиком. */
    public ListFormVariants addDefaultView(Class<? extends Component> viewClass) {
        return addView(null, viewClass);
    }

    /** Именованный вариант, открывающий указанный View-класс целиком. */
    public ListFormVariants addView(String variant, Class<? extends Component> viewClass) {
        views.put(variant, viewClass);
        return this;
    }

    /** View-фабрика с доступом к параметрам конкретного открытия. */
    public ListFormVariants addView(String variant, FormFactory factory) {
        viewFactories.put(variant, factory);
        return this;
    }

    public ListFormVariants addDefaultView(FormFactory factory) {
        return addView(null, factory);
    }

    // Package-visible для Registrar
    Map<String, FormFactory> getFactories() {
        return factories;
    }

    Map<String, FormFactory> getViewFactories() {
        return viewFactories;
    }

    Map<String, Class<? extends Component>> getViews() {
        return views;
    }
}

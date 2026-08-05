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
 *     {@code FormContext -> ListForm}, использующая FieldFactory/ColumnPath/сервис напрямую;
 *   - {@link #addDefaultView(Class)}/{@link #addView(String, Class)} — когда список нужно
 *     не сгенерировать из метаданных, а собрать композицией (ListForm внутри обычного
 *     Vaadin-компонента вместе с другим UI, как {@code PrdSpecByJournalView}: ComboBox для
 *     выбора журнала + ListForm.setContextFilter(...)).
 */
public class ListFormVariants {

    private final Map<String, FormFactory> factories = new LinkedHashMap<>();
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

    // Package-visible для Registrar
    Map<String, FormFactory> getFactories() {
        return factories;
    }

    Map<String, Class<? extends Component>> getViews() {
        return views;
    }
}

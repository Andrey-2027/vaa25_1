package org.ip.form.builder;

import com.vaadin.flow.component.Component;
import org.ip.form.registry.FormFactory;
import org.ipro.crud.IdentifiableEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Коллектор вариантов Формы Списка одной сущности. Передаётся в
 * {@link ListFormCustomization#configure} и аккумулирует default-вариант + именованные варианты.
 *
 * Используется {@link ListFormCustomizationRegistrar} для регистрации всех вариантов в
 * {@code FormRegistry}.
 */
public class ListFormVariants {

    private final Map<String, ListFormBuilder<?>> builders = new HashMap<>();
    private final Map<String, FormFactory> customFactories = new HashMap<>();
    private final Map<String, Class<? extends Component>> customViews = new HashMap<>();

    /**
     * Зарегистрировать default-вариант через {@link ListFormBuilder}.
     */
    public <T extends IdentifiableEntity> ListFormVariants addDefault(ListFormBuilder<T> builder) {
        return add(null, builder);
    }

    /**
     * Зарегистрировать именованный вариант через {@link ListFormBuilder}.
     */
    public <T extends IdentifiableEntity> ListFormVariants add(String variant, ListFormBuilder<T> builder) {
        builders.put(variant, builder);

        // Если builder указывает customView — регистрируем его отдельно
        if (builder.getCustomViewClass() != null) {
            customViews.put(variant, builder.getCustomViewClass());
        }

        return this;
    }

    /**
     * Зарегистрировать default-вариант через полностью кастомную фабрику (escape hatch).
     */
    public ListFormVariants addDefaultCustom(FormFactory factory) {
        return addCustom(null, factory);
    }

    /**
     * Зарегистрировать именованный вариант через полностью кастомную фабрику (escape hatch).
     */
    public ListFormVariants addCustom(String variant, FormFactory factory) {
        customFactories.put(variant, factory);
        return this;
    }

    // Package-visible для Registrar
    Map<String, ListFormBuilder<?>> getBuilders() {
        return builders;
    }

    Map<String, FormFactory> getCustomFactories() {
        return customFactories;
    }

    Map<String, Class<? extends Component>> getCustomViews() {
        return customViews;
    }

    /**
     * Получить кастомный View-класс для указанного варианта.
     * Возвращает null если вариант использует автоматическую форму.
     */
    public Class<? extends Component> getViewClass(String variant) {
        return customViews.get(variant);
    }
}

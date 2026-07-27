package org.ip.form.builder;

import org.ip.form.registry.FormFactory;
import org.ip.form.registry.FormRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Находит все бины {@link ListFormCustomization} и регистрирует их варианты в
 * {@link FormRegistry}. Один общий класс на весь проект — конкретные классы-конфиги (по одному
 * на сущность, см. {@code org.ip.views.forms}) ничего не знают про {@code FormRegistry} или
 * жизненный цикл Spring-бинов, только описывают колонки через {@link ListFormBuilder}.
 */
@Component
public class ListFormCustomizationRegistrar implements InitializingBean {

    private final FormRegistry formRegistry;
    private final List<ListFormCustomization> customizations;

    public ListFormCustomizationRegistrar(FormRegistry formRegistry,
                                          List<ListFormCustomization> customizations) {
        this.formRegistry = formRegistry;
        this.customizations = customizations;
    }

    @Override
    public void afterPropertiesSet() {
        for (ListFormCustomization customization : customizations) {
            Class<?> entityClass = customization.entityClass();
            ListFormVariants variants = new ListFormVariants();
            customization.configure(variants);

            // Регистрируем билдеры (превращаем в фабрики)
            variants.getBuilders().forEach((variant, builder) -> {
                FormFactory factory = builder.build();
                formRegistry.registerListForm(entityClass, variant, factory);
            });

            // Регистрируем полностью кастомные фабрики (escape hatch)
            variants.getCustomFactories().forEach((variant, factory) ->
                formRegistry.registerListForm(entityClass, variant, factory));

            // Регистрируем кастомные View-классы
            variants.getCustomViews().forEach((variant, viewClass) ->
                formRegistry.registerListFormView(entityClass, variant,
                    (Class<? extends com.vaadin.flow.component.Component>) viewClass));
        }
    }
}

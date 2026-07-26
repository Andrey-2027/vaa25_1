package org.ip.form.builder;

import org.ip.form.registry.FormFactory;
import org.ip.form.registry.FormRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Находит все бины {@link ItemFormCustomization} и регистрирует их варианты в
 * {@link FormRegistry}. Один общий класс на весь проект — конкретные классы-конфиги (по одному
 * на сущность, см. {@code org.ip.views.forms}) ничего не знают про {@code FormRegistry} или
 * жизненный цикл Spring-бинов, только описывают layout через {@link ItemFormBuilder}.
 */
@Component
public class ItemFormCustomizationRegistrar implements InitializingBean {

    private final FormRegistry formRegistry;
    private final List<ItemFormCustomization> customizations;

    public ItemFormCustomizationRegistrar(FormRegistry formRegistry,
                                          List<ItemFormCustomization> customizations) {
        this.formRegistry = formRegistry;
        this.customizations = customizations;
    }

    @Override
    public void afterPropertiesSet() {
        for (ItemFormCustomization customization : customizations) {
            Class<?> entityClass = customization.entityClass();
            ItemFormVariants variants = new ItemFormVariants();
            customization.configure(variants);

            variants.getBuilders().forEach((variant, builder) -> {
                FormFactory factory = builder.build();
                formRegistry.registerItemForm(entityClass, variant, factory);
            });
            variants.getCustomFactories().forEach((variant, factory) ->
                formRegistry.registerItemForm(entityClass, variant, factory));
        }
    }
}

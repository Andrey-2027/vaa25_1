package org.ipro.form.builder;

import org.ipro.form.registry.FormRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Находит все бины {@link SelectionFormCustomization} и регистрирует их варианты в
 * {@link FormRegistry}. Один общий класс на весь проект — конкретные классы-конфиги (по одному
 * на сущность, см. {@code org.ip.views.forms}) ничего не знают про {@code FormRegistry} или
 * жизненный цикл Spring-бинов, только описывают наборы колонок/фабрики через
 * {@link SelectionFormVariants}.
 */
@Component
public class SelectionFormCustomizationRegistrar implements InitializingBean {

    private final FormRegistry formRegistry;
    private final List<SelectionFormCustomization> customizations;

    public SelectionFormCustomizationRegistrar(FormRegistry formRegistry,
                                              List<SelectionFormCustomization> customizations) {
        this.formRegistry = formRegistry;
        this.customizations = customizations;
    }

    @Override
    public void afterPropertiesSet() {
        for (SelectionFormCustomization customization : customizations) {
            Class<?> entityClass = customization.entityClass();
            SelectionFormVariants variants = new SelectionFormVariants();
            customization.configure(variants);

            variants.getFactories().forEach((variant, factory) ->
                formRegistry.registerSelectionForm(entityClass, variant, factory));

            variants.getColumns().forEach((variant, def) ->
                formRegistry.registerSelectionColumns(entityClass, variant, def));

            variants.getContextFilterRows().forEach((variant, fields) ->
                formRegistry.registerVariantContextFilters(entityClass,
                    org.ipro.form.registry.FormType.SELECTION, variant, fields));

            if (!customization.contextFilters().isEmpty()) {
                formRegistry.registerSelectionContextFilters(entityClass, customization.contextFilters());
            }
        }
    }
}

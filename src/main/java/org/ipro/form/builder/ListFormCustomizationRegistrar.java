package org.ipro.form.builder;

import org.ipro.form.registry.FormRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Находит все бины {@link ListFormCustomization} и регистрирует их варианты в
 * {@link FormRegistry}. Один общий класс на весь проект — конкретные классы-конфиги (по одному
 * на сущность, см. {@code org.ip.views.forms}) ничего не знают про {@code FormRegistry} или
 * жизненный цикл Spring-бинов, только описывают FormFactory/View-класс через
 * {@link ListFormVariants}.
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
            String source = customization.getClass().getName();
            ListFormVariants variants = new ListFormVariants();
            customization.configure(variants);

            variants.getFactories().forEach((variant, factory) ->
                formRegistry.registerListForm(entityClass, variant, factory, source));

            variants.getViewFactories().forEach((variant, factory) ->
                formRegistry.registerListFormView(entityClass, variant, factory, source));

            variants.getViews().forEach((variant, viewClass) ->
                formRegistry.registerListFormView(entityClass, variant, viewClass, source));

            formRegistry.registerContextFilters(entityClass, customization.contextFilters(), source);

            variants.getContextFilterRows().forEach((variant, fields) ->
                formRegistry.registerVariantContextFilters(entityClass,
                    org.ipro.form.registry.FormType.LIST, variant, fields, source));

            variants.getCustomizers().forEach((variant, customizerList) ->
                customizerList.forEach(customizer ->
                    formRegistry.addListCustomizer(entityClass, variant, customizer)));
        }
    }
}

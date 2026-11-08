package org.ipro.form.builder;

import org.ipro.form.registry.FormRegistry;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;

/**
 * Находит все бины {@link ListFormCustomization} и регистрирует их варианты в
 * {@link FormRegistry}. Один общий класс на весь проект — конкретные классы-конфиги (по одному
 * на сущность, см. {@code org.ip.views.forms}) ничего не знают про {@code FormRegistry} или
 * жизненный цикл Spring-бинов, только описывают FormFactory/View-класс через
 * {@link ListFormVariants}.
 */
/**
 * Регистрация бина — в {@code FormAutoConfiguration}, без условий: регистратор агрегирует вклады
 * приложения в реестр форм (D3.5.5).
 */
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
            variants.getColumns().forEach((variant, columns) -> {
                columns.forEach(path -> org.ipro.metadata.ColumnPath.resolve(entityClass, path));
                formRegistry.registerListColumns(entityClass, variant, columns, source);
            });

            variants.getFactories().forEach((variant, factory) ->
                formRegistry.registerListForm(entityClass, variant, factory, source));

            variants.getViewFactories().forEach((variant, factory) ->
                formRegistry.registerListFormView(entityClass, variant, factory, source));

            variants.getViews().forEach((variant, viewClass) ->
                formRegistry.registerListFormView(entityClass, variant, viewClass, source));

            variants.getCommandLayouts().forEach((variant, layout) -> {
                try {
                    layout.validateStructure();
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException(source + ": list commands for "
                        + entityClass.getSimpleName() + " variant '" + variant + "': "
                        + invalid.getMessage(), invalid);
                }
                formRegistry.registerListCommandLayout(entityClass, variant, layout, source);
            });

            var fields = new java.util.LinkedHashMap<String, ContextFilterField>();
            customization.contextFilters().forEach(field -> fields.put(field.path(), field));
            for (var field : variants.getFilterPanel()) {
                if (fields.put(field.path(), field) != null)
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                        "{}: duplicate filter '{}': filterPanel overrides contextFilters()", source, field.path());
            }
            for (var field : fields.values()) {
                try {
                    var path = org.ipro.metadata.ColumnPath.resolve(entityClass, field.path());
                    if (field.options().operation() == ContextFilterOptions.Operation.PERIOD
                            && path.getResolvedType() != org.ipro.metadata.annotation.FieldType.DATE
                            && path.getResolvedType() != org.ipro.metadata.annotation.FieldType.DATETIME)
                        throw new IllegalArgumentException("Period requires a date or datetime field");
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException(source + ": filter '" + field.path() + "': " + invalid.getMessage(), invalid);
                }
            }
            formRegistry.registerContextFilters(entityClass, java.util.List.copyOf(fields.values()), source);

            variants.getContextFilterRows().forEach((variant, rowFields) ->
                formRegistry.registerVariantContextFilters(entityClass,
                    org.ipro.form.registry.FormType.LIST, variant, rowFields, source));

            variants.getCustomizers().forEach((variant, customizerList) ->
                customizerList.forEach(customizer ->
                    formRegistry.addListCustomizer(entityClass, variant, customizer)));
        }
    }
}

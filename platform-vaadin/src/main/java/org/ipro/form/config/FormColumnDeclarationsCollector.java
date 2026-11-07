package org.ipro.form.config;

import org.ipro.metadata.FormColumnDeclarations;
import org.ipro.metadata.annotation.FormConfigurationFor;
import org.ipro.metadata.annotation.ListColumns;
import org.ipro.metadata.annotation.SelectionColumns;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.Arrays;
import java.util.List;

/** Reads bean types, never constructs configurations or evaluates their configure methods. */
final class FormColumnDeclarationsCollector implements BeanFactoryPostProcessor, BeanPostProcessor {
    private FormColumnDeclarations declarations;
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        declarations = new FormColumnDeclarations();
        String[] names = beanFactory.getBeanDefinitionNames();
        Arrays.sort(names);
        for (String name : names) {
            if (beanFactory.containsBeanDefinition("scopedTarget." + name)) continue;
            Class<?> type = beanFactory.getType(name, false);
            if (type == null) continue;
            type = org.springframework.util.ClassUtils.getUserClass(type);
            ListColumns[] list = type.getAnnotationsByType(ListColumns.class);
            SelectionColumns[] selection = type.getAnnotationsByType(SelectionColumns.class);
            if (list.length == 0 && selection.length == 0) continue;
            FormConfigurationFor binding = type.getAnnotation(FormConfigurationFor.class);
            String source = type.getName() + " (bean '" + name + "')";
            if (binding == null) throw new IllegalStateException("Form columns on " + source
                + " require @FormConfigurationFor");
            for (ListColumns columns : list) {
                declarations.register(new FormColumnDeclarations.Declaration(binding.value(),
                    FormColumnDeclarations.Kind.LIST, columns.variant(), List.of(columns.value()), "", source,
                    properties(columns.properties())));
            }
            for (SelectionColumns columns : selection) {
                declarations.register(new FormColumnDeclarations.Declaration(binding.value(),
                    FormColumnDeclarations.Kind.SELECTION, columns.variant(), List.of(columns.value()),
                    columns.title(), source, properties(columns.properties())));
            }
        }
        declarations.freeze();
        beanFactory.registerSingleton("formColumnDeclarations", declarations);
    }

    private static List<org.ipro.metadata.ColumnPath.Spec> properties(ListColumns.Column[] properties) {
        return Arrays.stream(properties).map(property -> new org.ipro.metadata.ColumnPath.Spec(
            property.path(), property.label().isEmpty() ? null : property.label(),
            property.width().isEmpty() ? null : property.width(),
            property.flexGrow() == -1 ? null : property.flexGrow(),
            switch (property.inlineFilter()) {
                case INHERIT -> null;
                case ENABLED -> true;
                case DISABLED -> false;
            })).toList();
    }

    /** A factory returning Object/an interface must not silently hide annotated configuration. */
    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        Class<?> type = org.springframework.util.ClassUtils.getUserClass(bean);
        FormConfigurationFor binding = type.getAnnotation(FormConfigurationFor.class);
        for (ListColumns columns : type.getAnnotationsByType(ListColumns.class)) {
            verifyCollected(binding, FormColumnDeclarations.Kind.LIST, columns.variant(), type);
        }
        for (SelectionColumns columns : type.getAnnotationsByType(SelectionColumns.class)) {
            verifyCollected(binding, FormColumnDeclarations.Kind.SELECTION, columns.variant(), type);
        }
        if (binding != null) {
            if (bean instanceof org.ipro.form.builder.ListFormCustomization list
                    && list.entityClass() != binding.value()
                || bean instanceof org.ipro.form.builder.SelectionFormCustomization selection
                    && selection.entityClass() != binding.value()) {
                throw new IllegalStateException("@FormConfigurationFor conflicts with entityClass() on " + type.getName());
            }
        }
        return bean;
    }

    private void verifyCollected(FormConfigurationFor binding, FormColumnDeclarations.Kind kind,
                                 String variant, Class<?> type) {
        var found = binding == null ? null : declarations.find(binding.value(), kind, variant);
        if (found == null || !found.source().startsWith(type.getName() + " (bean '")) {
            throw new IllegalStateException("Form columns on " + type.getName()
                + " were not available before metadata resolution; declare the concrete bean return type and @FormConfigurationFor");
        }
    }
}

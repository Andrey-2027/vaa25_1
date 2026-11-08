package com.example.generated.harness;

import java.util.List;
import org.ipro.metadata.FormColumnDeclarations;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.FormConfigurationFor;
import org.ipro.metadata.annotation.ListColumns;
import org.ipro.metadata.annotation.SelectionColumns;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Backend C adapter for static declarations; production UI uses FormColumnDeclarationsCollector. */
@Configuration(proxyBeanMethods = false)
class HarnessFormColumnsConfiguration {
    @Bean static BeanFactoryPostProcessor formColumns() {
        return factory -> {
            var declarations = new FormColumnDeclarations();
            for (String name : factory.getBeanDefinitionNames()) {
                Class<?> type = factory.getType(name, false);
                if (type == null) continue;
                var binding = type.getAnnotation(FormConfigurationFor.class);
                if (binding == null) continue;
                for (var columns : type.getAnnotationsByType(ListColumns.class))
                    declarations.register(new FormColumnDeclarations.Declaration(binding.value(), FormColumnDeclarations.Kind.LIST,
                        columns.variant(), List.of(columns.value()), "", type.getName(), List.of()));
                for (var columns : type.getAnnotationsByType(SelectionColumns.class))
                    declarations.register(new FormColumnDeclarations.Declaration(binding.value(), FormColumnDeclarations.Kind.SELECTION,
                        columns.variant(), List.of(columns.value()), columns.title(), type.getName(), List.of()));
            }
            declarations.freeze();
            factory.registerSingleton("harnessFormColumnDeclarations", declarations);
        };
    }
    @Bean static BeanPostProcessor attachFormColumns(FormColumnDeclarations declarations) {
        return new BeanPostProcessor() {
            @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                if (bean instanceof MetadataResolver resolver) resolver.setFormColumnDeclarations(declarations);
                return bean;
            }
        };
    }
}

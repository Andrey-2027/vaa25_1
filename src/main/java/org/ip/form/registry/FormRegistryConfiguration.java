package org.ip.form.registry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация реестра форм.
 *
 * Создаёт Spring-бин FormRegistry, который используется для регистрации
 * кастомных вариантов форм.
 *
 * Для регистрации собственных форм создайте свой @Configuration класс
 * и добавьте регистрации через @Bean метод, принимающий FormRegistry:
 *
 * <pre>
 * {@code @Configuration}
 * public class CustomFormsConfiguration {
 *     {@code @Bean}
 *     public void registerCustomForms(FormRegistry registry) {
 *         registry.registerListForm(
 *             Nomenclature.class,
 *             "archived",
 *             context -> new ArchivedNomenclatureListForm(context)
 *         );
 *     }
 * }
 * </pre>
 */
@Configuration
public class FormRegistryConfiguration {

    @Bean
    public FormRegistry formRegistry() {
        return new FormRegistry();
    }
}

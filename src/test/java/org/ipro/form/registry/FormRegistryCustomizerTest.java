package org.ipro.form.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты хранилища поведенческих кастомайзеров (Этап 2): порядок добавления,
 * разделение default/вариант, очистка.
 */
class FormRegistryCustomizerTest {

    @Test
    void listCustomizersKeepInsertionOrder() {
        FormRegistry registry = new FormRegistry();
        org.ipro.form.builder.ListFormCustomizer first = (form, ctx) -> {
        };
        org.ipro.form.builder.ListFormCustomizer second = (form, ctx) -> {
        };

        assertThat(registry.getListCustomizers(String.class, null)).isEmpty();

        registry.addListCustomizer(String.class, null, first);
        registry.addListCustomizer(String.class, null, second);

        assertThat(registry.getListCustomizers(String.class, null))
            .containsExactly(first, second);
        assertThat(registry.getListCustomizers(String.class, "v")).isEmpty();
    }

    @Test
    void itemCustomizersSeparatedByVariant() {
        FormRegistry registry = new FormRegistry();
        org.ipro.form.builder.ItemFormCustomizer customizer = (form, ctx) -> {
        };

        registry.addItemCustomizer(String.class, "v", customizer);

        assertThat(registry.getItemCustomizers(String.class, "v")).containsExactly(customizer);
        assertThat(registry.getItemCustomizers(String.class, null)).isEmpty();

        registry.clear();

        assertThat(registry.getItemCustomizers(String.class, "v")).isEmpty();
        assertThat(registry.getListCustomizers(String.class, null)).isEmpty();
    }
}

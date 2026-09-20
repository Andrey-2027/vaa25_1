package org.ipro.form.builder;

import org.ipro.form.registry.FormRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты регистрации кастомайзеров через конфиги сущностей (Этап 2): коллекторы
 * Variants складывают default + вариантные, регистраторы разносят по реестру.
 */
class CustomizerRegistrarTest {

    @Test
    void listCustomizersRegisteredPerVariant() {
        FormRegistry registry = new FormRegistry();
        ListFormCustomizer def = (form, ctx) -> {
        };
        ListFormCustomizer named = (form, ctx) -> {
        };
        ListFormCustomization config = new ListFormCustomization() {
            @Override
            public Class<?> entityClass() {
                return String.class;
            }

            @Override
            public void configure(ListFormVariants variants) {
                variants.customizeDefault(def);
                variants.customize("v", named);
            }
        };
        new ListFormCustomizationRegistrar(registry, List.of(config)).afterPropertiesSet();

        assertThat(registry.getListCustomizers(String.class, null)).containsExactly(def);
        assertThat(registry.getListCustomizers(String.class, "v")).containsExactly(named);
    }

    @Test
    void itemCustomizersRegisteredPerVariant() {
        FormRegistry registry = new FormRegistry();
        ItemFormCustomizer def = (form, ctx) -> {
        };
        ItemFormCustomization config = new ItemFormCustomization() {
            @Override
            public Class<?> entityClass() {
                return String.class;
            }

            @Override
            public void configure(ItemFormVariants variants) {
                variants.customizeDefault(def);
            }
        };
        new ItemFormCustomizationRegistrar(registry, List.of(config)).afterPropertiesSet();

        assertThat(registry.getItemCustomizers(String.class, null)).containsExactly(def);
        assertThat(registry.getItemCustomizers(String.class, "v")).isEmpty();
    }
}

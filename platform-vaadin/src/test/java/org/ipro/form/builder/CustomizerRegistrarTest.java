package org.ipro.form.builder;

import org.ipro.form.action.CrudAction;
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
    void listCommandLayoutsRegisteredPerVariant() {
        FormRegistry registry = new FormRegistry();
        ListFormCustomization config = new ListFormCustomization() {
            @Override
            public Class<?> entityClass() {
                return String.class;
            }

            @Override
            public void configure(ListFormVariants variants) {
                variants.commands(commands -> commands
                    .commands(CrudAction.CREATE.id())
                    .panel(CrudAction.CREATE.id()));
                variants.commands("v", commands -> commands.allOnPanel());
            }
        };

        new ListFormCustomizationRegistrar(registry, List.of(config)).afterPropertiesSet();

        ListCommandLayout defaults = registry.getListCommandLayout(String.class, null);
        ListCommandLayout variant = registry.getListCommandLayout(String.class, "v");
        assertThat(defaults).isNotNull();
        assertThat(defaults.composition()).isEqualTo(ListCommandLayout.Composition.EXPLICIT);
        assertThat(defaults.panelMode()).isEqualTo(ListCommandLayout.PanelMode.EXPLICIT);
        assertThat(variant).isNotNull();
        assertThat(variant.panelMode()).isEqualTo(ListCommandLayout.PanelMode.ALL);
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

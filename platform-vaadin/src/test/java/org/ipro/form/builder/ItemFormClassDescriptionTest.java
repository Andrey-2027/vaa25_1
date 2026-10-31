package org.ipro.form.builder;

import com.vaadin.flow.component.html.Div;
import org.ipro.form.registry.FormRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ItemFormClassDescriptionTest {
    @Test
    void registrarCarriesTheOptionalClassWithoutConstructingAForm() {
        var registry = new FormRegistry();
        ItemFormCustomization config = new ItemFormCustomization() {
            public Class<?> entityClass() { return String.class; }
            public void configure(ItemFormVariants variants) {
                variants.addDefault(Div.class, context -> { throw new AssertionError("Factory must not run"); });
                variants.add("legacy", context -> { throw new AssertionError("Factory must not run"); });
            }
        };
        new ItemFormCustomizationRegistrar(registry, List.of(config)).afterPropertiesSet();
        assertThat(registry.registrationsOf(String.class)).hasSize(2);
        assertThat(registry.registrationsOf(String.class).getFirst().declaredClass()).isEqualTo(Div.class);
        assertThat(registry.registrationsOf(String.class).get(1).declaredClass()).isNull();
        assertThatThrownBy(() -> registry.registerItemForm(String.class, (String) null, c -> new Div(), "duplicate", Div.class))
            .isInstanceOf(IllegalStateException.class);
    }
}

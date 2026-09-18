package org.ipro.form.registry;

import org.ip.model.Nomenclature;
import org.ip.model.Workshop;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D3.5.3: повторная регистрация одного ключа — fail-fast, а не тихое last-wins.
 */
class FormRegistryDuplicateTest {

    @Test
    void duplicateFactoryRegistrationFailsFast() {
        FormRegistry registry = new FormRegistry();
        registry.registerItemForm(Nomenclature.class, null, ctx -> null, "first");

        assertThatThrownBy(() -> registry.registerItemForm(Nomenclature.class, null, ctx -> null, "second"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат регистрации формы")
            .hasMessageContaining("first")
            .hasMessageContaining("second");
    }

    @Test
    void differentVariantsDoNotClash() {
        FormRegistry registry = new FormRegistry();
        registry.registerItemForm(Nomenclature.class, null, ctx -> null, "first");
        registry.registerItemForm(Nomenclature.class, "compact", ctx -> null, "second");
        registry.registerItemForm(Workshop.class, null, ctx -> null, "third");
    }

    @Test
    void duplicateListFormViewFailsFast() {
        FormRegistry registry = new FormRegistry();
        registry.registerListFormView(Nomenclature.class, null, ctx -> null, "first");

        assertThatThrownBy(() -> registry.registerListFormView(Nomenclature.class, null, ctx -> null, "second"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат регистрации составного ListForm-view");
    }
}

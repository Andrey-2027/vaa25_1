package org.ip.form;

import org.ip.form.registry.FormFactory;
import org.ip.form.registry.FormRegistry;
import org.ip.form.registry.FormType;
import org.ip.model.PrdSpecMtr;
import org.ip.views.forms.PrdSpecMtrVariant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты перехода «enum↔string» в {@link FormRegistry} (PR-1.4): регистрация варианта
 * перечислением использует тот же строковый ключ, что и {@code PrdSpecMtrVariant.key()}.
 */
class FormRegistryEnumVariantTest {

    @Test
    void registerItemFormByEnumUsesEnumKey() {
        FormRegistry registry = new FormRegistry();
        FormFactory factory = ctx -> null;

        registry.registerItemForm(PrdSpecMtr.class, PrdSpecMtrVariant.PRODUCT, factory);

        assertThat(registry.find(PrdSpecMtr.class, FormType.ITEM, "product")).isSameAs(factory);
        assertThat(registry.find(PrdSpecMtr.class, FormType.ITEM, "PRODUCT")).isNull();
        assertThat(registry.has(PrdSpecMtr.class, FormType.ITEM, PrdSpecMtrVariant.PRODUCT.key()))
            .isTrue();
    }

    @Test
    void registerListFormByEnumUsesEnumKey() {
        FormRegistry registry = new FormRegistry();
        FormFactory factory = ctx -> null;

        registry.registerListForm(PrdSpecMtr.class, PrdSpecMtrVariant.MATERIAL, factory);

        assertThat(registry.find(PrdSpecMtr.class, FormType.LIST, "material")).isSameAs(factory);
    }
}

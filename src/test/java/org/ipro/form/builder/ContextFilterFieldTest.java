package org.ipro.form.builder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты маркера видимости: по умолчанию поле действует только там, где объявлено,
 * {@code allListVariants()} расширяет на все списки и диалог.
 */
class ContextFilterFieldTest {

    @Test
    void defaultIsLocalOnly() {
        ContextFilterField field = ContextFilterField.auto("code", "Код");

        assertThat(field.allLists()).isFalse();
        assertThat(field.required()).isFalse();
    }

    @Test
    void allListVariantsPreservesRest() {
        ContextFilterField field =
            ContextFilterField.requiredSelect("journal", "Журнал", String.class).allListVariants();

        assertThat(field.path()).isEqualTo("journal");
        assertThat(field.label()).isEqualTo("Журнал");
        assertThat(field.control()).isEqualTo(ContextFilterControl.SELECT);
        assertThat(field.lookupSource()).isEqualTo(String.class);
        assertThat(field.required()).isTrue();
        assertThat(field.allLists()).isTrue();
    }
}

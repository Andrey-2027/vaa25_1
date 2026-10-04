package org.ip.model;

import org.ip.register.RegisterMetadataValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0013 §3: реальная сущность-пилот проходит стартовую валидацию регистра.
 * Проверка без Spring — fail-fast подключение к старту приложения отдельным шагом.
 */
class NomenclaturePriceRegisterTest {

    @Test
    void nomenclaturePriceDeclaresAValidRegister() {
        assertThat(RegisterMetadataValidator.validate(NomenclaturePrice.class))
            .as("нарушения декларации регистра ЦеныНоменклатуры")
            .isEmpty();
    }
}

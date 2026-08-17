package org.ip.views.forms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты {@link PrdSpecMtrVariant} (PR-1.4): переход «enum↔string» и резолв дискриминатора
 * typeMtr — единый источник ключей «material»/«product» для селектора строки и регистрации
 * вариантов формы.
 */
class PrdSpecMtrVariantTest {

    @Test
    void keyMatchesRegistryEnumToStringTransition() {
        assertThat(PrdSpecMtrVariant.MATERIAL.key()).isEqualTo("material");
        assertThat(PrdSpecMtrVariant.PRODUCT.key()).isEqualTo("product");
    }

    @Test
    void ofStringResolvesKnownKeys() {
        assertThat(PrdSpecMtrVariant.of("material")).isEqualTo(PrdSpecMtrVariant.MATERIAL);
        assertThat(PrdSpecMtrVariant.of("product")).isEqualTo(PrdSpecMtrVariant.PRODUCT);
    }

    @Test
    void ofStringRejectsUnknownKey() {
        assertThatThrownBy(() -> PrdSpecMtrVariant.of("archive"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("archive");
    }

    @Test
    void ofDiscriminatorMapsNullAndZeroToMaterialAndOneToProduct() {
        assertThat(PrdSpecMtrVariant.of((Integer) null)).isEqualTo(PrdSpecMtrVariant.MATERIAL);
        assertThat(PrdSpecMtrVariant.of(Integer.valueOf(0))).isEqualTo(PrdSpecMtrVariant.MATERIAL);
        assertThat(PrdSpecMtrVariant.of(Integer.valueOf(1))).isEqualTo(PrdSpecMtrVariant.PRODUCT);
    }

    @Test
    void ofDiscriminatorRejectsUnknownValue() {
        assertThatThrownBy(() -> PrdSpecMtrVariant.of(Integer.valueOf(2)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("typeMtr");
    }
}

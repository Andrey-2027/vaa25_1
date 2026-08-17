package org.ip.form;

import org.ip.metadata.FieldMetadataInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты {@link BindingDescriptor} (PR-1.1): default label = key, валидация key,
 * round-trip из FieldMetadataInfo.
 */
class BindingDescriptorTest {

    @Test
    void bindingDescriptorRoundTrip() {
        FieldMetadataInfo meta = mock(FieldMetadataInfo.class);
        when(meta.getName()).thenReturn("code");
        when(meta.getLabel()).thenReturn("Код");
        when(meta.isRequired()).thenReturn(true);

        BindingDescriptor descriptor = BindingDescriptor.from(meta);

        assertThat(descriptor.key()).isEqualTo("code");
        assertThat(descriptor.label()).isEqualTo("Код");
        assertThat(descriptor.required()).isTrue();
    }

    @Test
    void nullLabelDefaultsToKey() {
        BindingDescriptor descriptor = new BindingDescriptor("sku", null, false);

        assertThat(descriptor.label()).isEqualTo("sku");
    }

    @Test
    void blankKeyIsRejected() {
        assertThatThrownBy(() -> new BindingDescriptor("  ", "Код", false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BindingDescriptor(null, "Код", false))
            .isInstanceOf(NullPointerException.class);
    }
}

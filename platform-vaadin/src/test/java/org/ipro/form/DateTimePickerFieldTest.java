package org.ipro.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.RequiredMode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DateTimePickerFieldTest {

    @Test
    void fieldFactoryAndBindingRoundTripMilliseconds() throws Exception {
        FieldMetadataInfo metadata = metadata(RequiredEntry.class, "period");
        FormBindingRegistry bindings = new FormBindingRegistry();
        Component component = new FieldFactory(null, null).createField(metadata, bindings);
        assertThat(component).isInstanceOf(DateTimePicker.class);

        DateTimePicker field = (DateTimePicker) component;
        LocalDateTime original = LocalDateTime.of(2026, 10, 6, 14, 5, 6, 123_000_000);
        RequiredEntry entity = new RequiredEntry();
        entity.period = original;

        bindings.readAllFromEntity(entity);

        assertThat(field.getValue()).isEqualTo(original);
        assertThat(field.getStep()).isEqualTo(java.time.Duration.ofMillis(1));

        field.setValue(LocalDateTime.of(2026, 10, 6, 14, 5, 7, 987_000_000));
        bindings.applyAllToEntity(entity);

        assertThat(entity.period)
            .isEqualTo(LocalDateTime.of(2026, 10, 6, 14, 5, 7, 987_000_000));
    }

    @Test
    void invalidTimeBlocksSaveEvenWhenFieldIsOptional() throws Exception {
        FieldMetadataInfo metadata = metadata(OptionalEntry.class, "period");
        FormBindingRegistry bindings = new FormBindingRegistry();
        DateTimePicker field = (DateTimePicker)
            new FieldFactory(null, null).createField(metadata, bindings);

        field.setErrorMessage("Некорректное время.");
        field.setInvalid(true);

        assertThat(field.isInvalid()).isTrue();
        assertThat(bindings.isValid()).isFalse();
        assertThat(bindings.validate())
            .containsExactly("Период: Некорректное время.");
    }

    private static FieldMetadataInfo metadata(Class<?> entityType, String name) throws Exception {
        Field field = entityType.getDeclaredField(name);
        return new FieldMetadataInfo(field, field.getAnnotation(FieldMetadata.class));
    }

    private static class RequiredEntry {

        @FieldMetadata(label = "Период", required = RequiredMode.REQUIRED)
        private LocalDateTime period;
    }

    private static class OptionalEntry {

        @FieldMetadata(label = "Период")
        private LocalDateTime period;
    }
}

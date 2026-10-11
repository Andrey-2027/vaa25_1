package org.ipro.form;

import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.annotation.FieldMetadata;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class FieldFactoryLongBindingTest {
    static class Values {
        @FieldMetadata(label = "Long") Long value;
        @FieldMetadata(label = "Integer") Integer integer;
    }
    private final FieldFactory factory = new FieldFactory(null, null);
    private FieldMetadataInfo metadata(String name) throws Exception {
        var field = Values.class.getDeclaredField(name);
        return new FieldMetadataInfo(field, field.getAnnotation(FieldMetadata.class));
    }
    @Test void longRoundTripsFullRangeAndNullWithoutPrecisionLoss() throws Exception {
        var bindings = new FormBindingRegistry();
        var field = (BigDecimalField) factory.createField(metadata("value"), bindings);
        var entity = new Values();
        for (Long value : new Long[]{Long.MIN_VALUE, 1L, 2L, Long.MAX_VALUE, null}) {
            entity.value = value; bindings.readAllFromEntity(entity);
            assertThat(field.getValue()).isEqualTo(value == null ? null : BigDecimal.valueOf(value));
            var target = new Values(); bindings.applyAllToEntity(target);
            assertThat(target.value).isEqualTo(value);
        }
    }
    @Test void longRejectsFractionAndOverflowRatherThanTruncating() throws Exception {
        var bindings = new FormBindingRegistry();
        var field = (BigDecimalField) factory.createField(metadata("value"), bindings);
        for (String invalid : new String[]{"1.5", "9223372036854775808", "-9223372036854775809"}) {
            field.setValue(new BigDecimal(invalid));
            assertThatThrownBy(() -> bindings.applyAllToEntity(new Values())).isInstanceOf(ArithmeticException.class);
        }
    }
    @Test void integerStillUsesIntegerField() throws Exception {
        var bindings = new FormBindingRegistry();
        var field = (IntegerField) factory.createField(metadata("integer"), bindings);
        field.setValue(123); var entity = new Values(); bindings.applyAllToEntity(entity);
        assertThat(entity.integer).isEqualTo(123);
        bindings.setReadOnly(true); assertThat(field.isReadOnly()).isTrue();
    }
}

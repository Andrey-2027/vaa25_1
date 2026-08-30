package org.ipro.filter;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterValueCodecTest {
    enum Status { ACTIVE, ARCHIVED }

    @Test
    void decodesNumericDateBooleanAndEnumValues() {
        var number = field(BigDecimal.class, FilterDataType.NUMBER);
        assertThat(FilterValueCodec.decode("12.50", number)).isEqualTo(new BigDecimal("12.50"));
        assertThat(FilterValueCodec.decode("2026-08-28", field(LocalDate.class, FilterDataType.DATE)))
                .isEqualTo(LocalDate.of(2026, 8, 28));
        assertThat(FilterValueCodec.decode("true", field(boolean.class, FilterDataType.BOOLEAN))).isEqualTo(true);
        assertThat(FilterValueCodec.decode("ACTIVE", field(Status.class, FilterDataType.ENUM))).isEqualTo(Status.ACTIVE);
    }

    @Test
    void decodesNonEmptyInList() {
        assertThat(FilterValueCodec.decodeList("1, 2,3", field(Integer.class, FilterDataType.NUMBER)))
                .containsExactly(1, 2, 3);
    }

    @Test
    void rejectsInvalidTypedValue() {
        assertThatThrownBy(() -> FilterValueCodec.decode("not-a-number", field(Integer.class, FilterDataType.NUMBER)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterValueCodec.decode("yes", field(Boolean.class, FilterDataType.BOOLEAN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterValueCodec.decodeList("1,,3", field(Integer.class, FilterDataType.NUMBER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустое");
        assertThatThrownBy(() -> FilterValueCodec.decode("17", field(Object.class, FilterDataType.ENTITY_REFERENCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("Для ссылочного поля требуется typed lookup/resolver: field");
    }

    private static FilterFieldResolver.ResolvedFilterField field(Class<?> type, FilterDataType dataType) {
        return new FilterFieldResolver.ResolvedFilterField("field", "Поле", type, dataType, true);
    }
}

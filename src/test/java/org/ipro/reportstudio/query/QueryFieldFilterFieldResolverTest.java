package org.ipro.reportstudio.query;

import org.ipro.filter.FilterDataType;
import org.ipro.reportstudio.data.QueryField;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryFieldFilterFieldResolverTest {
    enum Status { ACTIVE, ARCHIVED }

    @Test
    void exposesSupportedFieldsWithCaptionsAndTypes() {
        QueryFieldFilterFieldResolver resolver = new QueryFieldFilterFieldResolver(List.of(
                new QueryField("code", "", String.class, "Код", true, false, false),
                new QueryField("amount", "", BigDecimal.class, "Сумма", true, false, true),
                new QueryField("created", "", LocalDate.class, "Дата", true, false, false),
                new QueryField("enabled", "", Boolean.class, "Активен", true, false, false),
                new QueryField("status", "", Status.class, "Статус", true, false, false)));

        assertThat(resolver.fields()).extracting("path")
                .containsExactly("code", "amount", "created", "enabled", "status");
        assertThat(resolver.resolve("amount").dataType()).isEqualTo(FilterDataType.NUMBER);
        assertThat(resolver.resolve("created").dataType()).isEqualTo(FilterDataType.DATE);
        assertThat(resolver.resolve("enabled").dataType()).isEqualTo(FilterDataType.BOOLEAN);
        assertThat(resolver.resolve("status").dataType()).isEqualTo(FilterDataType.ENUM);
        assertThat(resolver.resolve("code").label()).isEqualTo("Код");
    }

    @Test
    void providesEnumConstantsAndRejectsUnknownFields() {
        QueryFieldFilterFieldResolver resolver = new QueryFieldFilterFieldResolver(List.of(
                QueryField.scalar("status", Status.class)));

        List<?> options = resolver.valueOptions(resolver.resolve("status"));
        assertThat(options).hasSize(2);
        assertThat(options.get(0)).isEqualTo(Status.ACTIVE);
        assertThat(options.get(1)).isEqualTo(Status.ARCHIVED);
        assertThatThrownBy(() -> resolver.resolve("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void excludesTechnicalAndUnknownObjectFields() {
        QueryFieldFilterFieldResolver resolver = new QueryFieldFilterFieldResolver(List.of(
                QueryField.scalar("__reportstudio_row_marker", Long.class),
                QueryField.scalar("unknown", Object.class)));

        assertThat(resolver.fields()).isEmpty();
        assertThatThrownBy(() -> resolver.resolve("__reportstudio_row_marker"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

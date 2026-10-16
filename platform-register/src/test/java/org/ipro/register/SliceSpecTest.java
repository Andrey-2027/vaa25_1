package org.ipro.register;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SliceSpecTest {
    @Test void compositeJoinColumnsCannotBeAdmittedAsASingleRegisterKeyColumn() {
        assertThatThrownBy(() -> RegisterDescriptor.describe(CompositeReference.class))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("KEY_COLUMN_NAME");
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(uniqueConstraints = @jakarta.persistence.UniqueConstraint(columnNames = {"item_a", "item_b", "period_date"}))
    @RegisterMetadata(dimensions="item", period="period", resources="value")
    static class CompositeReference {
        @jakarta.persistence.Id Long id;
        @jakarta.persistence.ManyToOne
        @jakarta.persistence.JoinColumns({
            @jakarta.persistence.JoinColumn(name="item_a", referencedColumnName="part_a", nullable=false, updatable=false),
            @jakarta.persistence.JoinColumn(name="item_b", referencedColumnName="part_b", nullable=false, updatable=false)}) CompositeItem item;
        @jakarta.persistence.Column(name="period_date", nullable=false, updatable=false) LocalDateTime period;
        int value;
    }
    @jakarta.persistence.Entity static class CompositeItem { @jakarta.persistence.EmbeddedId CompositeKey id; }
    @jakarta.persistence.Embeddable
    record CompositeKey(@jakarta.persistence.Column(name="part_a") Long a,
                        @jakarta.persistence.Column(name="part_b") Long b) implements java.io.Serializable { }

    @Test void copiesConditionsAndRejectsMissingDate() {
        var conditions = new ArrayList<>(List.of(new SliceSpec.Condition("code", SliceSpec.Operator.EQ, "A")));
        var spec = new SliceSpec(String.class, SliceSpec.Mode.LATEST, LocalDateTime.of(2026, 1, 1, 0, 0), conditions, List.of());
        conditions.clear();
        assertThat(spec.dimensions()).hasSize(1);
        assertThatThrownBy(() -> spec.dimensions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> SliceSpec.latest(String.class, null)).isInstanceOf(NullPointerException.class);
    }
    @Test void rejectsMutableValuesEntityInstancesAndQueryPaths() {
        for (Object value : List.of(new java.util.Date(), new ArrayList<>(), new Object(), Double.NaN))
            assertThatThrownBy(() -> new SliceSpec.Condition("code", SliceSpec.Operator.EQ, value))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SLICE_VALUE_TYPE");
        for (String field : List.of("p.code", "code OR 1=1", ""))
            assertThatThrownBy(() -> new SliceSpec.Condition(field, SliceSpec.Operator.EQ, "A"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SLICE_FIELD");
        for (String value : List.of(":runtime", "@subquery:other", " "))
            assertThatThrownBy(() -> new SliceSpec.Condition("code", SliceSpec.Operator.EQ, value))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SLICE_LITERAL");
    }
}

package org.ipro.register;

import org.ip.model.NomenclaturePrice;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisterListFilterPhasesTest {
    private final RegisterDescriptor descriptor = RegisterDescriptor.describe(NomenclaturePrice.class);
    private static FilterNode condition(String path) {
        return FilterConditionNode.of(new FilterCondition(path, FilterOperator.EQ, "1", null, FilterDataType.NUMBER));
    }
    @Test void dimensionsIncludingNestedPathsAreBeforeAndOtherColumnsAfter() {
        var phases = RegisterListFilterPhases.split(descriptor,
            FilterGroup.and(condition("nomenclature.id"), condition("price"), condition("period")));
        assertThat(((FilterGroup) phases.dimensions()).children()).hasSize(1);
        assertThat(((FilterGroup) phases.afterSlice()).children()).hasSize(2);
    }
    @Test void mixedOrIsRejectedEvenInsideAnd() {
        assertThatThrownBy(() -> RegisterListFilterPhases.split(descriptor,
            FilterGroup.and(condition("period"), FilterGroup.or(condition("nomenclature"), condition("price")))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ИЛИ").hasMessageContaining("историю");
    }
    @Test void orWithinOnePhaseAndEmptyTreeAreSupported() {
        var phases = RegisterListFilterPhases.split(descriptor, FilterGroup.or(condition("price"), condition("period")));
        assertThat(phases.dimensions()).isNull();
        assertThat(phases.afterSlice()).isInstanceOf(FilterGroup.class);
        assertThat(RegisterListFilterPhases.split(descriptor, null).dimensions()).isNull();
    }
}

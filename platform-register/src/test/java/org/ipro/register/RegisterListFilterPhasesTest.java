package org.ipro.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-R S5(в): фазы листового отбора делят условия по декларации регистра, а не по прикладной
 * сущности. Раньше тест описывал прикладной «Цены номенклатуры»; здесь тот же предмет объявлен
 * модульной фикстурой — ссылочное измерение, ресурс и период. Прикладные типы ушли, проверяемое
 * свойство осталось: вложенный путь измерения ({@code category.id}) относится к измерениям, а
 * имя измерения ({@code category}) — тем более, тогда как ресурс и период идут после среза.
 */
class RegisterListFilterPhasesTest {

    private final RegisterDescriptor descriptor = RegisterDescriptor.describe(NestedDimensionRegister.class);

    private static FilterNode condition(String path) {
        return FilterConditionNode.of(new FilterCondition(path, FilterOperator.EQ, "1", null, FilterDataType.NUMBER));
    }

    @Test
    void dimensionsIncludingNestedPathsAreBeforeAndOtherColumnsAfter() {
        var phases = RegisterListFilterPhases.split(descriptor,
            FilterGroup.and(condition("category.id"), condition("price"), condition("period")));
        assertThat(((FilterGroup) phases.dimensions()).children()).hasSize(1);
        assertThat(((FilterGroup) phases.afterSlice()).children()).hasSize(2);
    }

    @Test
    void mixedOrIsRejectedEvenInsideAnd() {
        assertThatThrownBy(() -> RegisterListFilterPhases.split(descriptor,
            FilterGroup.and(condition("period"), FilterGroup.or(condition("category"), condition("price")))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ИЛИ").hasMessageContaining("историю");
    }

    @Test
    void orWithinOnePhaseAndEmptyTreeAreSupported() {
        var phases = RegisterListFilterPhases.split(descriptor, FilterGroup.or(condition("price"), condition("period")));
        assertThat(phases.dimensions()).isNull();
        assertThat(phases.afterSlice()).isInstanceOf(FilterGroup.class);
        assertThat(RegisterListFilterPhases.split(descriptor, null).dimensions()).isNull();
    }

    // ---- фикстуры ----------------------------------------------------------------------

    /** Периодический регистр со ссылочным измерением: путь отбора может продолжаться в ссылку. */
    @Entity
    @Table(name = "phases_nested_register", uniqueConstraints =
        @UniqueConstraint(name = "uk_phases_nested_register_key",
            columnNames = {"category_id", "period_date"}))
    @RegisterMetadata(dimensions = "category", period = "period", resources = "price")
    static class NestedDimensionRegister {
        @Id
        Long id;
        @NotNull
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "category_id", nullable = false, updatable = false)
        NestedDimensionCategory category;
        @NotNull
        @Column(name = "period_date", nullable = false, updatable = false)
        LocalDateTime period;
        @NotNull
        @Column(name = "price", nullable = false)
        BigDecimal price;
    }

    /** Справочник измерения: сам регистром не является и в его декларации не участвует. */
    @Entity
    @Table(name = "phases_nested_category")
    static class NestedDimensionCategory {
        @Id
        Long id;
        @NotNull
        @Column(name = "code", nullable = false, updatable = false)
        String code;
    }
}

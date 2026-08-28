package org.ipro.filter;

import org.ipro.metadata.FilterSpec;

/** Миграция текущего формата FilterSpec к типизированному контракту. */
public final class FilterConditionCodec {
    private FilterConditionCodec() {
    }

    public static FilterCondition fromLegacy(FilterSpec spec, FilterDataType type) {
        if (spec == null) {
            throw new IllegalArgumentException("FilterSpec обязателен");
        }
        FilterOperator operator = switch (spec.mode() == null ? "" : spec.mode()) {
            case "EQUALS" -> FilterOperator.EQ;
            case "STARTS_WITH" -> FilterOperator.STARTS_WITH;
            case "ENDS_WITH" -> FilterOperator.CONTAINS;
            case "CONTAINS" -> FilterOperator.CONTAINS;
            default -> legacyDateOperator(spec);
        };
        return new FilterCondition(spec.path(), operator, spec.value(), spec.valueTo(), type);
    }

    private static FilterOperator legacyDateOperator(FilterSpec spec) {
        if (spec.value() == null && spec.valueTo() == null) {
            return FilterOperator.IS_NULL;
        }
        return spec.valueTo() == null ? FilterOperator.EQ : FilterOperator.BETWEEN;
    }
}

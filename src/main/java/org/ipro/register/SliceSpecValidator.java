package org.ipro.register;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Shared phase/type contract; executors supply verified comparison types for reference identities. */
public final class SliceSpecValidator {
    private SliceSpecValidator() { }

    public static void validate(RegisterDescriptor descriptor, SliceSpec spec, Map<String, Class<?>> dimensionTypes) {
        Map<String, Class<?>> resourceTypes = new LinkedHashMap<>();
        descriptor.resources().forEach(field -> resourceTypes.put(field.name(), field.javaType()));
        validate(descriptor, spec, dimensionTypes, resourceTypes);
    }

    /** Executors may compare a verified reference resource by its persistent identity. */
    public static void validate(RegisterDescriptor descriptor, SliceSpec spec, Map<String, Class<?>> dimensionTypes,
                                Map<String, Class<?>> resourceTypes) {
        if (descriptor.entityType() != spec.registerType())
            throw new IllegalArgumentException("SLICE_TYPE: спецификация относится к другому регистру");
        if (!descriptor.periodic())
            throw new IllegalArgumentException("SLICE_PERIOD: LATEST требует периодический регистр");
        if (!dimensionTypes.keySet().equals(java.util.Set.copyOf(descriptor.dimensionNames())))
            throw new IllegalArgumentException("SLICE_MAPPING: неполный набор типов сравнения измерений");
        var recorderIntent = spec.recorderReadIntent();
        if (recorderIntent.scope() == SliceSpec.RecorderReadIntent.Scope.SELECTED_RECORDERS) {
            if (!descriptor.recorded())
                throw new IllegalArgumentException("SLICE_RECORDER_INTENT: выбор регистратора допустим только для подчинённого регистра");
            for (RecorderRef recorder : recorderIntent.selectedRecorders()) {
                if (!descriptor.recorderTypeCodes().contains(recorder.getTypeCode()))
                    throw new IllegalArgumentException("SLICE_RECORDER_TYPE: тип регистратора не разрешён регистром: "
                        + recorder.getTypeCode());
            }
        }
        Map<String, Class<?>> afterTypes = new LinkedHashMap<>();
        if (!resourceTypes.keySet().equals(descriptor.resources().stream().map(RegisterDescriptor.FieldDescriptor::name)
                .collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("SLICE_MAPPING: неполный набор типов сравнения ресурсов");
        afterTypes.putAll(resourceTypes);
        var period = descriptor.period().orElseThrow();
        afterTypes.put(period.name(), period.javaType());
        for (var condition : spec.dimensions()) check(condition, dimensionTypes, "измерений");
        for (var condition : spec.afterSlice()) check(condition, afterTypes, "после среза");
        // Reference dimensions support equality by identity, never ordering of an entity.
        for (var dimension : descriptor.dimensions()) {
            if (dimension.mapping() == RegisterDescriptor.Mapping.TO_ONE)
                spec.dimensions().stream().filter(condition -> condition.field().equals(dimension.name()))
                    .forEach(condition -> requireEquality(condition));
        }
        descriptor.resources().stream().filter(field -> field.mapping() == RegisterDescriptor.Mapping.TO_ONE)
            .forEach(field -> spec.afterSlice().stream().filter(condition -> condition.field().equals(field.name()))
                .forEach(SliceSpecValidator::requireEquality));
    }

    private static void check(SliceSpec.Condition condition, Map<String, Class<?>> fields, String phase) {
        Class<?> type = fields.get(condition.field());
        if (type == null)
            throw new IllegalArgumentException("SLICE_PHASE_FIELD: поле '" + condition.field() + "' не разрешено в отборе " + phase);
        type = boxed(type);
        if (!supportedType(type) || !type.isInstance(condition.value()))
            throw new IllegalArgumentException("SLICE_VALUE_TYPE: поле '" + condition.field() + "' требует " + type.getName());
        if (!Number.class.isAssignableFrom(type) && type != LocalDate.class && type != LocalDateTime.class) requireEquality(condition);
    }

    private static void requireEquality(SliceSpec.Condition condition) {
        if (condition.operator() != SliceSpec.Operator.EQ && condition.operator() != SliceSpec.Operator.NE)
            throw new IllegalArgumentException("SLICE_OPERATOR: поле '" + condition.field() + "' поддерживает EQ/NE");
    }

    public static boolean supportedType(Class<?> type) {
        type = boxed(type);
        return type == String.class || type == Long.class || type == Integer.class || type == Short.class
            || type == Double.class || type == Float.class || type == BigDecimal.class || type == Boolean.class
            || type == LocalDate.class || type == LocalDateTime.class || type == UUID.class || type.isEnum();
    }

    public static Class<?> boxed(Class<?> type) {
        if (type == long.class) return Long.class;
        if (type == int.class) return Integer.class;
        if (type == short.class) return Short.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        return type;
    }
}

package org.ipro.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable snapshot of register row, slice-group, recorder, and temporal contracts. */
public final class RegisterDescriptor {
    public enum Mapping { BASIC, TO_ONE, OTHER }

    /** Column name is absent when the declaration leaves resource naming to the JPA naming strategy. */
    public record FieldDescriptor(String name, Class<?> javaType, Optional<String> columnName, Mapping mapping) {
        public FieldDescriptor {
            Objects.requireNonNull(name);
            Objects.requireNonNull(javaType);
            Objects.requireNonNull(columnName);
            Objects.requireNonNull(mapping);
        }
    }

    private final Class<?> entityType;
    private final String entityName;
    private final List<FieldDescriptor> dimensions;
    private final Optional<FieldDescriptor> period;
    private final List<FieldDescriptor> resources;
    private final boolean recorded;
    private final List<Class<?>> recorderTypes;
    private final List<String> recorderTypeCodes;
    private final Optional<FieldDescriptor> recorder;
    private final Optional<FieldDescriptor> lineNumber;
    private final Optional<FieldDescriptor> recordingOrder;
    private final List<String> dimensionGroupColumns;
    private final List<String> rowKeyColumns;
    private final List<String> winnerOrderFields;
    private final RegisterPeriodicity periodicity;

    private RegisterDescriptor(Class<?> type, RegisterMetadata metadata, Map<Class<?>, String> resolvedRecorderCodes) {
        entityType = type;
        periodicity = metadata.periodicity();
        String declaredName = type.getAnnotation(Entity.class).name();
        entityName = declaredName.isBlank() ? type.getSimpleName() : declaredName;
        dimensions = Arrays.stream(metadata.dimensions()).map(name -> field(type, name)).toList();
        period = metadata.period().isBlank() ? Optional.empty() : Optional.of(field(type, metadata.period()));
        resources = Arrays.stream(metadata.resources()).map(name -> field(type, name)).toList();
        recorded = RecordedRegisterEntry.class.isAssignableFrom(type);
        recorderTypes = List.copyOf(Arrays.asList(metadata.recorderTypes()));
        recorderTypeCodes = recorderTypes.stream().map(recorderType -> {
            String code = resolvedRecorderCodes.get(recorderType);
            if (code == null) {
                RecorderType declaration = recorderType.getAnnotation(RecorderType.class);
                if (declaration == null) {
                    throw new IllegalStateException("Validated recorder type lost @RecorderType: "
                        + recorderType.getName());
                }
                code = declaration.value();
            }
            return code;
        }).toList();
        recorder = recorded ? Optional.of(field(type, "recorder")) : Optional.empty();
        lineNumber = recorded ? Optional.of(field(type, "lineNumber")) : Optional.empty();
        recordingOrder = recorded ? Optional.of(field(type, "recordingOrder")) : Optional.empty();

        dimensionGroupColumns = dimensions.stream().map(value -> value.columnName().orElseThrow()).toList();
        var rowColumns = new ArrayList<String>();
        if (recorded) {
            rowColumns.add("recorder_type");
            rowColumns.add("recorder_id");
            rowColumns.add(lineNumber.orElseThrow().columnName().orElseThrow());
        } else {
            rowColumns.addAll(dimensionGroupColumns);
            period.ifPresent(value -> rowColumns.add(value.columnName().orElseThrow()));
        }
        rowKeyColumns = List.copyOf(rowColumns);

        var order = new ArrayList<String>();
        period.ifPresent(value -> order.add(value.name()));
        if (recorded && period.isPresent()) {
            order.add(recordingOrder.orElseThrow().name());
            order.add(lineNumber.orElseThrow().name());
        }
        winnerOrderFields = List.copyOf(order);
    }

    public static RegisterDescriptor describe(Class<?> type) {
        Objects.requireNonNull(type, "type");
        var violations = RegisterMetadataValidator.validate(type);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Декларация регистра " + type.getSimpleName() + " нарушена: "
                + violations.stream().map(value -> value.code() + " — " + value.message()).toList());
        }
        return validated(type, Map.of());
    }

    static RegisterDescriptor validated(Class<?> type) {
        return validated(type, Map.of());
    }

    static RegisterDescriptor validated(Class<?> type, Map<Class<?>, String> resolvedRecorderCodes) {
        return new RegisterDescriptor(type, type.getAnnotation(RegisterMetadata.class), resolvedRecorderCodes);
    }

    public Class<?> entityType() { return entityType; }
    public String entityName() { return entityName; }
    public List<FieldDescriptor> dimensions() { return dimensions; }
    public List<String> dimensionNames() { return dimensions.stream().map(FieldDescriptor::name).toList(); }
    public Optional<FieldDescriptor> period() { return period; }
    public boolean periodic() { return period.isPresent(); }
    public RegisterPeriodicity periodicity() { return periodicity; }
    public List<FieldDescriptor> resources() { return resources; }

    /** True when this register's rows are owned by a document recorder. */
    public boolean recorded() { return recorded; }

    /** Business dimensions used to group latest-value slices; never includes recorder identity. */
    public List<String> dimensionGroupColumns() { return dimensionGroupColumns; }

    /** Actual database uniqueness key for one stored row. */
    public List<String> rowKeyColumns() { return rowKeyColumns; }

    /** Generic entity create/update/delete is reserved for independent registers. */
    public boolean genericWritesAllowed() { return !recorded; }

    /** Explicit register declaration; entities outside RegisterRegistry never acquire this capability. */
    public boolean keyChangeAllowed() { return entityType.getAnnotation(RegisterMetadata.class).keyChangeAllowed(); }

    /** Stable-code declarations allowed to own rows of this register. */
    public List<Class<?>> recorderTypes() { return recorderTypes; }
    public List<String> recorderTypeCodes() { return recorderTypeCodes; }
    public Optional<FieldDescriptor> recorder() { return recorder; }
    public Optional<FieldDescriptor> lineNumber() { return lineNumber; }
    public Optional<FieldDescriptor> recordingOrder() { return recordingOrder; }

    /** Candidate winner order, in priority order; the winner is the maximum lexicographic tuple. */
    public List<String> winnerOrderFields() { return winnerOrderFields; }

    /**
     * Compatibility view of the v1 key contract. A recorder-owned row has a different row key and
     * dimension group, so callers must choose explicitly between {@link #rowKeyColumns()} and
     * {@link #dimensionGroupColumns()}.
     */
    @Deprecated(forRemoval = false)
    public List<String> keyColumns() {
        if (recorded) {
            throw new IllegalStateException("Для подчинённого регистра используйте rowKeyColumns() и dimensionGroupColumns()");
        }
        return rowKeyColumns;
    }

    private static FieldDescriptor field(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                var column = RegisterMetadataValidator.effectiveColumn(type, field);
                var join = field.getAnnotation(JoinColumn.class);
                String columnName = join != null ? join.name() : column == null ? "" : column.name();
                Mapping mapping = field.isAnnotationPresent(ManyToOne.class)
                    || field.isAnnotationPresent(OneToOne.class) ? Mapping.TO_ONE
                    : field.isAnnotationPresent(jakarta.persistence.Embedded.class)
                    || field.isAnnotationPresent(jakarta.persistence.ElementCollection.class)
                    || field.isAnnotationPresent(jakarta.persistence.OneToMany.class)
                    || field.isAnnotationPresent(jakarta.persistence.ManyToMany.class)
                    ? Mapping.OTHER : Mapping.BASIC;
                return new FieldDescriptor(name, field.getType(),
                    columnName.isBlank() ? Optional.empty() : Optional.of(columnName), mapping);
            } catch (NoSuchFieldException ignored) {
                // Validation has already checked persistence and access; inherited fields are supported.
            }
        }
        throw new IllegalStateException("Validated register field disappeared: " + type.getName() + "." + name);
    }
}

package org.ipro.register;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;

/** Neutral, immutable read intent. All conditions are AND; values are literals, never query text. */
public record SliceSpec(Class<?> registerType, Mode mode, LocalDateTime asOf,
                        List<Condition> dimensions, List<Condition> afterSlice,
                        RecorderReadIntent recorderReadIntent) {
    public enum Mode { LATEST }
    public enum Operator { EQ, NE, GT, GE, LT, LE }

    /** Explicit choice of the candidate population used to calculate a recorded slice. */
    public record RecorderReadIntent(Scope scope, List<RecorderRef> selectedRecorders) {
        public enum Scope { ALL_RECORDERS, SELECTED_RECORDERS }

        public RecorderReadIntent {
            Objects.requireNonNull(scope, "scope");
            selectedRecorders = List.copyOf(Objects.requireNonNull(selectedRecorders, "selectedRecorders"));
            if (scope == Scope.ALL_RECORDERS && !selectedRecorders.isEmpty())
                throw new IllegalArgumentException("SLICE_RECORDER_INTENT: all-recorders scope cannot include recorder references");
            if (scope == Scope.SELECTED_RECORDERS && selectedRecorders.isEmpty())
                throw new IllegalArgumentException("SLICE_RECORDER_INTENT: selected-recorders scope requires at least one reference");
            if (new HashSet<>(selectedRecorders).size() != selectedRecorders.size())
                throw new IllegalArgumentException("SLICE_RECORDER_INTENT: duplicate recorder reference");
        }

        public static RecorderReadIntent allRecorders() {
            return new RecorderReadIntent(Scope.ALL_RECORDERS, List.of());
        }

        public static RecorderReadIntent selectedRecorders(List<RecorderRef> recorders) {
            return new RecorderReadIntent(Scope.SELECTED_RECORDERS, recorders);
        }
    }

    /** Source-compatible constructor: existing calls mean the full recorder population. */
    public SliceSpec(Class<?> registerType, Mode mode, LocalDateTime asOf,
                     List<Condition> dimensions, List<Condition> afterSlice) {
        this(registerType, mode, asOf, dimensions, afterSlice, RecorderReadIntent.allRecorders());
    }

    public SliceSpec {
        Objects.requireNonNull(registerType, "registerType");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(asOf, "asOf");
        asOf = RegisterPeriodicity.EXACT.normalize(asOf);
        dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
        afterSlice = List.copyOf(Objects.requireNonNull(afterSlice, "afterSlice"));
        Objects.requireNonNull(recorderReadIntent, "recorderReadIntent");
    }

    public static SliceSpec latest(Class<?> type, LocalDateTime asOf) {
        return new SliceSpec(type, Mode.LATEST, asOf, List.of(), List.of(), RecorderReadIntent.allRecorders());
    }

    /** Calculates latest values after restricting candidates to the exact recorder identities. */
    public static SliceSpec latestAmongRecorders(Class<?> type, LocalDateTime asOf,
                                                  List<RecorderRef> recorders) {
        return new SliceSpec(type, Mode.LATEST, asOf, List.of(), List.of(),
            RecorderReadIntent.selectedRecorders(recorders));
    }

    public record Condition(String field, Operator operator, Object value) {
        public Condition {
            if (field == null || !field.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("SLICE_FIELD: требуется имя поля, а не путь или HQL");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(value, "value");
            if (!SliceSpecValidator.supportedType(value instanceof Enum<?> enumeration ? enumeration.getDeclaringClass() : value.getClass()))
                throw new IllegalArgumentException("SLICE_VALUE_TYPE: нужен неизменяемый scalar или persistent id");
            if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                    || value instanceof Float floatValue && !Float.isFinite(floatValue))
                throw new IllegalArgumentException("SLICE_VALUE_TYPE: число должно быть конечным");
            if (value instanceof String text && (text.isBlank() || text.startsWith(":") || text.startsWith("@subquery:")))
                throw new IllegalArgumentException("SLICE_LITERAL: пустые значения и маркеры параметров/подзапросов не поддерживаются");
        }
    }
}

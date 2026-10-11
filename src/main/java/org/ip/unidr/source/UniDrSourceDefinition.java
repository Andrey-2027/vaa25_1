package org.ip.unidr.source;

import org.ip.unidr.model.UniDrDefinition.Aggregation;
import org.ip.unidr.model.UniDrDefinition.Operator;
import org.ip.unidr.model.UniDrDefinition.ValueType;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Server-owned field contract. Query construction/execution belongs to U2. */
public record UniDrSourceDefinition(String id, int version, String title, String rowMeaning,
                                    List<Field> fields, List<SourceParameter> parameters,
                                    int maxGroups) {
    public UniDrSourceDefinition {
        Objects.requireNonNull(id);
        fields = List.copyOf(fields);
        parameters = List.copyOf(parameters);
        if (version < 1 || maxGroups < 0 || maxGroups > 3) throw new IllegalArgumentException("Invalid source limits");
        if (fields.stream().map(Field::id).distinct().count() != fields.size()
            || parameters.stream().map(SourceParameter::id).distinct().count() != parameters.size())
            throw new IllegalArgumentException("Duplicate source field/parameter");
        for (SourceParameter parameter : parameters) {
            Field field = fields.stream().filter(candidate -> candidate.id().equals(parameter.fieldId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown source parameter field"));
            if (!field.operators().contains(parameter.operator())) throw new IllegalArgumentException("Invalid source parameter operator");
        }
    }

    public record Field(String id, String caption, ValueType type, String valueAlias,
                        String groupKeyAlias, List<String> orderPaths, String referenceDomain, String unitFieldId,
                        boolean detail, Set<Aggregation> aggregations, Set<Operator> operators) {
        public Field {
            Objects.requireNonNull(id);
            Objects.requireNonNull(type);
            orderPaths = List.copyOf(orderPaths);
            aggregations = Set.copyOf(aggregations);
            operators = Set.copyOf(operators);
            if (type == ValueType.REFERENCE && (referenceDomain == null || groupKeyAlias == null))
                throw new IllegalArgumentException("Reference field requires an identity/domain");
        }
        public boolean groupable() { return groupKeyAlias != null; }
        public boolean sortable() { return !orderPaths.isEmpty(); }
    }

    public record SourceParameter(String id, String fieldId, Operator operator, Requirement requirement) {}
    public enum Requirement { ALWAYS, NUMERIC_QUANTITY, OPTIONAL }
}

package org.ip.unidr.query;

import org.ip.unidr.model.UniDrDefinition;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.model.UniDrDefinitionValidator;
import org.ip.unidr.source.UniDrSourceCatalog;
import org.ip.unidr.source.UniDrSourceDefinition;
import org.ipro.reportstudio.dom.*;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.query.ReportQueryAssembler;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Allow-listed HQL and typed bindings → the existing guarded assembler. No persisted UDR template. */
public final class UniDrQueryAdapter {
    private static final String SOURCE = """
        select i.id as rowId, d.id as documentId, d.number as documentNumber, d.date as date,
               r.id as receivingWorkshopId, r.name as receivingWorkshopName,
               t.id as transferringWorkshopId, t.name as transferringWorkshopName,
               n.id as nomenclatureId, n.code as nomenclatureCode, n.name as nomenclatureName,
               u.id as unitId, u.shortCode as unit, i.quantity as quantity
        from ReceivingDocumentItem i
        join ReceivingDocument d on i.document.id = d.id
        join Workshop r on d.receivingWorkshop.id = r.id
        join Workshop t on d.transferringWorkshop.id = t.id
        join Nomenclature n on i.nomenclature.id = n.id
        join UnitOfMeasurement u on n.unitOfMeasurement.id = u.id
        join Journal j on d.journal.id = j.id
        """;
    private static final Map<String, String> PATHS = Map.of("date", "d.date", "document", "d.id",
        "receivingWorkshop", "r.id", "transferringWorkshop", "t.id", "nomenclature", "n.id", "unit", "u.id", "quantity", "i.quantity");
    private final UniDrSourceCatalog catalog;
    private final UniDrDefinitionValidator validator;
    private final ReportQueryAssemblyService assembly;

    public UniDrQueryAdapter(UniDrSourceCatalog catalog, UniDrDefinitionValidator validator, ReportQueryAssemblyService assembly) {
        this.catalog = catalog; this.validator = validator; this.assembly = assembly;
    }

    public ReportQueryAssembler assemble(UniDrDefinition definition, Map<String, Literal> resolved, ReportContext context) {
        validator.requireValid(definition);
        List<org.ip.unidr.model.UniDrValidationException.Issue> errors = new ArrayList<>();
        for (String id : resolved.keySet()) {
            if (definition.parameters().stream().noneMatch(parameter -> parameter.id().equals(id)))
                throw new IllegalArgumentException("Параметр не объявлен");
            errors.addAll(validator.validateParameterValue(definition, id, resolved.get(id)));
        }
        for (Parameter parameter : definition.parameters())
            if (validator.parameterRequired(definition, parameter.id()) && !resolved.containsKey(parameter.id()))
                errors.add(new org.ip.unidr.model.UniDrValidationException.Issue(
                    org.ip.unidr.model.UniDrValidationException.Code.REQUIRED, "values." + parameter.id(), "Заполните параметр"));
        if (!errors.isEmpty()) throw new org.ip.unidr.model.UniDrValidationException(errors);
        if (!"receiving-items".equals(definition.sourceId())) throw new IllegalArgumentException("Исполнение источника не подключено");
        UniDrSourceDefinition source = catalog.find(definition.sourceId()).orElseThrow();
        var template = new ReportTemplate(); template.setName("UniDR");
        List<String> conditions = new ArrayList<>(); Map<String, Object> bindings = new LinkedHashMap<>();
        for (var parameter : source.parameters()) {
            Literal value = resolved.get(parameter.id());
            if (value != null) condition(parameter.fieldId(), parameter.operator(), value, conditions, bindings);
        }
        for (Filter filter : definition.filters()) {
            Literal value = filter.value() instanceof Literal literal ? literal : resolved.get(((ParameterRef) filter.value()).parameterId());
            if (value == null) throw new IllegalArgumentException("Не заполнен параметр отбора");
            condition(filter.fieldId(), filter.operator(), value, conditions, bindings);
        }
        template.setJpql(SOURCE + " where " + String.join(" and ", conditions));
        for (String name : bindings.keySet()) {
            var parameter = new ReportParam(); parameter.setName(name); parameter.setKind(ReportParamKind.SCALAR);
            parameter.setRequired(true); template.addParam(parameter);
        }
        Map<String, Direction> ordering = new LinkedHashMap<>();
        for (String group : definition.groups()) field(source, group).orderPaths().forEach(path -> ordering.putIfAbsent(path, Direction.ASC));
        for (Sort sort : definition.sorts()) field(source, sort.fieldId()).orderPaths().forEach(path -> ordering.putIfAbsent(path, sort.direction()));
        ordering.putIfAbsent("i.id", Direction.ASC);
        ordering.forEach((path, direction) -> {
            var order = new ReportOrder(); order.setColumnName(path); order.setDirection(ReportOrderDirection.valueOf(direction.name()));
            order.setPosition(template.getOrders().size()); template.addOrder(order);
        });
        return assembly.assemble(template, context, bindings);
    }

    private UniDrSourceDefinition.Field field(UniDrSourceDefinition source, String id) {
        return source.fields().stream().filter(field -> field.id().equals(id)).findFirst().orElseThrow();
    }
    private void condition(String fieldId, Operator operator, Literal value, List<String> conditions, Map<String, Object> bindings) {
        String path = Objects.requireNonNull(PATHS.get(fieldId), "Unknown source field");
        List<String> names = new ArrayList<>();
        for (String text : value.values()) {
            String name = "unidr_" + bindings.size(); names.add(":" + name);
            bindings.put(name, switch (value.type()) {
                case DATE -> LocalDate.parse(text);
                case DECIMAL -> new BigDecimal(text);
                case REFERENCE -> Long.valueOf(text);
                case TEXT -> text;
                case COUNT -> throw new IllegalArgumentException("Счётчик не является отбором");
            });
        }
        String expression = operator == Operator.BETWEEN ? path + " between " + names.getFirst() + " and " + names.getLast()
            : path + " " + switch (operator) {case EQ -> "="; case NE -> "<>"; case GT -> ">"; case GE -> ">="; case LT -> "<"; case LE -> "<="; default -> throw new IllegalArgumentException();} + " " + names.getFirst();
        conditions.add("(" + expression + ")");
    }
}

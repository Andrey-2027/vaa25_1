package org.ip.unidr.query;

import org.ipro.unidr.model.UniDrDefinition;
import org.ipro.unidr.model.UniDrDefinition.Direction;
import org.ipro.unidr.model.UniDrDefinition.Filter;
import org.ipro.unidr.model.UniDrDefinition.Literal;
import org.ipro.unidr.model.UniDrDefinition.Parameter;
import org.ipro.unidr.model.UniDrDefinition.ParameterRef;
import org.ipro.unidr.model.UniDrDefinition.Sort;
import org.ipro.unidr.model.UniDrDefinition.ValueType;
import org.ipro.unidr.model.UniDrDefinition.Operator;
import org.ipro.unidr.model.UniDrDefinitionValidator;
import org.ipro.unidr.model.UniDrValidationException;
import org.ipro.unidr.model.UniDrValidationException.Code;
import org.ipro.unidr.model.UniDrValidationException.Issue;
import org.ipro.unidr.query.UniDrFilterSupport;
import org.ipro.unidr.query.UniDrReferenceCatalog;
import org.ipro.unidr.query.UniDrRunQueryAdapter;
import org.ipro.unidr.query.UniDrSourcePlan;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.ipro.unidr.source.UniDrSourceDefinition;
import org.ipro.query.JpqlParameters;
import org.ipro.query.ReportContext;
import org.ipro.query.ReportQueryAssembler;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QuerySort;
import org.ipro.query.model.QuerySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Allow-listed HQL and typed bindings → the existing guarded assembler. No persisted UDR template. */
public final class UniDrQueryAdapter implements UniDrRunQueryAdapter {
    private static final Map<String, Class<?>> SCHEMA = Map.ofEntries(
        Map.entry("rowId", Long.class), Map.entry("documentId", Long.class), Map.entry("documentNumber", String.class),
        Map.entry("date", LocalDate.class), Map.entry("receivingWorkshopId", Long.class), Map.entry("receivingWorkshopName", String.class),
        Map.entry("transferringWorkshopId", Long.class), Map.entry("transferringWorkshopName", String.class),
        Map.entry("nomenclatureId", Long.class), Map.entry("nomenclatureCode", String.class), Map.entry("nomenclatureName", String.class),
        Map.entry("unitId", Long.class), Map.entry("unit", String.class), Map.entry("quantity", BigDecimal.class));
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
    private final UniDrReferenceCatalog references;
    @Override public Map<String,Class<?>> expectedSchema(){return SCHEMA;}
    public static UniDrSourcePlan plan(UniDrSourceDefinition source,UniDrReferenceCatalog references) {
        var projections=new LinkedHashMap<String,UniDrSourcePlan.Projection>();
        for(var field:source.fields())if(PATHS.containsKey(field.id())) {
            Class<?> type=field.type()==ValueType.REFERENCE?references.referenceType(field.id()):field.type()==ValueType.DATE?LocalDate.class:field.type()==ValueType.DECIMAL?BigDecimal.class:String.class;
            projections.put(field.id(),new UniDrSourcePlan.Projection(field.id(),PATHS.get(field.id()),type,
                field.type()==ValueType.REFERENCE?type.getName():null,field.groupKeyAlias(),true));
        }
        return new UniDrSourcePlan(null,source,projections,List.of());
    }
    @Override
    public ReportQueryAssembler applyFilters(ReportQueryAssembler query,UniDrDefinition definition,Map<String,Object> values,
                                             java.util.function.BiConsumer<String,Long> checkReference) {
        var filter=UniDrFilterSupport.compile(definition.filterTree(),plan(catalog.resolve(definition).orElseThrow(),references),definition.parameterDeclarations(),values,
            JpqlParameters.names(query.jpql()),checkReference,references);
        return filter.predicate()==null?query:assembly.appendPredicate(query,filter.predicate(),filter.bindings());
    }

    public UniDrQueryAdapter(UniDrSourceCatalog catalog, UniDrDefinitionValidator validator, ReportQueryAssemblyService assembly,
                             UniDrReferenceCatalog references) {
        this.catalog = catalog; this.validator = validator; this.assembly = assembly; this.references = references;
    }

    @Override
    public ReportQueryAssembler assemble(UniDrDefinition definition, Map<String, Literal> resolved, ReportContext context) {
        validator.requireValid(definition);
        List<Issue> errors = new ArrayList<>();
        for (String id : resolved.keySet()) {
            if (definition.parameters().stream().noneMatch(parameter -> parameter.id().equals(id)))
                throw new IllegalArgumentException("Параметр не объявлен");
            errors.addAll(validator.validateParameterValue(definition, id, resolved.get(id)));
        }
        for (Parameter parameter : definition.parameters())
            if (validator.parameterRequired(definition, parameter.id()) && !resolved.containsKey(parameter.id()))
                errors.add(new Issue(
                    Code.REQUIRED, "values." + parameter.id(), "Заполните параметр"));
        if (!errors.isEmpty()) throw new UniDrValidationException(errors);
        if (!"receiving-items".equals(definition.sourceId())) throw new IllegalArgumentException("Исполнение источника не подключено");
        UniDrSourceDefinition source = catalog.find(definition.sourceId()).orElseThrow();
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
        List<QueryParameter> parameters = bindings.keySet().stream()
            .map(QueryParameter::required)
            .toList();
        Map<String, Direction> ordering = new LinkedHashMap<>();
        for (String group : definition.groups()) field(source, group).orderPaths().forEach(path -> ordering.putIfAbsent(path, Direction.ASC));
        for (Sort sort : definition.sorts()) field(source, sort.fieldId()).orderPaths().forEach(path -> ordering.putIfAbsent(path, sort.direction()));
        ordering.putIfAbsent("i.id", Direction.ASC);
        List<QuerySort> sorts = ordering.entrySet().stream()
            .map(entry -> new QuerySort(entry.getKey(), entry.getValue() == Direction.DESC))
            .toList();
        var query = new QueryDefinition(SOURCE + " where " + String.join(" and ", conditions),
            QuerySource.MANUAL, null, null, parameters);
        return assembly.assemble(query, context, bindings, List.of(), sorts);
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

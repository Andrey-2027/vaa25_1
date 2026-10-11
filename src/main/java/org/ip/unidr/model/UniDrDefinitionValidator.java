package org.ip.unidr.model;

import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.model.UniDrValidationException.Issue;
import org.ip.unidr.source.UniDrSourceCatalog;
import org.ip.unidr.source.UniDrSourceDefinition;
import org.ip.unidr.source.UniDrSourceDefinition.Field;
import org.ip.unidr.source.UniDrSourceDefinition.Requirement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.ip.unidr.model.UniDrValidationException.Code.*;

/** Validates recipes against server metadata; entity visibility and run values are checked in U2. */
public final class UniDrDefinitionValidator {
    private static final int MAX_ITEMS = 20;
    private final UniDrSourceCatalog catalog;

    public UniDrDefinitionValidator(UniDrSourceCatalog catalog) { this.catalog = Objects.requireNonNull(catalog); }

    public UniDrDefinition requireValid(UniDrDefinition definition) {
        List<Issue> issues = validate(definition);
        if (!issues.isEmpty()) throw new UniDrValidationException(issues);
        return definition;
    }

    public List<Issue> validate(UniDrDefinition definition) {
        List<Issue> issues = new ArrayList<>();
        if (definition == null) return List.of(new Issue(REQUIRED, "$", "Настройка отчёта не задана"));
        if (definition.schemaVersion() != UniDrDefinition.SCHEMA_VERSION)
            issues.add(new Issue(UNSUPPORTED_SCHEMA, "schemaVersion", "Неподдерживаемая версия формата"));
        var found = catalog.resolve(definition);
        if (found.isEmpty()) {
            issues.add(new Issue(UNKNOWN_SOURCE, "sourceId", "Источник отчёта не найден"));
            return List.copyOf(issues);
        }
        UniDrSourceDefinition source = found.orElseThrow();
        if (definition.sourceVersion() != source.version()) {
            issues.add(new Issue(INCOMPATIBLE_SOURCE, "sourceVersion", "Настройка создана для другой версии источника"));
            return List.copyOf(issues);
        }
        title(definition.title(), "title", true, issues);
        if (definition.mode() == null) issues.add(new Issue(REQUIRED, "mode", "Выберите представление отчёта"));
        Map<String, Field> fields = source.fields().stream().collect(Collectors.toMap(Field::id, Function.identity()));
        limits("computedColumns",definition.computedColumns().size(),10,issues);
        for(int index=0;index<definition.computedColumns().size();index++) {
            var column=definition.computedColumns().get(index);String path="computedColumns["+index+"]";
            title(column.caption(),path+".caption",true,issues);
            if(column.id()==null || !column.id().matches("calc_[a-zA-Z0-9_]{1,50}") || fields.containsKey(column.id())
                || source.fields().stream().anyMatch(field->Objects.equals(field.valueAlias(),column.id()) || Objects.equals(field.groupKeyAlias(),column.id())))
                issues.add(new Issue(INVALID_COLUMN,path+".id","Имя вычисляемой колонки некорректно или уже занято"));
            else fields.put(column.id(),UniDrComputedColumns.field(column));
            try { UniDrComputedColumns.validate(column,source); }
            catch(IllegalArgumentException invalid) {issues.add(new Issue(INVALID_VALUE,path+".expression",invalid.getMessage()));}
        }
        definition.fieldStyles().forEach((id,style)->{
            String path="fieldStyles."+id;Field field=field(fields,id,path,issues);
            if(style.width()!=null && (style.width()<20 || style.width()>2000)) issues.add(new Issue(INVALID_VALUE,path+".width","Ширина должна быть от 20 до 2000"));
            if(style.format()!=null) {
                try {
                    if(style.format().isBlank() || style.format().length()>100)throw new IllegalArgumentException();
                    if(field!=null && field.type()==ValueType.DATE)java.time.format.DateTimeFormatter.ofPattern(style.format(),Locale.forLanguageTag("ru-RU")).format(LocalDate.of(2026,10,10));
                    else if(field!=null && (field.type()==ValueType.DECIMAL || field.type()==ValueType.COUNT))new java.text.DecimalFormat(style.format()).format(BigDecimal.ONE);
                    else throw new IllegalArgumentException();
                } catch(IllegalArgumentException | java.time.DateTimeException invalid) {issues.add(new Issue(INVALID_VALUE,path+".format","Некорректный формат числа или даты"));}
            }
        });
        Map<String, Parameter> parameters = new HashMap<>();
        Set<String> referenced = definition.filters().stream().map(Filter::value)
            .filter(ParameterRef.class::isInstance).map(ParameterRef.class::cast)
            .map(ParameterRef::parameterId).collect(Collectors.toSet());
        Set<String> builtIns = source.parameters().stream().map(UniDrSourceDefinition.SourceParameter::id).collect(Collectors.toSet());

        limits("columns", definition.columns().size(), definition.query()==null?MAX_ITEMS:30, issues);
        Set<String> selected = new HashSet<>();
        for (int index = 0; index < definition.columns().size(); index++) {
            Column column = definition.columns().get(index);
            String path = "columns[" + index + "]";
            Field field = field(fields, column.fieldId(), path + ".fieldId", issues);
            duplicate(selected, column.fieldId(), path + ".fieldId", issues);
            title(column.title(), path + ".title", false, issues);
            if (field != null && !field.detail()) issues.add(new Issue(INVALID_COLUMN, path, "Поле доступно только как показатель"));
        }
        limits("measures", definition.measures().size(), fields.size(), issues);
        selected.clear();
        for (int index = 0; index < definition.measures().size(); index++) {
            Measure measure = definition.measures().get(index);
            String path = "measures[" + index + "]";
            Field field = field(fields, measure.fieldId(), path + ".fieldId", issues);
            duplicate(selected, measure.fieldId(), path + ".fieldId", issues);
            title(measure.title(), path + ".title", false, issues);
            if (field != null && (measure.aggregation() == null || !field.aggregations().contains(measure.aggregation())))
                issues.add(new Issue(INVALID_MEASURE, path + ".aggregation", "Агрегация недоступна для этого поля"));
        }
        limits("groups", definition.groups().size(), source.maxGroups(), issues);
        selected.clear();
        for (int index = 0; index < definition.groups().size(); index++) {
            String path = "groups[" + index + "]";
            Field field = field(fields, definition.groups().get(index), path, issues);
            duplicate(selected, definition.groups().get(index), path, issues);
            if (field != null && !field.groupable()) issues.add(new Issue(INVALID_GROUP, path, "По этому полю нельзя группировать"));
        }
        limits("sorts", definition.sorts().size(), MAX_ITEMS, issues);
        selected.clear();
        for (int index = 0; index < definition.sorts().size(); index++) {
            Sort sort = definition.sorts().get(index);
            String path = "sorts[" + index + "]";
            Field field = field(fields, sort.fieldId(), path + ".fieldId", issues);
            duplicate(selected, sort.fieldId(), path + ".fieldId", issues);
            if (sort.direction() == null || field != null && !field.sortable())
                issues.add(new Issue(INVALID_SORT, path, "Некорректная сортировка"));
        }

        limits("parameters", definition.parameters().size(), MAX_ITEMS, issues);
        for (int index = 0; index < definition.parameters().size(); index++) {
            Parameter parameter = definition.parameters().get(index);
            String path = "parameters[" + index + "]";
            if (parameter.id() == null || !parameter.id().matches("[a-z][a-zA-Z0-9_-]{0,49}"))
                issues.add(new Issue(INVALID_PARAMETER, path + ".id", "Некорректный идентификатор параметра"));
            if (parameters.putIfAbsent(parameter.id(), parameter) != null)
                issues.add(new Issue(DUPLICATE, path + ".id", "Параметр уже объявлен"));
            Field field = field(fields, parameter.fieldId(), path + ".fieldId", issues);
            title(parameter.caption(), path + ".caption", true, issues);
            boolean validOperator = field != null && parameter.operator() != null && field.operators().contains(parameter.operator());
            if (field != null && !validOperator) issues.add(new Issue(INVALID_PARAMETER, path + ".operator", "Недопустимая форма параметра"));
            if (!builtIns.contains(parameter.id()) && !referenced.contains(parameter.id()))
                issues.add(new Issue(INVALID_PARAMETER, path, "Параметр не используется в отборах"));
            if (parameter.defaultRule() == null)
                issues.add(new Issue(REQUIRED, path + ".defaultRule", "Правило начального значения не задано"));
            else if (parameter.defaultRule() == DefaultRule.FIXED) {
                if (parameter.defaultValue() == null) issues.add(new Issue(REQUIRED, path + ".defaultValue", "Задайте начальное значение"));
                else if (validOperator) literal(parameter.defaultValue(), field, parameter.operator(), path + ".defaultValue", issues);
            } else {
                if (parameter.defaultValue() != null)
                    issues.add(new Issue(INVALID_PARAMETER, path + ".defaultValue", "Значение допустимо только для правила FIXED"));
                if (parameter.defaultRule() == DefaultRule.CURRENT_MONTH && (field == null || field.type() != ValueType.DATE
                    || parameter.operator() != Operator.BETWEEN))
                    issues.add(new Issue(INVALID_PARAMETER, path + ".defaultRule", "Текущий месяц применим только к диапазону дат"));
            }
        }
        for (var required : source.parameters()) {
            Parameter parameter = parameters.get(required.id());
            if (parameter == null && isRequired(definition, required.requirement()))
                issues.add(new Issue(MISSING_PARAMETER, "parameters", "Источник требует параметр «" + required.id() + "»"));
            if (parameter != null && (!required.fieldId().equals(parameter.fieldId()) || required.operator() != parameter.operator()))
                issues.add(new Issue(INVALID_PARAMETER, "parameters", "Нельзя изменить назначение параметра «" + required.id() + "»"));
        }

        limits("filters", definition.filters().size(), MAX_ITEMS, issues);
        Set<String> boundFields = source.parameters().stream().filter(parameter -> parameters.containsKey(parameter.id()))
            .map(UniDrSourceDefinition.SourceParameter::fieldId).collect(Collectors.toSet());
        for (int index = 0; index < definition.filters().size(); index++) {
            Filter filter = definition.filters().get(index);
            String path = "filters[" + index + "]";
            Field field = field(fields, filter.fieldId(), path + ".fieldId", issues);
            if (boundFields.contains(filter.fieldId()))
                issues.add(new Issue(CONFLICTING_FILTER, path, "Для этого поля уже настроен параметр источника"));
            if (field == null) continue;
            if (filter.operator() == null || !field.operators().contains(filter.operator())) {
                issues.add(new Issue(INVALID_FILTER, path + ".operator", "Операция отбора недоступна для этого поля"));
                continue;
            }
            if (filter.value() instanceof Literal value) literal(value, field, filter.operator(), path + ".value", issues);
            else if (filter.value() instanceof ParameterRef reference) {
                Parameter parameter = parameters.get(reference.parameterId());
                if (parameter == null) issues.add(new Issue(MISSING_PARAMETER, path + ".value", "Параметр отбора не объявлен"));
                else {
                    Field parameterField = fields.get(parameter.fieldId());
                    if (parameterField == null || parameter.operator() == null || parameterField.type() != field.type()
                        || parameter.operator().arity() != filter.operator().arity()
                        || field.type() == ValueType.REFERENCE && !Objects.equals(parameterField.referenceDomain(), field.referenceDomain()))
                        issues.add(new Issue(TYPE_MISMATCH, path + ".value", "Тип параметра не подходит к условию"));
                }
            } else issues.add(new Issue(REQUIRED, path + ".value", "Задайте значение или параметр отбора"));
        }
        if (definition.mode() == Mode.SUMMARY && definition.measures().isEmpty())
            issues.add(new Issue(MISSING_MEASURE, "measures", "Для режима «Только итоги» нужен хотя бы один показатель"));
        if (definition.mode() == Mode.DETAILS && definition.columns().isEmpty() && definition.measures().isEmpty())
            issues.add(new Issue(MISSING_COLUMNS, "columns", "Выберите поле или показатель"));
        return List.copyOf(issues);
    }

    /** Source parameters may be optional; parameters used by filters are always required. */
    public boolean parameterRequired(UniDrDefinition definition, String id) {
        requireValid(definition);
        if (definition.parameters().stream().noneMatch(parameter -> parameter.id().equals(id)))
            throw new IllegalArgumentException("Параметр не объявлен");
        if (definition.filters().stream().anyMatch(filter -> filter.value() instanceof ParameterRef reference && reference.parameterId().equals(id)))
            return true;
        return catalog.resolve(definition).orElseThrow().parameters().stream()
            .filter(parameter -> parameter.id().equals(id)).anyMatch(parameter -> isRequired(definition, parameter.requirement()));
    }

    /** Same type/range rules for configured constants and values entered for one run. */
    public List<Issue> validateParameterValue(UniDrDefinition definition, String id, Literal value) {
        requireValid(definition);
        Parameter parameter = definition.parameters().stream().filter(item -> item.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Параметр не объявлен"));
        Field field = catalog.resolve(definition).orElseThrow().fields().stream()
            .filter(item -> item.id().equals(parameter.fieldId())).findFirst().orElseThrow();
        List<Issue> issues = new ArrayList<>();
        if (value == null) issues.add(new Issue(REQUIRED, "values." + id, "Задайте значение параметра"));
        else literal(value, field, parameter.operator(), "values." + id, issues);
        return List.copyOf(issues);
    }

    private boolean isRequired(UniDrDefinition definition, Requirement requirement) {
        return requirement == Requirement.ALWAYS || requirement == Requirement.NUMERIC_QUANTITY
            && aggregatesQuantity(definition);
    }
    public static boolean aggregatesQuantity(UniDrDefinition definition) {
        return definition.measures().stream().anyMatch(measure -> "quantity".equals(measure.fieldId())
                || definition.computedColumns().stream().anyMatch(column->column.id().equals(measure.fieldId())
                    && column.kind()==Computation.FORMULA && referencesQuantity(column)));
    }
    private static boolean referencesQuantity(ComputedColumn column) {
        try {return UniDrComputedColumns.references(column).contains("quantity");}
        catch(IllegalArgumentException invalid){return false;}
    }

    private void literal(Literal value, Field field, Operator operator, String path, List<Issue> issues) {
        if (value.type() != field.type()) {
            issues.add(new Issue(TYPE_MISMATCH, path + ".type", "Тип значения не подходит к полю")); return;
        }
        if (value.values().size() != operator.arity()) {
            issues.add(new Issue(INVALID_VALUE, path + ".values", "Неверное число границ/значений")); return;
        }
        try {
            List<Object> parsed = new ArrayList<>();
            for (String text : value.values()) {
                if (text.isBlank() || text.length() > (field.type()==ValueType.TEXT?1000:80)) throw new IllegalArgumentException();
                parsed.add(switch (field.type()) {
                    case DATE -> LocalDate.parse(text);
                    case REFERENCE -> { long id = Long.parseLong(text); if (id <= 0) throw new IllegalArgumentException(); yield id; }
                    case DECIMAL -> {
                        if (!text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) throw new IllegalArgumentException();
                        BigDecimal number = new BigDecimal(text);
                        if (number.precision() > 30 || number.scale() > 12) throw new IllegalArgumentException();
                        yield number;
                    }
                    case COUNT -> throw new IllegalArgumentException();
                    case TEXT -> text;
                });
            }
            if (operator == Operator.BETWEEN && compare(parsed.getFirst(), parsed.getLast()) > 0)
                issues.add(new Issue(INVALID_VALUE, path, "Начало диапазона больше конца"));
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            issues.add(new Issue(INVALID_VALUE, path, "Некорректное значение: нужны ISO-дата, десятичное число или ID ссылки"));
        }
    }

    private int compare(Object left, Object right) {
        return left instanceof LocalDate date ? date.compareTo((LocalDate) right) : left instanceof String text?text.compareTo((String)right):((BigDecimal) left).compareTo((BigDecimal) right);
    }
    private Field field(Map<String, Field> fields, String id, String path, List<Issue> issues) {
        Field field = fields.get(id);
        if (field == null) issues.add(new Issue(UNKNOWN_FIELD, path, "Поле отсутствует в источнике"));
        return field;
    }
    private void duplicate(Set<String> seen, String id, String path, List<Issue> issues) {
        if (!seen.add(id)) issues.add(new Issue(DUPLICATE, path, "Поле уже выбрано"));
    }
    private void limits(String path, int size, int max, List<Issue> issues) {
        if (size > max) issues.add(new Issue(TOO_MANY, path, "Допускается не более " + max + " элементов"));
    }
    private void title(String text, String path, boolean required, List<Issue> issues) {
        if (text == null) { if (required) issues.add(new Issue(REQUIRED, path, "Задайте название")); return; }
        if (text.isBlank() || text.codePointCount(0, text.length()) > 200 || text.chars().anyMatch(Character::isISOControl))
            issues.add(new Issue(INVALID_VALUE, path, "Название должно содержать 1–200 символов без управляющих знаков"));
    }
}

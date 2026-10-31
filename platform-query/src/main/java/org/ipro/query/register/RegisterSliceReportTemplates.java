package org.ipro.query.register;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.register.RegisterRegistry;
import org.ipro.register.SliceSpec;
import org.ipro.query.*;
import org.ipro.query.model.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Supported recipe for an editable report of a reference directory and its latest register values. */
public final class RegisterSliceReportTemplates {
    /** Typed filter metadata from the same register descriptor and report catalog as execution. */
    public record FilterField(String name, String caption, Class<?> javaType, Class<?> referenceType) { }

    /** One editable constructor source; its generated stages remain an implementation detail. */
    public record ConstructorSlice(String name, String registerEntity, String dateParameter, SliceSpec spec,
                                   List<VisualQueryPackage.Cte> stages,
                                   QueryBuilderMetadataCatalog.Entity entity, String settingsJson,
                                   List<SliceParameterFilter> parameterFilters) {
        public ConstructorSlice { stages = List.copyOf(stages); parameterFilters = List.copyOf(parameterFilters); }
        public ConstructorSlice(String name, String registerEntity, String dateParameter, SliceSpec spec,
                                List<VisualQueryPackage.Cte> stages, QueryBuilderMetadataCatalog.Entity entity, String settingsJson) {
            this(name, registerEntity, dateParameter, spec, stages, entity, settingsJson, List.of());
        }
    }

    public ConstructorSlice constructorSlice(SliceSpec spec, String dateParameter, java.util.Set<String> occupied) {
        return constructorSlice(spec, dateParameter, occupied, List.of());
    }

    public ConstructorSlice constructorSlice(SliceSpec spec, String dateParameter, java.util.Set<String> occupied,
                                             List<SliceParameterFilter> parameterFilters) {
        if (dateParameter == null || !dateParameter.matches("[A-Za-z_][A-Za-z0-9_]{0,49}"))
            throw new IllegalArgumentException("Укажите корректное имя параметра даты среза.");
        var source = source(spec.registerType());
        var parameterTypes = new java.util.HashMap<String, Class<?>>(); parameterTypes.put(dateParameter, LocalDateTime.class);
        for (var filter : parameterFilters) {
            var field = sliceFilters(spec.registerType(), filter.afterSlice()).stream().filter(value -> value.name().equals(filter.field())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Поле недоступно в этой фазе: " + filter.field()));
            Class<?> type = field.referenceType() == null ? org.ipro.register.SliceSpecValidator.boxed(field.javaType()) : field.referenceType();
            Class<?> previous = parameterTypes.putIfAbsent(filter.parameter(), type);
            if (previous != null && previous != type) throw new IllegalArgumentException("Несовместимые типы параметра: " + filter.parameter());
        }
        var contextNames = new java.util.LinkedHashSet<>(occupied);
        var fragment = generator.generateParameterized(source, spec,
            new RegisterSliceCteGenerator.NameContext(contextNames), dateParameter, parameterFilters);
        String name = fragment.latestName() + "_source";
        while (contextNames.contains(name)) {
            contextNames.add(fragment.latestName());
            fragment = generator.generateParameterized(source, spec,
                new RegisterSliceCteGenerator.NameContext(contextNames), dateParameter, parameterFilters);
            name = fragment.latestName() + "_source";
        }
        var fields = new ArrayList<QueryBuilderMetadataCatalog.Field>();
        var selections = new ArrayList<VisualQueryDefinition.SelectField>();
        var register = catalog.root(source.descriptor().entityName());
        for (var column : fragment.columns()) {
            String field = column.field().replace('.', '_');
            Class<?> reference = java.util.stream.Stream.concat(source.dimensions().stream(),source.resources().stream()).filter(value -> value.field().equals(column.field()) && value.reference()).map(value -> catalog.root(value.targetEntity()).javaType()).findFirst().orElse(null);
            fields.add(new QueryBuilderMetadataCatalog.Field(field, caption(register, column.field()), column.javaType(), false, List.of(), reference));
            selections.add(new VisualQueryDefinition.SelectField("sliceRows." + column.name(), field));
        }
        var stages = new ArrayList<>(fragment.ctes());
        var definition = new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, fragment.latestName(),
            "sliceRows", selections, List.of(), List.of(), List.of(), List.of(), null, null,
            fragment.ctes().getFirst().definition().parameters(), List.of(), List.of());
        stages.add(new VisualQueryPackage.Cte(name, new VisualQueryPackage.CteSource("sliceRows", fragment.latestName()), definition));
        var entity = new QueryBuilderMetadataCatalog.Entity(name, Object.class, fields, List.of(),
            register.caption() + ".Срез последних (:" + dateParameter + ")");
        return new ConstructorSlice(name, register.entityName(), dateParameter, spec, stages, entity,
            RegisterSliceConstructorSources.settings(register.entityName(), dateParameter, spec, contextNames, parameterFilters), parameterFilters);
    }

    public List<ConstructorSlice> constructorSlices(String json) {
        return RegisterSliceConstructorSources.read(json, this, catalog);
    }

    public String saveConstructorSlices(String json, List<ConstructorSlice> sources) {
        return RegisterSliceConstructorSources.attach(json, sources);
    }

    public java.util.Set<String> constructorNamespace(ConstructorSlice source) {
        return RegisterSliceConstructorSources.occupied(source.settingsJson());
    }

    public List<QueryBuilderMetadataCatalog.Field> sliceFields(Class<?> registerType) {
        var source = source(registerType);
        var entity = catalog.root(source.descriptor().entityName());
        var result = new ArrayList<QueryBuilderMetadataCatalog.Field>();
        java.util.stream.Stream.concat(source.dimensions().stream(), source.resources().stream()).forEach(field ->
            result.add(new QueryBuilderMetadataCatalog.Field(field.field(), caption(entity, field.field()), field.comparisonType(), false, List.of(), field.reference() ? catalog.root(field.targetEntity()).javaType() : null)));
        source.descriptor().period().ifPresent(field -> result.add(new QueryBuilderMetadataCatalog.Field(field.name(), caption(entity, field.name()), field.javaType(), false)));
        if (source.descriptor().recorded()) result.add(new QueryBuilderMetadataCatalog.Field("recorder", "Регистратор", org.ipro.register.RecorderRef.class, false));
        return List.copyOf(result);
    }

    public List<QueryBuilderMetadataCatalog.Entity> sliceSources() {
        var periodic = registers.descriptors().stream().filter(org.ipro.register.RegisterDescriptor::periodic)
            .map(org.ipro.register.RegisterDescriptor::entityType).collect(java.util.stream.Collectors.toSet());
        return catalog.roots().stream().filter(entity -> periodic.contains(entity.javaType()))
            .filter(entity -> permissions == null || permissions.isEntityReadPermitted(currentUser.requireAuthenticatedUsername(), entity.javaType()))
            .toList();
    }

    /** Dimensions belong before the slice; resources and period belong after it. */
    public List<FilterField> sliceFilters(Class<?> registerType, boolean afterSlice) {
        var source = source(registerType);
        var entity = catalog.root(source.descriptor().entityName());
        var result = new ArrayList<FilterField>();
        for (var field : afterSlice ? source.resources() : source.dimensions()) {
            Class<?> referenceType = field.reference() ? catalog.root(field.targetEntity()).javaType() : null;
            result.add(new FilterField(field.field(), caption(entity, field.field()), field.comparisonType(), referenceType));
        }
        if (afterSlice) {
            var period = source.descriptor().period().orElseThrow();
            result.add(new FilterField(period.name(), caption(entity, period.name()), period.javaType(), null));
        }
        return List.copyOf(result);
    }

    /** General editor recipe: all slice columns, a named runtime boundary, and an ordinary saved package. */
    public QueryDefinition createSliceQuery(SliceSpec spec, String dateParameter) { return createSliceQuery(spec, dateParameter, List.of()); }

    public List<VisualQueryDefinition.Parameter> sourceParameters(ConstructorSlice source) {
        var values = new java.util.LinkedHashMap<String, VisualQueryDefinition.Parameter>();
        source.stages().getFirst().definition().parameters().forEach(value -> values.put(value.name(), value));
        for (var filter : source.parameterFilters()) {
            var field = sliceFilters(source.spec().registerType(), filter.afterSlice()).stream().filter(value -> value.name().equals(filter.field())).findFirst().orElseThrow();
            String type = field.referenceType() != null ? "ENTITY" : field.javaType() == LocalDateTime.class ? "DATETIME" : field.javaType() == java.time.LocalDate.class ? "DATE" : field.javaType().getName();
            values.put(filter.parameter(), new VisualQueryDefinition.Parameter(filter.parameter(), type, true, null));
        }
        return List.copyOf(values.values());
    }
    public QueryDefinition createSliceQuery(SliceSpec spec, String dateParameter, List<SliceParameterFilter> parameterFilters) {
        if (dateParameter == null || !dateParameter.matches("[A-Za-z_][A-Za-z0-9_]{0,49}"))
            throw new IllegalArgumentException("Имя параметра даты: латинские буквы, цифры и _, до 50 символов.");
        var constructorSource = constructorSlice(spec, dateParameter, java.util.Set.of(), parameterFilters);
        var fields = constructorSource.entity().fields().stream().map(field ->
            new VisualQueryDefinition.SelectField("slice." + field.name(), field.name())).toList();
        var main = new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, constructorSource.name(), "slice", fields,
            List.of(), List.of(), List.of(), List.of(), null, null,
            sourceParameters(constructorSource), List.of(), List.of());
        var pack = new VisualQueryPackage(constructorSource.stages(), main);
        String jpql = VisualQueryCompiler.compile(pack, catalog).jpql();
        String json = saveConstructorSlices(new VisualQueryPackageJsonCodec().write(pack), List.of(constructorSource));
        String readable = new org.ipro.query.ReportQueryLanguage(catalog, this).format(json).orElseThrow();
        json = RegisterSlicePresentation.attach(json, readable);
        var parameters = new ArrayList<QueryParameter>();
        parameters.add(dateQueryParameter(dateParameter, 0));
        for (var filter : constructorSource.parameterFilters()) {
            if (parameters.stream().anyMatch(value -> value.name().equals(filter.parameter()))) continue;
            var field = sliceFilters(spec.registerType(), filter.afterSlice()).stream().filter(value -> value.name().equals(filter.field())).findFirst().orElseThrow();
            boolean reference = field.referenceType() != null;
            parameters.add(new QueryParameter(filter.parameter(), filter.parameter(), filter.parameter(),
                reference ? QueryParameterKind.ENTITY : QueryParameterKind.SCALAR, QueryScalarType.UNRESOLVED,
                reference ? field.referenceType().getName() : null, QueryValueSource.FORM, true, true,
                null, QueryComputedValue.NONE, null, parameters.size()));
        }
        return new QueryDefinition(jpql, QuerySource.VISUAL, json, null, parameters);
    }

    private static QueryParameter dateQueryParameter(String name, int position) {
        return new QueryParameter(name, name, "Дата и время среза", QueryParameterKind.SCALAR,
            QueryScalarType.UNRESOLVED, null, QueryValueSource.FORM, true, true, null,
            QueryComputedValue.NONE, null, position);
    }

    /** Reopen display text only while it describes the exact executable visual package. */
    public java.util.Optional<String> describeSliceQuery(QueryDefinition definition) {
        if (definition == null || definition.source() != QuerySource.VISUAL || definition.visualQueryJson() == null)
            return java.util.Optional.empty();
        if (!constructorSlices(definition.visualQueryJson()).isEmpty()) {
            try { return new org.ipro.query.ReportQueryLanguage(catalog, this).format(definition.visualQueryJson()); }
            catch (RuntimeException unsupported) { return java.util.Optional.empty(); }
        }
        var saved = RegisterSlicePresentation.read(definition.visualQueryJson());
        if (saved.isPresent()) return saved;
        // Earlier generated reports without filters need no migration or resave.
        try {
            var codec = new VisualQueryPackageJsonCodec();
            var pack = codec.read(definition.visualQueryJson());
            if (pack.ctes().isEmpty()) return java.util.Optional.empty();
            String root = pack.ctes().getFirst().definition().entityName();
            var parameters = pack.ctes().getFirst().definition().parameters();
            if (parameters.size() != 1) return java.util.Optional.empty();
            var descriptor = registers.descriptors().stream()
                .filter(candidate -> candidate.periodic() && candidate.entityName().equals(root)).findFirst();
            if (descriptor.isEmpty()) return java.util.Optional.empty();
            var generated = createSliceQuery(SliceSpec.latest(descriptor.get().entityType(), LocalDateTime.MIN),
                parameters.getFirst().name());
            if (codec.write(pack).equals(codec.write(codec.read(generated.visualQueryJson()))))
                return RegisterSlicePresentation.read(generated.visualQueryJson());
        } catch (RuntimeException unsupported) {
            // Arbitrary or edited CTE packages retain their normal editor.
        }
        return java.util.Optional.empty();
    }

    public String retainSlicePresentation(String originalJson, String editedJson) {
        return RegisterSlicePresentation.retain(originalJson, editedJson);
    }

    private static void appendFilters(StringBuilder text, String title, List<SliceSpec.Condition> filters,
                                      QueryBuilderMetadataCatalog.Entity entity) {
        if (filters.isEmpty()) return;
        text.append("\n").append(title).append("\n");
        for (int index = 0; index < filters.size(); index++) {
            var condition = filters.get(index);
            String operator = switch (condition.operator()) {
                case EQ -> "="; case NE -> "<>"; case GT -> ">";
                case GE -> ">="; case LT -> "<"; case LE -> "<=";
            };
            Object value = condition.value();
            String literal = value instanceof String string ? "\"" + string.replace("\"", "\"\"") + "\""
                : value.toString();
            if (entity.associations().stream().anyMatch(association -> association.name().equals(condition.field())))
                literal = "ID " + literal;
            text.append("    [").append(caption(entity, condition.field())).append("] ")
                .append(operator).append(" ").append(literal)
                .append(index + 1 < filters.size() ? " И\n" : "");
        }
    }

    private static String caption(QueryBuilderMetadataCatalog.Entity entity, String name) {
        if ("recorder.typeCode".equals(name)) return "Тип регистратора";
        if ("recorder.id".equals(name)) return "Идентификатор регистратора";
        return entity.fields().stream().filter(field -> field.name().equals(name)).map(QueryBuilderMetadataCatalog.Field::caption)
            .findFirst().orElseGet(() -> entity.associations().stream().filter(field -> field.name().equals(name))
                .map(QueryBuilderMetadataCatalog.Association::caption).findFirst().orElse(name));
    }

    private RegisterSliceSource source(Class<?> type) {
        var source = RegisterSliceSource.resolve(type, registers, factory.getMetamodel(), catalog);
        if (permissions != null) {
            var entities = new java.util.LinkedHashSet<Class<?>>(); entities.add(type);
            java.util.stream.Stream.concat(source.dimensions().stream(), source.resources().stream())
                .filter(RegisterSliceSource.Dimension::reference)
                .forEach(field -> entities.add(catalog.root(field.targetEntity()).javaType()));
            for (var entityType : entities) {
                if (!permissions.isEntityReadPermitted(currentUser.requireAuthenticatedUsername(), entityType)) {
                    String label = catalog.roots().stream().filter(entity -> entity.javaType() == entityType)
                        .map(QueryBuilderMetadataCatalog.Entity::caption).findFirst().orElse(entityType.getSimpleName());
                    throw new IllegalArgumentException("Нет права чтения: «" + label + "».");
                }
            }
        }
        return source;
    }
    private final EntityManagerFactory factory;
    private final RegisterRegistry registers;
    private final QueryBuilderMetadataCatalog catalog;
    private final RegisterSliceCteGenerator generator;
    private final org.ipro.rls.c5.C5PermissionEvaluator permissions;
    private final org.ipro.rls.RlsCurrentUser currentUser;

    public RegisterSliceReportTemplates(EntityManagerFactory factory, RegisterRegistry registers,
                                        QueryBuilderMetadataCatalog catalog, RegisterSliceCteGenerator generator) {
        this(factory, registers, catalog, generator, null, null);
    }

    public RegisterSliceReportTemplates(EntityManagerFactory factory, RegisterRegistry registers,
                                        QueryBuilderMetadataCatalog catalog, RegisterSliceCteGenerator generator,
                                        org.ipro.rls.c5.C5PermissionEvaluator permissions, org.ipro.rls.RlsCurrentUser currentUser) {
        this.factory = factory; this.registers = registers; this.catalog = catalog; this.generator = generator;
        this.permissions = permissions; this.currentUser = currentUser;
        if ((permissions == null) != (currentUser == null)) throw new IllegalArgumentException("C5 и пользователь задаются вместе");
    }
}

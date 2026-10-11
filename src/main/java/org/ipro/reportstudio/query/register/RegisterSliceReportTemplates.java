package org.ipro.reportstudio.query.register;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.register.RegisterRegistry;
import org.ipro.register.SliceSpec;
import org.ipro.reportstudio.dom.*;
import org.ipro.reportstudio.query.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Supported recipe for an editable report of a reference directory and its latest register values. */
public final class RegisterSliceReportTemplates {
    /** Dates declared by the saved visual query, including ordinary queries without a slice. */
    public static java.util.Set<String> dateParameters(ReportTemplate template) {
        return temporalParameters(template, "DATE");
    }

    public static java.util.Set<String> dateTimeParameters(ReportTemplate template) {
        return temporalParameters(template, "DATETIME");
    }

    public static java.util.Map<String,String> parameterTypes(ReportTemplate template) {
        if (template.getQuerySource() != ReportQuerySource.VISUAL || template.getVisualQueryJson() == null) return java.util.Map.of();
        var result = new java.util.LinkedHashMap<String,String>();
        new VisualQueryPackageJsonCodec().read(template.getVisualQueryJson()).main().parameters().stream().filter(parameter -> !parameter.name().startsWith("sliceId_")).forEach(parameter -> result.put(parameter.name(), parameter.dataType()));
        return java.util.Map.copyOf(result);
    }

    private static java.util.Set<String> temporalParameters(ReportTemplate template, String dataType) {
        if (template.getVisualQueryJson() == null)
            return java.util.Set.of();
        String json = template.getVisualQueryJson();
        var definition = json.contains("\"ctes\"") ? new VisualQueryPackageJsonCodec().read(json).main()
            : new VisualQueryDefinitionJsonCodec().read(json);
        return definition.parameters().stream().filter(parameter -> dataType.equals(parameter.dataType()))
            .map(VisualQueryDefinition.Parameter::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public record RootColumn(String field, String alias, String caption) {
        public RootColumn {
            if (field == null || !field.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || alias == null || !alias.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("SLICE_REPORT_COLUMN: required bare field and alias");
        }
    }

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
    public ReportTemplate createSliceQuery(SliceSpec spec, String dateParameter) { return createSliceQuery(spec, dateParameter, List.of()); }

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
    public ReportTemplate createSliceQuery(SliceSpec spec, String dateParameter, List<SliceParameterFilter> parameterFilters) {
        if (dateParameter == null || !dateParameter.matches("[A-Za-z_][A-Za-z0-9_]{0,49}"))
            throw new IllegalArgumentException("Имя параметра даты: латинские буквы, цифры и _, до 50 символов.");
        var source = source(spec.registerType());
        var constructorSource = constructorSlice(spec, dateParameter, java.util.Set.of(), parameterFilters);
        var fields = constructorSource.entity().fields().stream().map(field ->
            new VisualQueryDefinition.SelectField("slice." + field.name(), field.name())).toList();
        var main = new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, constructorSource.name(), "slice", fields,
            List.of(), List.of(), List.of(), List.of(), null, null,
            sourceParameters(constructorSource), List.of(), List.of());
        var pack = new VisualQueryPackage(constructorSource.stages(), main);
        var template = new ReportTemplate();
        template.setQuerySource(ReportQuerySource.VISUAL);
        template.setVisualQueryJson(saveConstructorSlices(new VisualQueryPackageJsonCodec().write(pack), List.of(constructorSource)));
        template.setJpql(VisualQueryCompiler.compile(pack, catalog).jpql());
        String readable = new org.ipro.reportstudio.query.ReportQueryLanguage(catalog, this).format(template).orElseThrow();
        template.setVisualQueryJson(RegisterSlicePresentation.attach(template.getVisualQueryJson(), readable));
        var parameter = new ReportParam();
        parameter.setName(dateParameter); parameter.setCaption("Дата и время среза");
        parameter.setRequired(true); parameter.setShowOnForm(true); template.addParam(parameter);
        for (var filter : constructorSource.parameterFilters()) {
            if (template.getParams().stream().anyMatch(value -> value.getName().equals(filter.parameter()))) continue;
            var field = sliceFilters(spec.registerType(), filter.afterSlice()).stream().filter(value -> value.name().equals(filter.field())).findFirst().orElseThrow();
            var declaration = new ReportParam(); declaration.setName(filter.parameter()); declaration.setCaption(filter.parameter());
            declaration.setRequired(true); declaration.setShowOnForm(true); declaration.setPosition(template.getParams().size());
            if (field.referenceType() != null) {
                declaration.setKind(org.ipro.reportstudio.dom.ReportParamKind.ENTITY); declaration.setEntityClass(field.referenceType().getName());
            }
            template.addParam(declaration);
        }
        return template;
    }

    /** Reopen display text only while it describes the exact executable visual package. */
    public java.util.Optional<String> describeSliceQuery(ReportTemplate template) {
        if (template == null || template.getQuerySource() != ReportQuerySource.VISUAL)
            return java.util.Optional.empty();
        if (!constructorSlices(template.getVisualQueryJson()).isEmpty()) {
            try { return new org.ipro.reportstudio.query.ReportQueryLanguage(catalog, this).format(template); }
            catch (RuntimeException unsupported) { return java.util.Optional.empty(); }
        }
        var saved = RegisterSlicePresentation.read(template.getVisualQueryJson());
        if (saved.isPresent()) return saved;
        // Earlier generated reports without filters need no migration or resave.
        try {
            var codec = new VisualQueryPackageJsonCodec();
            var pack = codec.read(template.getVisualQueryJson());
            if (pack.ctes().isEmpty()) return java.util.Optional.empty();
            String root = pack.ctes().getFirst().definition().entityName();
            var parameters = pack.ctes().getFirst().definition().parameters();
            if (parameters.size() != 1) return java.util.Optional.empty();
            var descriptor = registers.descriptors().stream()
                .filter(candidate -> candidate.periodic() && candidate.entityName().equals(root)).findFirst();
            if (descriptor.isEmpty()) return java.util.Optional.empty();
            var generated = createSliceQuery(SliceSpec.latest(descriptor.get().entityType(), LocalDateTime.MIN),
                parameters.getFirst().name());
            if (codec.write(pack).equals(codec.write(codec.read(generated.getVisualQueryJson()))))
                return RegisterSlicePresentation.read(generated.getVisualQueryJson());
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

    public ReportTemplate createDraft(Class<?> registerType, String referenceDimension, String dateParameter,
                                      String name, List<RootColumn> rootColumns) {
        rootColumns = List.copyOf(rootColumns);
        var source = RegisterSliceSource.resolve(registerType, registers, factory.getMetamodel(), catalog);
        if (source.dimensions().size() != 1 || !source.dimensions().getFirst().reference()
                || !source.dimensions().getFirst().field().equals(referenceDimension))
            throw new IllegalArgumentException("SLICE_REPORT_DIMENSION: recipe requires one reference dimension");
        var slice = generator.generateParameterized(source, SliceSpec.latest(registerType, LocalDateTime.MIN),
            RegisterSliceCteGenerator.NameContext.empty(), dateParameter);
        var fields = new ArrayList<VisualQueryDefinition.SelectField>();
        fields.add(new VisualQueryDefinition.SelectField("n.id", "itemId"));
        rootColumns.forEach(column -> fields.add(new VisualQueryDefinition.SelectField("n." + column.field(), column.alias())));
        fields.add(new VisualQueryDefinition.SelectField("p." + slice.columnNames().get(source.descriptor().period().orElseThrow().name()), "slicePeriod"));
        source.descriptor().resources().forEach(resource -> fields.add(new VisualQueryDefinition.SelectField(
            "p." + slice.columnNames().get(resource.name()), resource.name())));
        if (fields.stream().map(VisualQueryDefinition.SelectField::resultName).distinct().count() != fields.size())
            throw new IllegalArgumentException("SLICE_REPORT_COLUMN: duplicate output alias");
        var main = new VisualQueryDefinition(3, source.dimensions().getFirst().targetEntity(), "n", fields,
            List.of(new VisualQueryDefinition.Join(null, slice.latestName(), "p", VisualQueryDefinition.JoinKind.LEFT,
                new JoinCondition.Predicate("n.id", JoinCondition.Operator.EQ, "p." + slice.columnNames().get(referenceDimension)))),
            List.of(), List.of(), List.of(), null, null, List.of(),
            List.of(new VisualQueryOrder("n.id", VisualQueryOrder.Direction.ASC)));
        var pack = slice.appendTo(new VisualQueryPackage(List.of(), main));
        var template = new ReportTemplate(); template.setName(name);
        template.setDescription("Последние значения на выбранный момент включительно. Пустые значения означают отсутствие истории.");
        template.setQuerySource(ReportQuerySource.VISUAL);
        template.setVisualQueryJson(new VisualQueryPackageJsonCodec().write(pack));
        template.setJpql(VisualQueryCompiler.compile(pack, catalog).jpql());
        var parameter = new ReportParam(); parameter.setName(dateParameter); parameter.setCaption("Дата и время среза");
        parameter.setRequired(true); parameter.setShowOnForm(true); template.addParam(parameter);
        var detail = new ReportBand();
        for (var column : rootColumns) addColumn(detail, column.alias(), column.caption(), null);
        boolean exact = source.descriptor().periodicity() == org.ipro.register.RegisterPeriodicity.EXACT;
        addColumn(detail, "slicePeriod", exact ? "Период записи" : "Дата записи",
            exact ? "dd.MM.yyyy HH:mm:ss.SSS" : "dd.MM.yyyy");
        source.descriptor().resources().forEach(resource -> addColumn(detail, resource.name(), resource.name(),
            resource.javaType() == java.math.BigDecimal.class ? "#,##0.0000" : null));
        template.addBand(detail);
        return template;
    }

    private static void addColumn(ReportBand band, String name, String caption, String format) {
        var field = new ReportField(); field.setQueryField(name); field.setCaption(caption);
        field.setPosition(band.getFields().size()); field.setFormat(format); band.addField(field);
    }
}

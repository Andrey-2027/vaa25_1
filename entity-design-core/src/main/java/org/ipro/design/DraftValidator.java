package org.ipro.design;

import java.util.*;
import javax.lang.model.SourceVersion;
import static org.ipro.design.DraftProblem.Code.*;
import org.ipro.design.DraftProblem.Code;
import org.ipro.design.DraftProblem.Severity;
import org.ipro.design.RecipeCatalog.FieldType;

/** The only semantic rule engine. No IO, class loading, mutable cache or business callbacks. */
public final class DraftValidator {
    public ValidationResult validate(EntityDraft draft, ProjectDesignContext context) {
        return new Run(Objects.requireNonNull(draft), Objects.requireNonNull(context)).validate();
    }

    private static final class Run {
        private final EntityDraft draft;
        private final ProjectDesignContext context;
        private final List<DraftProblem> problems = new ArrayList<>();
        private final Set<UUID> ids = new HashSet<>();
        private final Set<String> types = new HashSet<>(), tables = new HashSet<>();
        private final Set<String> newTypes = new HashSet<>();
        private final Set<String> foldedTypes = new HashSet<>();
        private boolean extension() { return draft.recipe().equals(RecipeCatalog.STANDARD_CATALOG_V2) || draft.recipe().equals(RecipeCatalog.STANDARD_DOCUMENT); }
        private UUID sectionId, fieldId;
        Run(EntityDraft draft, ProjectDesignContext context) { this.draft = draft; this.context = context; }

        ValidationResult validate() {
            boolean registerRecipe = RecipeCatalog.isRegisterRecipe(draft.recipe());
            var recipe = RecipeCatalog.find(draft.recipe());
            if (registerRecipe) {
                if (draft.schemaVersion() != 3) error(UNSUPPORTED_VERSION, "schemaVersion", Integer.toString(draft.schemaVersion()));
                if (recipe.isEmpty()) error(UNSUPPORTED_VERSION, "recipe", draft.recipe().toString());
                if (!draft.sections().isEmpty()) error(REGISTER_SECTION_UNSUPPORTED, "sections", Integer.toString(draft.sections().size()));
                var snapshot=context.runtimeSnapshot();
                if (!snapshot.complete()) error(PROJECT_FACT_UNKNOWN,"context.runtimeSnapshot","incomplete");
                var facts=snapshot.prerequisite("REGISTER_FACTS");
                if (facts.status()!=RuntimeDesignSnapshot.Status.AVAILABLE)
                    error(facts.status()==RuntimeDesignSnapshot.Status.UNKNOWN ? PROJECT_FACT_UNKNOWN : RECIPE_PREREQUISITE_UNAVAILABLE,
                        "recipe.prerequisites.REGISTER_FACTS",facts.evidence());
            } else {
                if (draft.schemaVersion() != 1 && draft.schemaVersion() != 2 || draft.schemaVersion() == 1 && draft.owner() != null)
                    error(UNSUPPORTED_VERSION, "schemaVersion", Integer.toString(draft.schemaVersion()));
                if (draft.schemaVersion() == 2 && !Set.of(RecipeCatalog.STANDARD_CATALOG_V2, RecipeCatalog.STANDARD_DOCUMENT).contains(draft.recipe()))
                    error(UNSUPPORTED_VERSION, "recipe", draft.recipe().toString());
                if (recipe.isEmpty()) error(UNSUPPORTED_VERSION, "recipe", draft.recipe().toString());
                else if (!recipe.get().draftSupported()) error(RECIPE_DRAFT_UNSUPPORTED,"recipe",draft.recipe().toString());
                // Capture v1 keeps its original semantic checks. Opting into v2 requires current runtime facts.
                else if (draft.recipe().equals(RecipeCatalog.STANDARD_CATALOG_V2) || draft.recipe().equals(RecipeCatalog.STANDARD_DOCUMENT)) {
                    if (!context.runtimeSnapshot().complete()) error(PROJECT_FACT_UNKNOWN,"context.runtimeSnapshot","incomplete");
                    for (String prerequisite : recipe.get().runtimePrerequisites()) {
                        var fact=context.runtimeSnapshot().prerequisite(prerequisite);
                        if (fact.status()!=RuntimeDesignSnapshot.Status.AVAILABLE)
                            error(fact.status()==RuntimeDesignSnapshot.Status.UNKNOWN ? PROJECT_FACT_UNKNOWN : RECIPE_PREREQUISITE_UNAVAILABLE,
                                "recipe.prerequisites."+prerequisite,fact.evidence());
                    }
                }
                if (draft.register() != null) error(REGISTER_SECTION_UNSUPPORTED, "register", draft.recipe().id());
            }
            id(draft.draftId());
            required(context.projectId(), "context.projectId"); required(context.version(), "context.version");
            catalog(context.existingTypes(), "context.existingTypes");
            catalog(context.existingTables(), "context.existingTables");
            var e = draft.entity();
            String pkg = DraftSuggestions.rootPackage(draft, context), table = DraftSuggestions.rootTable(draft);
            if (e.packageName().mode() == DraftText.Mode.AUTO && context.defaultPackage() == null)
                error(PROJECT_FACT_UNKNOWN, "packageName", "defaultPackage");
            type(e.className(), pkg);
            newTypes.add(pkg + "." + e.className());
            if (!registerRecipe) for (var s : draft.sections()) newTypes.add(DraftSuggestions.rowPackage(s, pkg) + "." + s.rowClassName());
            required(e.label(), "label"); raw(e.className(), "className"); raw(e.packageName().text(), "packageName");
            storage(table, "tableName", tables); raw(e.tableName().text(), "tableName");
            if (context.existingTables().values().contains(table)) error(DUPLICATE_STORAGE_NAME, "tableName", table);
            String list = e.titles().list().resolve(e.label()), item = e.titles().item().resolve(e.label()), selection = e.titles().selection().resolve(e.label());
            required(list, "titles.list"); required(item, "titles.item"); required(selection, "titles.selection");
            raw(e.titles().list().text(), "titles.list"); raw(e.titles().item().text(), "titles.item"); raw(e.titles().selection().text(), "titles.selection");
            if (e.subsystemId() != null) choice(e.subsystemId(), context.subsystemIds(), "subsystemId", SUBSYSTEM_UNAVAILABLE);
            choice(e.icon(), context.iconIds(), "icon", ICON_UNAVAILABLE);
            if (draft.sections().size() > RecipeCatalog.MAX_SECTIONS) error(LIMIT_EXCEEDED, "sections", "50");
            if ((long) draft.fields().size() + draft.sections().stream().mapToLong(s -> s.fields().size()).sum() > RecipeCatalog.MAX_FIELDS)
                error(LIMIT_EXCEEDED, "fields", "1000");
            var rootReserved = new HashSet<>(RecipeCatalog.systemFieldNames());
            if (registerRecipe) rootReserved.addAll(RecipeCatalog.registerSystemFieldNames(
                draft.register() == null ? RecipeCatalog.REGISTER_DAY : draft.register().period()));
            if (recipe.isPresent() && recipe.get().draftSupported())
                RecipeCatalog.inheritedFields(draft.recipe()).forEach(f -> rootReserved.add(f.name()));
            var rootColumns = storageNames(rootReserved);
            var owner = owner(rootReserved, rootColumns);
            var fields = fields(draft.fields(), rootReserved, rootColumns);
            var sections = new ArrayList<CheckedStructure.Section>();
            if (!registerRecipe) for (var s : draft.sections()) {
                sectionId = s.id(); fieldId = null;
                id(s.id()); required(s.label(), "label");
                String rowPkg = DraftSuggestions.rowPackage(s, pkg), rowTable = DraftSuggestions.rowTable(s);
                type(s.rowClassName(), rowPkg);
                raw(s.rowClassName(), "rowClassName"); raw(s.packageName().text(), "packageName"); raw(s.tableName().text(), "tableName");
                storage(rowTable, "tableName", tables);
                if (context.existingTables().values().contains(rowTable)) error(DUPLICATE_STORAGE_NAME, "tableName", rowTable);
                String parent = DraftSuggestions.parentField(s), parentColumn = DraftSuggestions.parentColumn(s);
                identifier(parent, "parentFieldName"); raw(s.parentFieldName().text(), "parentFieldName"); raw(s.parentColumnName().text(), "parentColumnName");
                if (RecipeCatalog.systemFieldNames().contains(parent) || extension() && (accessorSuffix(parent).equals("LineNumber") || accessorSuffix(parent).equals("Class"))) error(RESERVED_FIELD, "parentFieldName", parent);
                var reserved = new HashSet<>(RecipeCatalog.systemFieldNames()); reserved.add(parent);
                var columns = storageNames(RecipeCatalog.systemFieldNames());
                if (extension()) { reserved.add("lineNumber"); columns.add("line_number"); }
                storage(parentColumn, "parentColumnName", columns);
                var rowFields = fields(s.fields(), reserved, columns);
                sections.add(new CheckedStructure.Section(s.id(), s.label(), s.rowClassName(), rowPkg, rowTable, parent, parentColumn, rowFields));
            }
            var register = registerRecipe ? register(fields, table) : null;
            boolean errors = problems.stream().anyMatch(p -> p.severity() == Severity.ERROR);
            var checked = errors ? null : new CheckedStructure(draft, context,
                    new CheckedStructure.Root(e.className(), pkg, e.label(), list, item, selection, table, e.subsystemId(), e.icon()),
                    fields, sections, owner, register);
            return new ValidationResult(problems, checked);
        }

        /**
         * Register rules of the first slice: ordered roles, builtin period, key order and write
         * profile. A role is a UUID reference, so a renamed field keeps its role and a removed
         * field leaves a diagnosable unknown reference instead of a silent re-assignment.
         */
        private CheckedStructure.Register register(List<CheckedStructure.Field> fields, String table) {
            var reg = draft.register();
            if (reg == null) { error(REGISTER_ROLE_MISSING, "register.roles", "missing"); return null; }
            if (!Set.of(RecipeCatalog.REGISTER_NONE, RecipeCatalog.REGISTER_DAY).contains(reg.period()))
                error(REGISTER_PERIOD_UNSUPPORTED, "register.period", reg.period());
            if (!RecipeCatalog.REGISTER_WRITE_PROFILE.equals(reg.writeProfile()))
                error(REGISTER_WRITE_PROFILE_UNSUPPORTED, "register.writeProfile", reg.writeProfile());
            var byId = new LinkedHashMap<UUID, CheckedStructure.Field>();
            for (var f : fields) byId.putIfAbsent(f.id(), f);
            boolean anomaly = roles(reg.dimensions(), "register.dimensions", byId);
            anomaly |= roles(reg.resources(), "register.resources", byId);
            var overlap = new HashSet<>(reg.dimensions()); overlap.retainAll(reg.resources());
            if (!overlap.isEmpty()) {
                // A resource may not repeat a dimension: report it on the resource list that contains it.
                error(REGISTER_ROLE_DUPLICATE, "register.resources", overlap.iterator().next().toString()); anomaly = true;
            }
            if (!anomaly) {
                var assigned = new HashSet<UUID>(); assigned.addAll(reg.dimensions()); assigned.addAll(reg.resources());
                boolean unassigned = draft.fields().stream().anyMatch(f -> !assigned.contains(f.id()));
                if (reg.dimensions().isEmpty() && reg.resources().isEmpty())
                    error(REGISTER_ROLE_MISSING, "register.roles", "empty");
                else {
                    if (reg.dimensions().isEmpty()) error(REGISTER_ROLE_MISSING, "register.dimensions", "empty");
                    if (reg.resources().isEmpty()) error(REGISTER_ROLE_MISSING, "register.resources", "empty");
                    if (unassigned) error(REGISTER_ROLE_MISSING, "register.roles", "unassigned");
                }
            }
            for (UUID id : reg.dimensions()) dimension(byId.get(id));
            for (UUID id : reg.resources()) resource(byId.get(id));
            var dimensions = reg.dimensions().stream().map(byId::get).filter(Objects::nonNull).map(Run::role).toList();
            var resources = reg.resources().stream().map(byId::get).filter(Objects::nonNull).map(Run::role).toList();
            String periodColumn = reg.periodic() ? RecipeCatalog.REGISTER_PERIOD_COLUMN : null;
            var dimensionColumns = dimensions.stream().map(CheckedStructure.RegisterRole::column).toList();
            var keyColumns = new ArrayList<>(dimensionColumns);
            if (periodColumn != null) keyColumns.add(periodColumn);
            return new CheckedStructure.Register(reg.period(), reg.writeProfile(), periodColumn, table,
                draft.entity().className(), dimensions, resources, dimensionColumns, keyColumns);
        }
        private boolean roles(List<UUID> roleIds, String property, Map<UUID, CheckedStructure.Field> byId) {
            boolean anomaly = false; var seen = new HashSet<UUID>();
            for (UUID id : roleIds) {
                if (!byId.containsKey(id)) { error(REGISTER_ROLE_UNKNOWN, property, id.toString()); anomaly = true; }
                else if (!seen.add(id)) { error(REGISTER_ROLE_DUPLICATE, property, id.toString()); anomaly = true; }
            }
            return anomaly;
        }
        private void dimension(CheckedStructure.Field field) {
            if (field == null) return;
            if (!field.serverRequired()) error(REGISTER_DIMENSION_REQUIRED, "serverRequired", field.name());
            if (!Set.of(FieldType.STRING, FieldType.INTEGER, FieldType.LONG, FieldType.REFERENCE).contains(field.type()))
                error(REGISTER_FIELD_ROLE_UNSUPPORTED, "register.roles", field.name());
            if (field.type() == FieldType.STRING && field.length() != null && field.length() > 200)
                error(LIMIT_EXCEEDED, "constraints.length", Integer.toString(field.length()));
        }
        private void resource(CheckedStructure.Field field) {
            if (field == null) return;
            if (!Set.of(FieldType.STRING, FieldType.INTEGER, FieldType.LONG, FieldType.DECIMAL, FieldType.BOOLEAN).contains(field.type()))
                error(REGISTER_FIELD_ROLE_UNSUPPORTED, "register.roles", field.name());
            if (field.type() == FieldType.STRING && field.length() != null && field.length() > 4000)
                error(LIMIT_EXCEEDED, "constraints.length", Integer.toString(field.length()));
            if (field.type() == FieldType.DECIMAL && field.precision() != null && field.precision() > 19)
                error(INVALID_CONSTRAINT, "constraints.precision", Integer.toString(field.precision()));
        }
        private static CheckedStructure.RegisterRole role(CheckedStructure.Field field) {
            return new CheckedStructure.RegisterRole(field.id(), field.name(), field.columnName(), field.type(),
                field.length(), field.precision(), field.scale(), field.referenceTypeId());
        }

        private CheckedStructure.Owner owner(Set<String> reserved, Set<String> columns) {
            var owner = draft.owner();
            if (owner == null) return null;
            if (!draft.recipe().equals(RecipeCatalog.STANDARD_CATALOG_V2)) error(OWNER_UNSUPPORTED_RECIPE, "owner", draft.recipe().id());
            identifier(owner.fieldName(), "owner.fieldName"); required(owner.label(), "owner.label");
            raw(owner.columnName().text(), "owner.columnName"); raw(owner.targetTypeId(), "owner.targetTypeId");
            String suffix = accessorSuffix(owner.fieldName());
            if ("Class".equals(suffix) || reserved.stream().anyMatch(name -> accessorSuffix(name).equals(suffix)))
                error(RESERVED_FIELD, "owner.fieldName", owner.fieldName());
            if (draft.fields().stream().anyMatch(f -> accessorSuffix(f.name()).equals(suffix)))
                error(DUPLICATE_NAME, "owner.fieldName", owner.fieldName());
            String column = DraftSuggestions.ownerColumn(owner);
            storage(column, "owner.columnName", columns);
            if (draft.fields().stream().anyMatch(f -> DraftSuggestions.column(f).equals(column)))
                error(DUPLICATE_STORAGE_NAME, "owner.columnName", column);
            reserved.add(owner.fieldName());
            reference(owner.targetTypeId(), "owner.targetTypeId");
            var facts = context.runtimeSnapshot().types().get(owner.targetTypeId());
            if (facts == null || facts.kind().isBlank() || "UNKNOWN".equals(facts.kind()))
                error(PROJECT_FACT_UNKNOWN, "owner.targetTypeId", "unknownTargetKind");
            else if (!"CATALOG".equals(facts.kind()) || !"STANDARD_ROOT".equals(facts.exposure()))
                error(REFERENCE_UNAVAILABLE, "owner.targetTypeId", "ownerRequiresCatalogRoot");
            return new CheckedStructure.Owner(owner.targetTypeId(), owner.fieldName(), column, owner.label(), 30, FieldApplications.root());
        }

        private static String accessorSuffix(String name) {
            return name.isEmpty() ? "" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }

        private void reference(String reference, String property) {
            if (reference.isBlank()) { error(REFERENCE_REQUIRED, property, ""); return; }
            raw(reference, property);
            var target = context.referenceTargets().get(reference);
            if (newTypes.contains(reference)) error(REFERENCE_UNAVAILABLE, property, "newDraftReference");
            else if (target == null) error(context.referencesComplete() ? REFERENCE_UNAVAILABLE : PROJECT_FACT_UNKNOWN, property, reference);
            else if (!target.supported()) error(target.eligibility() == ReferenceTargetDescriptor.Eligibility.UNKNOWN
                    || target.representation() == ReferenceTargetDescriptor.Fact.UNKNOWN || target.selection() == ReferenceTargetDescriptor.Fact.UNKNOWN
                    ? PROJECT_FACT_UNKNOWN : REFERENCE_UNAVAILABLE, property, target.reason());
            else if (registerTarget(reference)) error(REFERENCE_UNAVAILABLE, property, "registerTarget");
            else if (!SourceVersion.isName(target.sourceTypeName(), SourceVersion.RELEASE_21))
                error(REFERENCE_UNAVAILABLE, property, "invalidJavaSourceType");
        }
        /** A register is never a reference target of the first slice, even with supported Long identity. */
        private boolean registerTarget(String typeId) {
            var facts = context.runtimeSnapshot().types().get(typeId);
            return facts != null && "INFORMATION_REGISTER".equals(facts.kind());
        }

        private List<CheckedStructure.Field> fields(List<FieldDraft> input, Set<String> reserved, Set<String> columns) {
            var names = new HashSet<String>();
            var checked = new ArrayList<CheckedStructure.Field>();
            for (var f : input) {
                fieldId = f.id(); id(f.id()); identifier(f.name(), "name"); required(f.label(), "label");
                if (!f.name().isEmpty() && !names.add(f.name())) error(DUPLICATE_NAME, "name", f.name());
                if (reserved.contains(f.name())) error(RESERVED_FIELD, "name", f.name());
                else if (extension() && reserved.stream().anyMatch(name -> accessorSuffix(name).equals(accessorSuffix(f.name())))) error(RESERVED_FIELD, "name", f.name());
                if (!f.name().isEmpty() && Character.isUpperCase(f.name().charAt(0))) warning(STYLE_CONVENTION, "name", "lowerCamelCase");
                String column = DraftSuggestions.column(f); storage(column, "columnName", columns); raw(f.columnName().text(), "columnName");
                raw(f.type(), "type");
                FieldType type = null;
                try { type = FieldType.valueOf(f.type()); }
                catch (IllegalArgumentException ex) { error(UNSUPPORTED_FIELD_TYPE, "type", f.type()); }
                var c = f.constraints();
                Integer length = constraint(c.length(), "constraints.length", type == FieldType.STRING, true);
                Integer precision = constraint(c.precision(), "constraints.precision", type == FieldType.DECIMAL, true);
                Integer scale = constraint(c.scale(), "constraints.scale", type == FieldType.DECIMAL, false);
                if (precision != null && scale != null && scale > precision) error(INVALID_CONSTRAINT, "constraints.scale", c.scale());
                String reference = null;
                if (type == FieldType.REFERENCE) {
                    if (f.reference() == null || f.reference().typeId().isBlank()) error(REFERENCE_REQUIRED, "reference.typeId", "");
                    else {
                        reference = f.reference().typeId();
                        reference(reference, "reference.typeId");
                    }
                } else if (f.reference() != null) {
                    raw(f.reference().typeId(), "reference.typeId");
                    error(REFERENCE_UNAVAILABLE, "reference.typeId", "incompatibleFieldType");
                }
                var a = f.applications();
                if (sectionId == null && a.table()) error(INVALID_APPLICATION, "applications.table", "root");
                if (sectionId != null && a.list()) error(INVALID_APPLICATION, "applications.list", "row");
                if (sectionId != null && a.selection()) error(INVALID_APPLICATION, "applications.selection", "row");
                if (f.serverRequired() && !a.form()) error(INVALID_APPLICATION, "applications.form", "requiredField");
                if (f.serverRequired() && f.uiRequired() == FieldDraft.UiRequired.OPTIONAL) warning(UI_REQUIRED_MISMATCH, "uiRequired", "serverRequired");
                if (!draft.recipe().equals(RecipeCatalog.STANDARD_CATALOG) && !f.serverRequired()
                        && f.uiRequired()==FieldDraft.UiRequired.REQUIRED)
                    warning(UI_REQUIRED_SERVER_OPTIONAL,"uiRequired","serverOptional");
                checked.add(new CheckedStructure.Field(f.id(), f.name(), f.label(), type, column, f.serverRequired(), f.uiRequired(), length, precision, scale, reference, a));
            }
            fieldId = null;
            return checked;
        }
        private Integer constraint(String value, String property, boolean applicable, boolean positive) {
            raw(value, property);
            if (!applicable) { if (!value.isEmpty()) error(INAPPLICABLE_CONSTRAINT, property, value); return null; }
            if (value.isEmpty()) { error(VALUE_REQUIRED, property, value); return null; }
            try {
                if (!value.matches("[0-9]+")) throw new NumberFormatException();
                int number = Integer.parseInt(value);
                if (positive && number == 0) throw new NumberFormatException();
                return number;
            } catch (NumberFormatException ex) { error(INVALID_CONSTRAINT, property, value); return null; }
        }
        private void type(String name, String pkg) {
            String property = sectionId == null ? "className" : "rowClassName";
            identifier(name, property);
            if (Set.of("var", "yield", "record", "sealed", "permits").contains(name)) error(INVALID_JAVA_IDENTIFIER, property, name);
            if (!name.isEmpty() && Character.isLowerCase(name.charAt(0))) warning(STYLE_CONVENTION, property, "UpperCamelCase");
            required(pkg, "packageName");
            if (!pkg.isEmpty() && (!pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
                    || Arrays.stream(pkg.split("\\.", -1)).anyMatch(Run::keyword))) error(INVALID_PACKAGE, "packageName", pkg);
            raw(pkg, "packageName");
            String fqn = pkg + "." + name;
            if (!types.add(fqn) || context.existingTypes().values().contains(fqn)) error(DUPLICATE_TYPE_NAME, property, fqn);
            if (extension() && (!foldedTypes.add(fqn.toLowerCase(Locale.ROOT))
                || context.existingTypes().values().stream().anyMatch(existing -> existing.equalsIgnoreCase(fqn) && !existing.equals(fqn))))
                error(DUPLICATE_TYPE_NAME, property, fqn);
        }
        private void identifier(String value, String property) {
            required(value, property);
            if (!value.isEmpty() && !value.matches("[A-Za-z_][A-Za-z0-9_]*")) error(INVALID_JAVA_IDENTIFIER, property, value);
            else if (keyword(value)) error(JAVA_KEYWORD, property, value);
        }
        private static boolean keyword(String value) {
            return SourceVersion.isKeyword(value, SourceVersion.RELEASE_21) || Set.of("true", "false", "null").contains(value);
        }
        private void storage(String value, String property, Set<String> names) {
            required(value, property);
            // Portable unquoted lowercase ASCII profile; maximum PostgreSQL identifier length.
            if (!value.isEmpty() && (!value.matches("[a-z][a-z0-9_]{0,62}") || Set.of("select", "from", "where", "table", "user", "order", "group", "primary", "references", "constraint").contains(value)))
                error(INVALID_STORAGE_NAME, property, value);
            if (!value.isEmpty() && !names.add(value)) error(DUPLICATE_STORAGE_NAME, property, value);
        }
        private static Set<String> storageNames(Set<String> names) {
            var result = new HashSet<String>(); names.forEach(n -> result.add(RecipeCatalog.snake(n))); return result;
        }
        private void choice(String value, ProjectDesignContext.Names names, String property, Code unavailable) {
            required(value, property);
            if (!names.complete()) error(PROJECT_FACT_UNKNOWN, property, names.reason());
            if (!value.isEmpty() && !names.values().contains(value)) error(names.complete() ? unavailable : PROJECT_FACT_UNKNOWN, property, value);
        }
        private void catalog(ProjectDesignContext.Names names, String property) { if (!names.complete()) error(PROJECT_FACT_UNKNOWN, property, names.reason()); }
        private void id(UUID value) { if (!ids.add(value)) error(DUPLICATE_ID, "id", value.toString()); }
        private void required(String value, String property) { raw(value, property); if (value.isBlank()) error(VALUE_REQUIRED, property, value); }
        private void raw(String value, String property) { if (value.codePointCount(0, value.length()) > RecipeCatalog.MAX_TEXT) error(LIMIT_EXCEEDED, property, "4096"); }
        private void error(Code code, String property, String value) { problem(Severity.ERROR, code, property, value); }
        private void warning(Code code, String property, String value) { problem(Severity.WARNING, code, property, value); }
        private void problem(Severity severity, Code code, String property, String value) {
            problems.add(new DraftProblem(severity, code, new DraftProblem.Target(draft.draftId(), sectionId, fieldId, property),
                    "entityDraft." + code.name(), Map.of("value", value)));
        }
    }
}

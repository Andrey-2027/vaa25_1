package org.ipro.design.generation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.ipro.design.CheckedStructure;
import org.ipro.design.DraftProblem;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RecipeCatalog.FieldType;

/**
 * Final gate shared by early UI readiness and later generation. It only accepts the checked
 * independent-register projection and returns stable codes/targets for anything the profile
 * cannot represent.
 */
public final class RegisterRepresentability {
    private RegisterRepresentability() { }

    public static List<GenerationDiagnostic> check(CheckedStructure checked) {
        Objects.requireNonNull(checked, "checked");
        var diagnostics = new ArrayList<GenerationDiagnostic>();
        var source = checked.source();
        var recipe = source.recipe();
        if (!RecipeCatalog.isRegisterRecipe(recipe)) {
            diagnostics.add(new GenerationDiagnostic(GenerationDiagnostic.Code.UNSUPPORTED_RECIPE,
                    target(source.draftId(), null, "recipe"),
                    messageKey(GenerationDiagnostic.Code.UNSUPPORTED_RECIPE),
                    Map.of("recipe", recipe.id() + "@" + recipe.version())));
            return diagnostics;
        }
        var register = checked.register();
        if (register == null) {
            diagnostics.add(unsupported(source.draftId(), null, "register", "REGISTER_PROJECTION",
                    Map.of()));
            return diagnostics;
        }
        var snapshot = checked.context().runtimeSnapshot();
        if (!snapshot.complete())
            diagnostics.add(unsupported(source.draftId(), null, "context.runtimeSnapshot", "RUNTIME_SNAPSHOT", Map.of()));
        var prerequisites = new ArrayList<>(RecipeCatalog.find(recipe).orElseThrow().runtimePrerequisites());
        prerequisites.add("REGISTER_FACTS");
        for (var prerequisite : prerequisites) {
            var fact = snapshot.prerequisite(prerequisite);
            if (fact.status() != org.ipro.design.RuntimeDesignSnapshot.Status.AVAILABLE)
                diagnostics.add(unsupported(source.draftId(), null, "recipe.prerequisites." + prerequisite,
                    "RUNTIME_PREREQUISITE", Map.of("prerequisite", prerequisite,
                        "status", fact.status().name(), "reason", fact.evidence())));
        }
        if (register.dimensions().isEmpty() || register.resources().isEmpty())
            diagnostics.add(projection(source.draftId(), null, "register.roles"));
        if (!checked.sections().isEmpty())
            diagnostics.add(unsupported(source.draftId(), null, "sections", "REGISTER_SECTIONS", Map.of()));
        if (checked.owner() != null)
            diagnostics.add(unsupported(source.draftId(), null, "owner", "REGISTER_OWNER", Map.of()));
        if (checked.root().subsystemId() != null)
            diagnostics.add(unsupported(source.draftId(), null, "subsystemId", "SUBSYSTEM",
                    Map.of("subsystemId", checked.root().subsystemId())));
        if (!Set.of(RecipeCatalog.REGISTER_NONE, RecipeCatalog.REGISTER_DAY).contains(register.period()))
            diagnostics.add(unsupported(source.draftId(), null, "register.period", "REGISTER_PERIOD",
                    Map.of("period", register.period())));
        if (!RecipeCatalog.REGISTER_WRITE_PROFILE.equals(register.writeProfile()))
            diagnostics.add(unsupported(source.draftId(), null, "register.writeProfile", "REGISTER_WRITE_PROFILE",
                    Map.of("writeProfile", register.writeProfile())));

        var fieldsById = new LinkedHashMap<UUID, CheckedStructure.Field>();
        for (var field : checked.fields()) fieldsById.putIfAbsent(field.id(), field);
        var rls = register.rls();
        var rawRls = source.register() == null ? null : source.register().rls();
        if (RecipeCatalog.isRlsRegisterRecipe(recipe)) {
            var field = rls == null ? null : fieldsById.get(rls.fieldId());
            if (field == null || rawRls == null || !rls.dimension().matches("[A-Z][A-Z0-9_]{0,63}")
                    || !rls.dimension().equals(rawRls.dimension()) || !rls.fieldId().equals(rawRls.fieldId())
                    || !source.register().dimensions().contains(rls.fieldId()) || field.type() != FieldType.REFERENCE
                    || !rls.valuePath().equals(field.name()+".id") || !rls.column().equals(field.columnName()))
                diagnostics.add(projection(source.draftId(), null, "register.rls"));
        } else if (rls != null || rawRls != null) diagnostics.add(projection(source.draftId(), null, "register.rls"));
        var assigned = new HashSet<UUID>();
        roles(diagnostics, checked, register.dimensions(), true, fieldsById, assigned);
        roles(diagnostics, checked, register.resources(), false, fieldsById, assigned);
        for (var field : checked.fields()) {
            var applications = field.applications();
            if (!applications.form()) diagnostics.add(unsupported(source.draftId(), field.id(),
                "applications.form", "REGISTER_APPLICATIONS", Map.of("expected", "true")));
            if (!applications.list()) diagnostics.add(unsupported(source.draftId(), field.id(),
                "applications.list", "REGISTER_APPLICATIONS", Map.of("expected", "true")));
            if (!applications.selection()) diagnostics.add(unsupported(source.draftId(), field.id(),
                "applications.selection", "REGISTER_APPLICATIONS", Map.of("expected", "true")));
            if (applications.table()) diagnostics.add(unsupported(source.draftId(), field.id(),
                "applications.table", "REGISTER_APPLICATIONS", Map.of("expected", "false")));
            if (!assigned.contains(field.id()))
                diagnostics.add(projection(source.draftId(), field.id(), "register.roles"));
            if (field.serverRequired() && field.uiRequired() == org.ipro.design.FieldDraft.UiRequired.OPTIONAL)
                diagnostics.add(new GenerationDiagnostic(GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE,
                        new DraftProblem.Target(source.draftId(), null, field.id(), "uiRequired"),
                        messageKey(GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE),
                        Map.of("runtimeCode", "UI_OPTIONAL_SERVER_REQUIRED",
                                "serverRequired", "true", "uiRequired", "OPTIONAL")));
            if (field.type() == FieldType.STRING && field.length() == null)
                diagnostics.add(projection(source.draftId(), field.id(), "constraints.length"));
            if (field.type() == FieldType.DECIMAL && (field.precision() == null || field.scale() == null))
                diagnostics.add(projection(source.draftId(), field.id(), "constraints"));
        }

        var expectedKey = new ArrayList<String>();
        for (var dimension : register.dimensions()) expectedKey.add(dimension.column());
        if (register.periodic()) expectedKey.add(RecipeCatalog.REGISTER_PERIOD_COLUMN);
        if (register.dimensionGroupColumns().size() != register.dimensions().size()
                || !register.dimensionGroupColumns().equals(register.dimensions().stream()
                        .map(CheckedStructure.RegisterRole::column).toList()))
            diagnostics.add(projection(source.draftId(), null, "register.dimensionGroupColumns"));
        if (register.rowKeyColumns().isEmpty() || !register.rowKeyColumns().equals(expectedKey))
            diagnostics.add(projection(source.draftId(), null, "register.rowKeyColumns"));
        if (register.periodic() != (register.periodColumn() != null)
                || register.periodic() && !RecipeCatalog.REGISTER_PERIOD_COLUMN.equals(register.periodColumn()))
            diagnostics.add(projection(source.draftId(), null, "register.period"));
        return diagnostics;
    }

    private static void roles(List<GenerationDiagnostic> diagnostics, CheckedStructure checked,
                              List<CheckedStructure.RegisterRole> roles, boolean dimension,
                              Map<UUID, CheckedStructure.Field> fieldsById, Set<UUID> assigned) {
        var source = checked.source();
        for (var role : roles) {
            if (!assigned.add(role.fieldId())) {
                diagnostics.add(projection(source.draftId(), role.fieldId(),
                        dimension ? "register.dimensions" : "register.resources"));
                continue;
            }
            var field = fieldsById.get(role.fieldId());
            if (field == null) {
                diagnostics.add(projection(source.draftId(), role.fieldId(),
                        dimension ? "register.dimensions" : "register.resources"));
                continue;
            }
            if (!role.name().equals(field.name()) || !role.column().equals(field.columnName())) {
                diagnostics.add(projection(source.draftId(), field.id(), "register.roles"));
                continue;
            }
            if (dimension) {
                if (!field.serverRequired())
                    diagnostics.add(unsupported(source.draftId(), field.id(), "serverRequired",
                            "NULLABLE_DIMENSION", Map.of("field", field.name())));
                if (!org.ipro.design.RegisterDraft.DIMENSION_TYPES.contains(field.type())) {
                    diagnostics.add(unsupported(source.draftId(), field.id(), "type", "REGISTER_DIMENSION",
                            Map.of("field", field.name(), "type", field.type().name())));
                } else if (field.type() == FieldType.REFERENCE) {
                    var target = field.referenceTypeId() == null ? null
                            : checked.context().referenceTargets().get(field.referenceTypeId());
                    if (target == null || !target.supported())
                        diagnostics.add(projection(source.draftId(), field.id(), "reference.typeId"));
                }
            } else {
                if (!org.ipro.design.RegisterDraft.RESOURCE_TYPES.contains(field.type())) {
                    diagnostics.add(unsupported(source.draftId(), field.id(), "type", "REGISTER_RESOURCE",
                            Map.of("field", field.name(), "type", field.type().name())));
                }
            }
        }
    }

    private static GenerationDiagnostic projection(UUID draftId, UUID fieldId, String property) {
        return new GenerationDiagnostic(GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE,
                new DraftProblem.Target(draftId, null, fieldId, property),
                messageKey(GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE), Map.of());
    }

    private static GenerationDiagnostic unsupported(UUID draftId, UUID fieldId, String property,
                                                    String construct, Map<String, String> parameters) {
        var values = new HashMap<String, String>(parameters);
        values.put("construct", construct);
        return new GenerationDiagnostic(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT,
                new DraftProblem.Target(draftId, null, fieldId, property),
                messageKey(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT), values);
    }

    private static String messageKey(GenerationDiagnostic.Code code) {
        return "entityGeneration." + code.name();
    }

    private static DraftProblem.Target target(UUID draftId, UUID fieldId, String property) {
        return new DraftProblem.Target(draftId, null, fieldId, property);
    }
}

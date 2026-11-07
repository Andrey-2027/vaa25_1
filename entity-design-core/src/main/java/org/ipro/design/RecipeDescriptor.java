package org.ipro.design;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Portable recipe description for UI and generation selection. It sits above the exact versioned
 * handoff contract in {@link RecipeCatalog} and never enables a recipe by itself.
 */
public record RecipeDescriptor(RecipeRef ref, String displayName, String description, String kind,
                               String baseType, String baseApiRoleAtCapture, RecipeCatalog.Phase phase,
                               String persistenceProfile, String writeProfile,
                               List<RecipeCatalog.InheritedField> inheritedFields,
                               Set<EditorConstruction> editorConstructions, List<String> constraints,
                               boolean draftSupported, boolean generatorImplemented,
                               List<String> unavailableReasons) {
    public enum EditorConstruction {
        FIELDS, OWNER, SECTIONS,
        REGISTER_ROLES, REGISTER_PERIOD_NONE, REGISTER_PERIOD_DAY,
        REGISTER_SCALAR_DIMENSION, REGISTER_REFERENCE_DIMENSION, REGISTER_RESOURCE
    }

    public java.util.Optional<RecipeGenerationProfile> generationProfile() {
        return RecipeGenerationProfile.find(ref);
    }
    public record FieldRole(String id, String label, String description, List<RecipeCatalog.FieldType> types, boolean requiredAtWrite) {
        public FieldRole { types = List.copyOf(types); }
    }
    public record PeriodMode(String id, String label) { }
    public List<FieldRole> fieldRoles() {
        return RecipeCatalog.isRegisterRecipe(ref) ? List.of(
            new FieldRole("DIMENSION", "Измерение", "Часть ключа; обязательно при записи. Длина строки: не более "
                + RegisterDraft.MAX_DIMENSION_STRING_LENGTH + ".", RegisterDraft.DIMENSION_TYPES, true),
            new FieldRole("RESOURCE", "Ресурс", "Значение записи. Длина строки: не более " + RegisterDraft.MAX_RESOURCE_STRING_LENGTH
                + "; точность decimal: не более " + RegisterDraft.MAX_RESOURCE_PRECISION + ".", RegisterDraft.RESOURCE_TYPES, false)) : List.of();
    }
    public List<RecipeCatalog.FieldType> fieldTypes(String role) {
        if (fieldRoles().isEmpty()) return List.of(RecipeCatalog.FieldType.values());
        var exact = fieldRoles().stream().filter(r -> r.id().equals(role)).findFirst();
        if (exact.isPresent()) return exact.orElseThrow().types();
        var types = new java.util.LinkedHashSet<RecipeCatalog.FieldType>();
        fieldRoles().forEach(r -> types.addAll(r.types()));
        return List.copyOf(types);
    }
    public List<PeriodMode> periodModes() {
        var modes = new java.util.ArrayList<PeriodMode>();
        if (editorConstructions.contains(EditorConstruction.REGISTER_PERIOD_NONE)) modes.add(new PeriodMode(RegisterDraft.NONE, "Без периода"));
        if (editorConstructions.contains(EditorConstruction.REGISTER_PERIOD_DAY)) modes.add(new PeriodMode(RegisterDraft.DAY, "По дням"));
        return List.copyOf(modes);
    }
    public java.util.Optional<FieldApplications> fixedPublications() {
        return RecipeCatalog.isRegisterRecipe(ref) ? java.util.Optional.of(FieldApplications.root()) : java.util.Optional.empty();
    }

    /** Resolved base and builtins for a mode, without changing the exact recipe identity. */
    public record EffectiveProfile(String baseType, String persistenceProfile, String writeProfile,
                                   String period, String periodColumn,
                                   List<RecipeCatalog.InheritedField> inheritedFields) {
        public EffectiveProfile { inheritedFields = List.copyOf(inheritedFields); }
    }

    public EffectiveProfile effectiveProfile(EntityDraft draft) {
        if (!ref.equals(draft.recipe())) throw new IllegalArgumentException("Recipe mismatch");
        return effectiveProfile(draft.register() == null ? null : draft.register().period());
    }

    public EffectiveProfile effectiveProfile(String period) {
        if (!RecipeCatalog.isRegisterRecipe(ref)) {
            if (period != null) throw new IllegalArgumentException("Period is not applicable to " + ref);
            return new EffectiveProfile(baseType, persistenceProfile, writeProfile, null, null, inheritedFields);
        }
        if (RecipeCatalog.REGISTER_NONE.equals(period))
            return new EffectiveProfile(RecipeCatalog.REGISTER_NONE_BASE, persistenceProfile, writeProfile,
                period, null, List.of());
        if (RecipeCatalog.REGISTER_DAY.equals(period))
            return new EffectiveProfile(RecipeCatalog.REGISTER_DAY_BASE, persistenceProfile, writeProfile,
                period, RecipeCatalog.REGISTER_PERIOD_COLUMN, List.of(new RecipeCatalog.InheritedField(
                    "builtin:period", "period", "Период", RecipeCatalog.FieldType.DATETIME, 0, true,
                    FieldDraft.UiRequired.REQUIRED, null, 2, true)));
        throw new IllegalArgumentException("Unsupported register period: " + period);
    }

    public RecipeDescriptor {
        Objects.requireNonNull(ref);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(description);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(baseType);
        Objects.requireNonNull(baseApiRoleAtCapture);
        Objects.requireNonNull(phase);
        Objects.requireNonNull(persistenceProfile);
        Objects.requireNonNull(writeProfile);
        inheritedFields = List.copyOf(inheritedFields);
        editorConstructions = Set.copyOf(editorConstructions);
        constraints = List.copyOf(constraints);
        unavailableReasons = List.copyOf(unavailableReasons);
        if (displayName.isBlank()) throw new IllegalArgumentException("Recipe display name is required");
        if (description.isBlank()) throw new IllegalArgumentException("Recipe description is required");
        if (generatorImplemented != RecipeGenerationProfile.find(ref).isPresent())
            throw new IllegalArgumentException("Generator description does not match the implemented profile");
    }
}

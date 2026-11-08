package org.ipro.design;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** One list of descriptions for the exact recipe set exposed by {@link RecipeCatalog#recipes()}. */
public final class RecipeDescriptors {
    private static final Map<String, RecipeDescriptor> BY_KEY;
    private static final List<RecipeDescriptor> ALL;

    static {
        var all = new ArrayList<RecipeDescriptor>();
        for (var recipe : RecipeCatalog.recipes()) all.add(describe(recipe));
        ALL = List.copyOf(all);
        var byKey = new HashMap<String, RecipeDescriptor>();
        for (var descriptor : ALL) {
            if (byKey.put(key(descriptor.ref()), descriptor) != null)
                throw new IllegalStateException("Duplicate recipe description: " + descriptor.ref());
        }
        BY_KEY = Map.copyOf(byKey);
    }

    private RecipeDescriptors() { }

    public static List<RecipeDescriptor> all() { return ALL; }

    public static Optional<RecipeDescriptor> find(RecipeRef ref) {
        return Optional.ofNullable(BY_KEY.get(key(ref)));
    }

    public static RecipeDescriptor require(RecipeRef ref) {
        return find(ref).orElseThrow(() -> new IllegalArgumentException("Unknown recipe: " + ref));
    }

    private static String key(RecipeRef ref) { return ref.id() + "@" + ref.version(); }

    private static RecipeDescriptor describe(RecipeCatalog.Recipe recipe) {
        return switch (RecipeCatalog.structuralRecipe(recipe.ref()).id()) {
            case "standard-catalog-v1" -> new RecipeDescriptor(recipe.ref(), "Справочник (v1)",
                    "Захваченный контракт стандартного справочника без раскрытия новых конструкций.",
                    "CATALOG", recipe.baseType(), recipe.baseApiRoleAtCapture(), recipe.phase(),
                    "STANDARD_CATALOG_ROOT", "CANONICAL_CRUD", RecipeCatalog.inheritedFields(),
                    Set.of(RecipeDescriptor.EditorConstruction.FIELDS, RecipeDescriptor.EditorConstruction.SECTIONS),
                    recipe.schemaConstraints(), recipe.draftSupported(), RecipeGenerationProfile.find(recipe.ref()).isPresent(),
                    List.of("Генератор Java для этого захваченного контракта не реализован"));
            case "standard-catalog-v2" -> new RecipeDescriptor(recipe.ref(), RecipeCatalog.formConfigurationRecipe(recipe.ref()) ? "Справочник (v3)" : "Справочник (v2)",
                    RecipeCatalog.formConfigurationRecipe(recipe.ref()) ? "Стандартный справочник с отдельной конфигурацией форм." : "Стандартный справочник с разделами и владельцем; из корня генерируется один Java-файл.",
                    "CATALOG", recipe.baseType(), recipe.baseApiRoleAtCapture(), recipe.phase(),
                    "STANDARD_CATALOG_ROOT", "CANONICAL_CRUD", RecipeCatalog.inheritedFields(),
                    Set.of(RecipeDescriptor.EditorConstruction.FIELDS, RecipeDescriptor.EditorConstruction.OWNER,
                            RecipeDescriptor.EditorConstruction.SECTIONS),
                    recipe.schemaConstraints(), recipe.draftSupported(), RecipeGenerationProfile.find(recipe.ref()).isPresent(), List.of());
            case "standard-document-v1" -> new RecipeDescriptor(recipe.ref(), RecipeCatalog.formConfigurationRecipe(recipe.ref()) ? "Документ (v2)" : "Документ",
                    "Стандартный документ с унаследованными номером и датой и принадлежащими разделами.",
                    "DOCUMENT", recipe.baseType(), recipe.baseApiRoleAtCapture(), recipe.phase(),
                    "STANDARD_DOCUMENT_ROOT", "CANONICAL_CRUD",
                    RecipeCatalog.inheritedFields(recipe.ref()),
                    Set.of(RecipeDescriptor.EditorConstruction.FIELDS, RecipeDescriptor.EditorConstruction.SECTIONS),
                    recipe.schemaConstraints(), recipe.draftSupported(), RecipeGenerationProfile.find(recipe.ref()).isPresent(), List.of());
            case "custom-plain-v1" -> new RecipeDescriptor(recipe.ref(), "Простая сущность",
                    "Сущность без навязанных полей; контракт черновика ещё не реализован.",
                    "PLAIN", recipe.baseType(), recipe.baseApiRoleAtCapture(), recipe.phase(),
                    "BASE_ENTITY_EXPLICIT", "CANONICAL_CRUD", List.of(),
                    Set.of(RecipeDescriptor.EditorConstruction.FIELDS),
                    recipe.schemaConstraints(), recipe.draftSupported(), RecipeGenerationProfile.find(recipe.ref()).isPresent(),
                    List.of("Контракт черновика и генератор для простой сущности не реализованы"));
            case "interned-catalog-v1" -> new RecipeDescriptor(recipe.ref(), "Интернируемый справочник",
                    "Справочник с естественным ключом, нормализацией и именованными операциями.",
                    "CATALOG", recipe.baseType(), recipe.baseApiRoleAtCapture(), recipe.phase(),
                    "INTERNED_CATALOG", "NAMED_OPERATIONS", List.of(),
                    Set.of(RecipeDescriptor.EditorConstruction.FIELDS),
                    recipe.schemaConstraints(), recipe.draftSupported(), RecipeGenerationProfile.find(recipe.ref()).isPresent(),
                    List.of("Контракт интернирования и генератор не реализованы"));
            case "independent-register-v1", "independent-register-v2" -> new RecipeDescriptor(recipe.ref(),
                    RecipeCatalog.formConfigurationRecipe(recipe.ref())
                        ? (RecipeCatalog.isRlsRegisterRecipe(recipe.ref()) ? "Независимый регистр с RLS (v4)" : "Независимый регистр (v3)")
                        : (RecipeCatalog.isRlsRegisterRecipe(recipe.ref()) ? "Независимый регистр с RLS" : "Независимый регистр"),
                    "Информационный регистр без регистратора: измерения, ресурсы и необязательный период DAY.",
                    "INFORMATION_REGISTER", recipe.baseType(), recipe.baseApiRoleAtCapture(), recipe.phase(),
                    "INDEPENDENT_REGISTER", RecipeCatalog.REGISTER_WRITE_PROFILE, List.of(),
                    Set.of(RecipeDescriptor.EditorConstruction.FIELDS, RecipeDescriptor.EditorConstruction.REGISTER_ROLES,
                            RecipeDescriptor.EditorConstruction.REGISTER_PERIOD_NONE,
                            RecipeDescriptor.EditorConstruction.REGISTER_PERIOD_DAY,
                            RecipeDescriptor.EditorConstruction.REGISTER_SCALAR_DIMENSION,
                            RecipeDescriptor.EditorConstruction.REGISTER_REFERENCE_DIMENSION,
                            RecipeDescriptor.EditorConstruction.REGISTER_RESOURCE),
                    registerConstraints(recipe, false), recipe.draftSupported(), RecipeGenerationProfile.find(recipe.ref()).isPresent(), List.of());
            case "recorded-register-v1" -> new RecipeDescriptor(recipe.ref(), "Регистр с регистратором",
                    "Подчинённый документу регистр; контракт регистратора и генерация не реализованы.",
                    "INFORMATION_REGISTER", recipe.baseType(), recipe.baseApiRoleAtCapture(), recipe.phase(),
                    "RECORDED_REGISTER", "RECORDER_ONLY", List.of(),
                    Set.of(RecipeDescriptor.EditorConstruction.FIELDS, RecipeDescriptor.EditorConstruction.REGISTER_ROLES,
                            RecipeDescriptor.EditorConstruction.REGISTER_PERIOD_NONE,
                            RecipeDescriptor.EditorConstruction.REGISTER_PERIOD_DAY),
                    registerConstraints(recipe, true), recipe.draftSupported(), RecipeGenerationProfile.find(recipe.ref()).isPresent(),
                    List.of("REGISTER_DRAFT_AND_OPERATION_CONTRACT", "Профиль записей регистратора не реализован"));
            default -> throw new IllegalStateException("No description for recipe: " + recipe.ref());
        };
    }

    private static List<String> registerConstraints(RecipeCatalog.Recipe recipe, boolean recorded) {
        var constraints = new ArrayList<>(recipe.schemaConstraints());
        constraints.add("period NONE or DAY; DAY uses inherited period and period_date");
        constraints.add("dimensions and resources are required; dimensions are immutable and not nullable");
        constraints.add("UNIQUE is exactly ordered dimensions followed by period_date when periodic");
        constraints.add("independent register has no sections, owner or subsystem; reference dimensions only");
        constraints.add(RecipeCatalog.formConfigurationRecipe(recipe.ref())
            ? "register entity and separate form configuration generated; recorded-register columns are outside this profile"
            : "one Java entry generated; recorded-register columns are outside this profile");
        if (recorded) constraints.add("recorder type codes, recorder columns and recorded operations are not supported");
        if (RecipeCatalog.isRlsRegisterRecipe(recipe.ref()))
            constraints.add("explicit RLS name and one reference dimension with Long identity; generated read/write policy; no automatic grants");
        return List.copyOf(constraints);
    }
}

package org.ipro.design;

import java.util.List;
import java.util.Set;

/** Versioned handoff contracts. Runtime facts are supplied by the host; no classes are loaded. */
public final class RecipeCatalog {
    private RecipeCatalog() {}
    public static final RecipeRef STANDARD_CATALOG = new RecipeRef("standard-catalog-v1", 1);
    public static final RecipeRef STANDARD_CATALOG_V2 = new RecipeRef("standard-catalog-v2", 2);
    public static final RecipeRef STANDARD_DOCUMENT = new RecipeRef("standard-document-v1", 1);
    public static final RecipeRef INDEPENDENT_REGISTER = new RecipeRef("independent-register-v1", 1);
    public static final String REGISTER_NONE = RegisterDraft.NONE, REGISTER_DAY = RegisterDraft.DAY;
    public static final String REGISTER_WRITE_PROFILE = RegisterDraft.NAMED_AND_CANONICAL_CRUD;
    public static final String REGISTER_PERIOD_COLUMN = "period_date";
    public static final String REGISTER_NONE_BASE = "org.ipro.crud.BaseEntity";
    public static final String REGISTER_DAY_BASE = "org.ipro.register.PeriodicRegisterEntryBase";
    public static final String BASE_TYPE = "org.ipro.crud.StandardCatalogEntity";
    public static final int MAX_FIELDS = 1000, MAX_SECTIONS = 50, MAX_TEXT = 4096;
    public enum FieldType { STRING, INTEGER, LONG, DECIMAL, BOOLEAN, DATE, DATETIME, REFERENCE }
    public record InheritedField(String key, String name, String label, FieldType type, int length,
                                 boolean serverRequired, FieldDraft.UiRequired declaredUiRequired,
                                 String numberingRole, int order, boolean editable) {}
    public static List<InheritedField> inheritedFields() {
        return List.of(new InheritedField("builtin:code", "code", "Код", FieldType.STRING, 50, true, FieldDraft.UiRequired.REQUIRED, "CATALOG_CODE", 10, false),
                new InheritedField("builtin:name", "name", "Наименование", FieldType.STRING, 200, true, FieldDraft.UiRequired.REQUIRED, null, 20, false));
    }
    /** Metadata contract only: this does not enable draft creation or generation. */
    public static List<InheritedField> inheritedFields(RecipeRef recipe) {
        if (STANDARD_CATALOG.equals(recipe) || STANDARD_CATALOG_V2.equals(recipe)) return inheritedFields();
        if (STANDARD_DOCUMENT.equals(recipe)) return List.of(
                new InheritedField("builtin:number", "number", "Номер", FieldType.STRING, 50, true,
                        FieldDraft.UiRequired.REQUIRED, "DOCUMENT_NUMBER", 10, false),
                new InheritedField("builtin:date", "date", "Дата", FieldType.DATE, 0, true,
                        FieldDraft.UiRequired.REQUIRED, null, 20, false));
        throw new IllegalArgumentException("No inherited field contract for recipe: " + recipe);
    }
    public static String baseType(RecipeRef recipe) {
        inheritedFields(recipe); // Restrict this API to the two standard base contracts.
        return STANDARD_DOCUMENT.equals(recipe) ? "org.ipro.crud.StandardDocumentEntity" : BASE_TYPE;
    }
    public static List<String> defaultPublications(RecipeRef recipe) {
        return inheritedFields(recipe).stream().map(InheritedField::name).toList();
    }
    public static Set<String> systemFieldNames() { return Set.of("id", "version", "createdAt", "modifiedAt", "createdBy", "modifiedBy"); }
    /** Names a register draft cannot declare as fields; {@code period} is builtin only for DAY. */
    public static Set<String> registerSystemFieldNames(String period) {
        var names = new java.util.HashSet<>(systemFieldNames());
        if (REGISTER_DAY.equals(period)) names.add("period");
        return Set.copyOf(names);
    }
    public static boolean isRegisterRecipe(RecipeRef ref) { return INDEPENDENT_REGISTER.equals(ref); }
    public static String registerBaseType(String period) {
        return descriptor(INDEPENDENT_REGISTER).orElseThrow().effectiveProfile(period).baseType();
    }
    public static boolean generationEnabled() { return false; }
    public static List<String> generationPrerequisites() { return List.of("GENERATION_PLAN_AND_COMPILE_GATE"); }
    public enum Phase { CAPTURED, DRAFT_READY, HANDOFF_ONLY }
    public record Recipe(RecipeRef ref, String kind, String baseType, String baseApiRoleAtCapture, Phase phase,
                         Set<String> reads, Set<String> writes, List<String> runtimePrerequisites,
                         List<String> subjectDecisions, List<String> files, List<String> schemaConstraints,
                         List<String> acceptanceFixtures, List<String> draftBlockers) {
        public Recipe {
            reads=Set.copyOf(reads); writes=Set.copyOf(writes); runtimePrerequisites=List.copyOf(runtimePrerequisites);
            subjectDecisions=List.copyOf(subjectDecisions); files=List.copyOf(files);
            schemaConstraints=List.copyOf(schemaConstraints); acceptanceFixtures=List.copyOf(acceptanceFixtures);
            draftBlockers=List.copyOf(draftBlockers);
        }
        public boolean draftSupported() { return phase != Phase.HANDOFF_ONLY; }
        public boolean generationEnabled() { return false; }
    }
    public record Readiness(RecipeRef recipe, boolean runtimeReady,
                            java.util.Map<String, RuntimeDesignSnapshot.Prerequisite> currentPrerequisites,
                            List<String> blockers) {
        public Readiness { currentPrerequisites=java.util.Map.copyOf(currentPrerequisites); blockers=List.copyOf(blockers); }
        public boolean generationEnabled() { return false; }
    }
    /** These require real G1 owners. Host assertions can never manufacture an implemented generator. */
    public static List<String> g1Prerequisites() {
        return List.of("GENERATION_PLAN_CREATE_MODIFY_SOURCE_HASHES", "REVIEWABLE_DIFF",
            "SAFE_FILE_ADAPTER_WITH_ROLLBACK", "MANUAL_EDITS_AND_REPEATABILITY",
            "EXPLICIT_DB_MIGRATION_PLAN", "RECIPE_COMPILE_STARTUP_BEHAVIOR_GATE");
    }
    public static List<Recipe> recipes() {
        var reads=Set.of("LIST","DETAIL","LOOKUP");
        var writes=Set.of("CREATE","UPDATE","DELETE");
        var standardFiles=List.of("root entity Java", "owned row Java per section", "compile/runtime acceptance fixture");
        return List.of(
            new Recipe(STANDARD_CATALOG,"CATALOG",BASE_TYPE,"INTERNAL",Phase.CAPTURED,reads,writes,
                List.of("STANDARD_CATALOG_APP_API","ET0_ET7_RUNTIME"),List.of("catalog subject fields and publication","C5/RLS policy; no automatic grants"),
                standardFiles,List.of("code VARCHAR(50) NOT NULL; name VARCHAR(200) NOT NULL","no implicit UNIQUE(code)","owned parent NOT NULL, aggregate boundary"),
                List.of("EntityTypesAcceptanceTest","ET5 section 0/1/2 and rollback"),List.of()),
            new Recipe(STANDARD_CATALOG_V2,"CATALOG",BASE_TYPE,"APP_API",Phase.DRAFT_READY,reads,writes,
                List.of("STANDARD_CATALOG_APP_API","ET0_ET7_RUNTIME"),List.of("catalog subject fields and publication","C5/RLS policy; no automatic grants"),
                standardFiles,List.of("code VARCHAR(50) NOT NULL; name VARCHAR(200) NOT NULL","no implicit UNIQUE(code)","MUTABLE_REPLACE_ALL; INHERIT_ROOT; parent NOT NULL"),
                List.of("EntityDesignRuntimeProjectionIT","FieldApplicationRuntimeParityTest","EntityTypesAcceptanceTest"),List.of()),
            new Recipe(STANDARD_DOCUMENT,"DOCUMENT","org.ipro.crud.StandardDocumentEntity","APP_API",Phase.DRAFT_READY,reads,writes,
                List.of("STANDARD_DOCUMENT_APP_API","ET0_ET7_RUNTIME"),List.of("document date semantics","owned sections","numbering DOCUMENT_NUMBER","C5/RLS policy"),
                standardFiles,List.of("inherited number/date mapping","section parent and cascade ownership; no implicit posting"),
                List.of("EntityTypesAcceptanceTest: Order, TwoSectionDocument, CustomDocument"),List.of()),
            new Recipe(new RecipeRef("custom-plain-v1",1),"PLAIN","org.ipro.crud.BaseEntity","APP_SPI",Phase.HANDOFF_ONLY,reads,writes,
                List.of("ET0_ET7_RUNTIME"),List.of("explicit subject fields and presentation","optional numbering roles","C5/RLS policy"),
                standardFiles,List.of("explicit column names/types/constraints; no imposed code/name"),
                List.of("FieldApplicationRuntimeParityTest: PlainProbe"),List.of("CUSTOM_PLAIN_DRAFT_CONTRACT")),
            new Recipe(new RecipeRef("interned-catalog-v1",1),"CATALOG","org.ipro.crud.BaseEntity","APP_SPI",Phase.HANDOFF_ONLY,reads,Set.of(),
                List.of("ET0_ET7_RUNTIME","ET8_INTERNING"),List.of("natural key branches, normalization and NULL semantics","owner-managed creation or confirmed generic CREATE exception","named operations and permissions"),
                List.of("root entity Java with InternedContract","domain normalization/findOrCreate Java","compile/runtime acceptance fixture"),
                List.of("exact natural-key UNIQUE, NULLS DISTINCT, NOT NULL active branch","no generic UPDATE/DELETE; CREATE only by explicit exception"),
                List.of("InternedPostgresIT","AttributeValueServiceTest","SklNomOpaServiceTest","EntityTypesAcceptanceTest: InternedLabel"),List.of("INTERNED_IDENTITY_AND_OPERATIONS_DRAFT_CONTRACT")),
            registerRecipe("independent-register-v1",false,reads),registerRecipe("recorded-register-v1",true,reads));
    }
    private static Recipe registerRecipe(String id, boolean recorded, Set<String> reads) {
        return new Recipe(new RecipeRef(id,1),"INFORMATION_REGISTER","org.ipro.crud.BaseEntity",
            "APP_SPI",recorded ? Phase.HANDOFF_ONLY : Phase.DRAFT_READY,reads,Set.of(),List.of("ET6_ET7_REGISTER_CLASSIFICATION","REGISTER_ENGINE_OPERATIONS"),
            List.of("dimensions/resources/periodicity","register write/key semantics",recorded ? "implements RecordedRegisterEntry; recorder type codes and owner boundary" : "independent replacement boundary"),
            List.of("register entity Java","register engine operation adapter","compile/runtime acceptance fixture"),
            List.of("validated register descriptor/key columns",recorded ? "recorder columns and allowed managed DOCUMENT types" : "independent natural-key constraints"),
            List.of("RegisterStartupValidatorTest","RegisterWriteConcurrencyPostgresIT"),
            recorded ? List.of("REGISTER_DRAFT_AND_OPERATION_CONTRACT") : List.of());
    }
    public static java.util.Optional<Recipe> find(RecipeRef ref) {
        return recipes().stream().filter(recipe -> recipe.ref().equals(ref)).findFirst();
    }
    /** UI-independent descriptions of exactly the registered recipes. */
    public static List<RecipeDescriptor> descriptors() { return RecipeDescriptors.all(); }
    public static java.util.Optional<RecipeDescriptor> descriptor(RecipeRef ref) { return RecipeDescriptors.find(ref); }
    public static boolean supportsDraft(RecipeRef ref) { return find(ref).map(Recipe::draftSupported).orElse(false); }
    public static Readiness readiness(RecipeRef ref, RuntimeDesignSnapshot snapshot) {
        var recipe=find(ref).orElseThrow(() -> new IllegalArgumentException("Unknown recipe: "+ref));
        var facts=new java.util.TreeMap<String,RuntimeDesignSnapshot.Prerequisite>();
        var blockers=new java.util.ArrayList<String>();
        for (String prerequisite : recipe.runtimePrerequisites()) {
            var fact=snapshot.prerequisite(prerequisite); facts.put(prerequisite,fact);
            if (fact.status()!=RuntimeDesignSnapshot.Status.AVAILABLE) blockers.add(prerequisite);
        }
        boolean runtimeReady=snapshot.complete() && blockers.isEmpty();
        if (!snapshot.complete()) blockers.add("COMPLETE_RUNTIME_SNAPSHOT");
        blockers.addAll(recipe.draftBlockers()); blockers.addAll(g1Prerequisites());
        return new Readiness(ref,runtimeReady,facts,blockers);
    }
    public static ConstraintDraft initialConstraints(String type) {
        return switch (type) {
            case "STRING" -> new ConstraintDraft("255", "", "");
            case "DECIMAL" -> new ConstraintDraft("", "19", "2");
            default -> ConstraintDraft.empty();
        };
    }
    public static String snake(String value) {
        return value.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }
}

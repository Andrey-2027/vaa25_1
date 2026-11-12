package org.ipro.design;

import java.util.List;
import java.util.Set;

/** Versioned handoff contracts. Runtime facts are supplied by the host; no classes are loaded. */
public final class RecipeCatalog {
    private RecipeCatalog() {}
    public static final RecipeRef STANDARD_CATALOG = new RecipeRef("standard-catalog-v1", 1);
    public static final RecipeRef STANDARD_CATALOG_V2 = new RecipeRef("standard-catalog-v2", 2);
    public static final RecipeRef STANDARD_CATALOG_V3 = new RecipeRef("standard-catalog-v3", 3);
    public static final RecipeRef STANDARD_DOCUMENT_V2 = new RecipeRef("standard-document-v2", 2);
    public static final RecipeRef INDEPENDENT_REGISTER_V3 = new RecipeRef("independent-register-v3", 3);
    public static final RecipeRef INDEPENDENT_REGISTER_RLS_V4 = new RecipeRef("independent-register-v4", 4);
    public static final RecipeRef STANDARD_CATALOG_V4 = new RecipeRef("standard-catalog-v4", 4);
    public static final RecipeRef STANDARD_DOCUMENT_V3 = new RecipeRef("standard-document-v3", 3);
    public static final RecipeRef INDEPENDENT_REGISTER_V5 = new RecipeRef("independent-register-v5", 5);
    public static final RecipeRef INDEPENDENT_REGISTER_RLS_V6 = new RecipeRef("independent-register-v6", 6);
    public static final RecipeRef CUSTOM_PLAIN_V2 = new RecipeRef("custom-plain-v2", 2);
    public static final int PLAIN_SCHEMA_VERSION = 5;
    public static boolean strictFormSettingsRecipe(RecipeRef ref) {
        return Set.of(STANDARD_CATALOG_V4, STANDARD_DOCUMENT_V3, INDEPENDENT_REGISTER_V5, INDEPENDENT_REGISTER_RLS_V6,
            CUSTOM_PLAIN_V2).contains(ref);
    }
    /** Structural predecessor only; generation keeps the exact selected version. */
    public static RecipeRef structuralRecipe(RecipeRef ref) {
        if (STANDARD_CATALOG_V4.equals(ref)) return STANDARD_CATALOG_V2;
        if (STANDARD_DOCUMENT_V3.equals(ref)) return STANDARD_DOCUMENT;
        if (INDEPENDENT_REGISTER_V5.equals(ref)) return INDEPENDENT_REGISTER;
        if (INDEPENDENT_REGISTER_RLS_V6.equals(ref)) return INDEPENDENT_REGISTER_RLS;
        if (STANDARD_CATALOG_V3.equals(ref)) return STANDARD_CATALOG_V2;
        if (STANDARD_DOCUMENT_V2.equals(ref)) return STANDARD_DOCUMENT;
        if (INDEPENDENT_REGISTER_V3.equals(ref)) return INDEPENDENT_REGISTER;
        if (INDEPENDENT_REGISTER_RLS_V4.equals(ref)) return INDEPENDENT_REGISTER_RLS;
        return ref;
    }
    public static boolean formConfigurationRecipe(RecipeRef ref) {
        return CUSTOM_PLAIN_V2.equals(ref) || !structuralRecipe(ref).equals(ref);
    }
    public static boolean isRlsRegisterRecipe(RecipeRef ref) { return INDEPENDENT_REGISTER_RLS.equals(structuralRecipe(ref)); }
    public static boolean isStandardExtension(RecipeRef ref) {
        return STANDARD_CATALOG_V2.equals(structuralRecipe(ref)) || STANDARD_DOCUMENT.equals(structuralRecipe(ref));
    }
    public static final RecipeRef STANDARD_DOCUMENT = new RecipeRef("standard-document-v1", 1);
    /**
     * Marks a draft read from an existing Java file. It is a working mode, not a recipe: it is
     * deliberately absent from {@link #recipes()}, so it cannot be chosen for creation or imported.
     */
    public static final RecipeRef EXISTING_ENTITY = new RecipeRef("existing-entity", 1);
    public static final RecipeRef INDEPENDENT_REGISTER = new RecipeRef("independent-register-v1", 1);
    public static final RecipeRef INDEPENDENT_REGISTER_RLS = new RecipeRef("independent-register-v2", 2);
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
        if (CUSTOM_PLAIN_V2.equals(recipe)) return List.of();
        recipe = structuralRecipe(recipe);
        if (STANDARD_CATALOG.equals(recipe) || STANDARD_CATALOG_V2.equals(recipe)) return inheritedFields();
        if (STANDARD_DOCUMENT.equals(recipe)) return List.of(
                new InheritedField("builtin:number", "number", "Номер", FieldType.STRING, 50, true,
                        FieldDraft.UiRequired.REQUIRED, "DOCUMENT_NUMBER", 10, false),
                new InheritedField("builtin:date", "date", "Дата", FieldType.DATE, 0, true,
                        FieldDraft.UiRequired.REQUIRED, null, 20, false));
        throw new IllegalArgumentException("No inherited field contract for recipe: " + recipe);
    }
    public static String baseType(RecipeRef recipe) {
        if (CUSTOM_PLAIN_V2.equals(recipe)) return "org.ipro.crud.BaseEntity";
        inheritedFields(recipe); // Restrict this API to supported root base contracts.
        return STANDARD_DOCUMENT.equals(structuralRecipe(recipe)) ? "org.ipro.crud.StandardDocumentEntity" : BASE_TYPE;
    }
    /**
     * A platform class an entity may extend. {@code fields} and {@code methods} are the names the
     * class itself declares; a subclass inherits them through {@code parent}.
     */
    public record PlatformBase(String typeId, String parent, List<String> fields, List<String> methods) {}
    private static final List<PlatformBase> PLATFORM_BASES = List.of(
        new PlatformBase("org.ipro.crud.BaseEntity", null,
            List.of("id", "version", "createdAt", "modifiedAt", "createdBy", "modifiedBy"),
            List.of("getId", "setId", "getVersion", "setVersion", "getCreatedAt", "setCreatedAt", "getModifiedAt",
                "setModifiedAt", "getCreatedBy", "setCreatedBy", "getModifiedBy", "setModifiedBy", "equals", "hashCode")),
        new PlatformBase(BASE_TYPE, "org.ipro.crud.BaseEntity", List.of("code", "name"),
            List.of("getCode", "setCode", "getName", "setName", "getDisplayName", "toString")),
        new PlatformBase("org.ipro.crud.StandardDocumentEntity", "org.ipro.crud.BaseEntity", List.of("number", "date"),
            List.of("getNumber", "setNumber", "getDate", "setDate", "getDisplayName", "toString")),
        new PlatformBase(REGISTER_DAY_BASE, "org.ipro.crud.BaseEntity", List.of("period"), List.of("getPeriod", "setPeriod")),
        new PlatformBase("org.ipro.register.RecordedRegisterEntryBase", "org.ipro.crud.BaseEntity",
            List.of("recorder", "lineNumber", "recordingOrder"),
            List.of("getRecorder", "setRecorder", "getLineNumber", "setLineNumber", "getRecordingOrder", "setRecordingOrder")),
        new PlatformBase("org.ipro.register.PeriodicRecordedRegisterEntryBase", "org.ipro.register.RecordedRegisterEntryBase",
            List.of("period"), List.of("getPeriod", "setPeriod")));
    /** The platform base classes whose members a draft of an existing entity must not shadow. */
    public static List<PlatformBase> platformBases() { return PLATFORM_BASES; }
    public static java.util.Optional<PlatformBase> platformBase(String typeId) {
        return PLATFORM_BASES.stream().filter(base -> base.typeId().equals(typeId)).findFirst();
    }
    public static List<String> defaultPublications(RecipeRef recipe) {
        return inheritedFields(recipe).stream().map(InheritedField::name).toList();
    }
    /** The ten words the captured {@code standard-catalog-v1} rejects; its semantics never change. */
    private static final Set<String> CAPTURED_RESERVED_STORAGE_WORDS = Set.of(
        "select", "from", "where", "table", "user", "order", "group", "primary", "references", "constraint");
    /**
     * Words that cannot name an unquoted table or column: PostgreSQL 17 categories R and T of
     * {@code pg_get_keywords()} (reserved, also reserved as function or type), plus the words H2 2.4
     * (the Spring Boot 4.0 default) rejects, checked with a one-off {@code CREATE TABLE} per word.
     * Draft names are never quoted, so any of them would break the DDL on one of the two databases.
     */
    private static final Set<String> RESERVED_STORAGE_WORDS = Set.copyOf(java.util.Arrays.asList((
        "all analyse analyze and any array as asc asymmetric authorization binary both case cast check collate collation "
        + "column concurrently constraint create cross current_catalog current_date current_role current_schema current_time "
        + "current_timestamp current_user default deferrable desc distinct do else end except false fetch for foreign freeze "
        + "from full grant group having ilike in initially inner intersect into is isnull join lateral leading left like limit "
        + "localtime localtimestamp natural not notnull null offset on only or order outer overlaps placing primary references "
        + "returning right select session_user similar some symmetric system_user table tablesample then to trailing true "
        + "union unique user using variadic verbose when where window with "
        + "between current_path day exists hour if interval key minus minute month qualify row rownum second set uescape "
        + "unknown value values year").split(" ")));
    /** Reserved storage words that apply to the drafts of {@code recipe}. */
    public static Set<String> reservedStorageWords(RecipeRef recipe) {
        return STANDARD_CATALOG.equals(recipe) ? CAPTURED_RESERVED_STORAGE_WORDS : RESERVED_STORAGE_WORDS;
    }
    public static Set<String> systemFieldNames() { return Set.of("id", "version", "createdAt", "modifiedAt", "createdBy", "modifiedBy"); }
    /** Names a register draft cannot declare as fields; {@code period} is builtin only for DAY. */
    public static Set<String> registerSystemFieldNames(String period) {
        var names = new java.util.HashSet<>(systemFieldNames());
        if (REGISTER_DAY.equals(period)) names.add("period");
        return Set.copyOf(names);
    }
    public static boolean isRegisterRecipe(RecipeRef ref) { return INDEPENDENT_REGISTER.equals(structuralRecipe(ref)) || isRlsRegisterRecipe(ref); }
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
    private static Recipe formsRecipe(RecipeRef ref, RecipeRef predecessor) {
        var base = legacyRecipes().stream().filter(r -> r.ref().equals(predecessor)).findFirst().orElseThrow();
        var files = new java.util.ArrayList<>(base.files());
        files.add("entity form configuration Java with explicit list/selection composition");
        return new Recipe(ref, base.kind(), base.baseType(), base.baseApiRoleAtCapture(), base.phase(),
            base.reads(), base.writes(), base.runtimePrerequisites(), base.subjectDecisions(), files,
            base.schemaConstraints(), base.acceptanceFixtures(), base.draftBlockers());
    }
    public static List<Recipe> recipes() {
        var all = new java.util.ArrayList<>(legacyRecipes());
        all.add(formsRecipe(STANDARD_CATALOG_V3, STANDARD_CATALOG_V2));
        all.add(formsRecipe(STANDARD_DOCUMENT_V2, STANDARD_DOCUMENT));
        all.add(formsRecipe(INDEPENDENT_REGISTER_V3, INDEPENDENT_REGISTER));
        all.add(formsRecipe(INDEPENDENT_REGISTER_RLS_V4, INDEPENDENT_REGISTER_RLS));
        all.add(formsRecipe(STANDARD_CATALOG_V4, STANDARD_CATALOG_V2));
        all.add(formsRecipe(STANDARD_DOCUMENT_V3, STANDARD_DOCUMENT));
        all.add(formsRecipe(INDEPENDENT_REGISTER_V5, INDEPENDENT_REGISTER));
        all.add(formsRecipe(INDEPENDENT_REGISTER_RLS_V6, INDEPENDENT_REGISTER_RLS));
        all.add(new Recipe(CUSTOM_PLAIN_V2, "PLAIN", "org.ipro.crud.BaseEntity", "APP_SPI",
            Phase.DRAFT_READY, Set.of("LIST", "DETAIL", "LOOKUP"), Set.of("CREATE", "UPDATE", "DELETE"),
            List.of("ET0_ET7_RUNTIME"),
            List.of("explicit entity identity, table and label",
                "explicit required string field as InstanceName and default search path",
                "standard item, list and selection forms with explicit FieldApplications",
                "STANDARD_ROOT canonical CRUD; no automatic permissions or RLS rules"),
            List.of("root entity Java with FormSettingsOnly", "separate FormConfigurationFor Java",
                "root table DDL", "compile and runtime acceptance fixture"),
            List.of("at least one own scalar field; no implicit code, name, numbering or uniqueness",
                "required nonblank string presentation field published in item, list and selection",
                "no reference fields, owned sections or owner",
                "DDL matches Java mapping, inherited identity/version/audit and declared nullability"),
            List.of("Reason with one required caption: JSON, Java, forms, DDL and canonical CRUD"),
            List.of()));
        return List.copyOf(all);
    }
    private static List<Recipe> legacyRecipes() {
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
            registerRecipe("independent-register-v1",false,reads), registerRlsRecipe(reads),
            registerRecipe("recorded-register-v1",true,reads));
    }
    private static Recipe registerRlsRecipe(Set<String> reads) {
        var base = registerRecipe("independent-register-v1", false, reads);
        return new Recipe(INDEPENDENT_REGISTER_RLS, base.kind(), base.baseType(), base.baseApiRoleAtCapture(),
            base.phase(), base.reads(), base.writes(),
            List.of("ET6_ET7_REGISTER_CLASSIFICATION", "REGISTER_ENGINE_OPERATIONS", "RLS_NUMERIC_REFERENCE_FILTERS"),
            List.of("explicit RLS dimension and reference dimension field; no automatic grants"),
            base.files(), base.schemaConstraints(), base.acceptanceFixtures(), base.draftBlockers());
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

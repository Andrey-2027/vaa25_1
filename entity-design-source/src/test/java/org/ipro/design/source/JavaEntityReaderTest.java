package org.ipro.design.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.ipro.design.DraftText;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.existing.EntityShape;
import org.ipro.design.existing.ExistingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JavaEntityReaderTest {
    private static final String CONSUMER_PACKAGE = "com.example.entitytypes.model.";
    private static ProjectSources consumer, application;

    @BeforeAll static void scan() {
        if (Files.isDirectory(TestSources.CONSUMER)) consumer = ProjectSources.scan(List.of(TestSources.CONSUMER));
        if (Files.isDirectory(TestSources.APPLICATION_MODEL)) application = ProjectSources.scan(List.of(TestSources.APPLICATION_MODEL));
    }

    private static ExistingEntity consumer(String simple) {
        TestSources.existing(TestSources.CONSUMER);
        String typeId = CONSUMER_PACKAGE + simple;
        return JavaEntityReader.read(typeId, TestSources.read(TestSources.CONSUMER, consumer.type(typeId).orElseThrow().relativePath()), consumer);
    }
    private static ExistingEntity application(String simple) {
        TestSources.existing(TestSources.APPLICATION_MODEL);
        String typeId = "org.ip.model." + simple;
        return JavaEntityReader.read(typeId, TestSources.read(TestSources.APPLICATION_MODEL, application.type(typeId).orElseThrow().relativePath()), application);
    }
    private static FieldDraft field(ExistingEntity entity, String name) {
        return entity.draft().fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name + " in " + entity.draft().fields()));
    }
    private static String reason(ExistingEntity entity, String name) {
        return entity.lockedFields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name)).reason();
    }

    // ------------------------------------------------------------------ real models

    @Test void customCatalogHasAnnotationsOnOneLineAndNoTableAnnotation() {
        var entity = consumer("CustomCatalog");
        assertEquals(EntityShape.ROOT, entity.shape());
        assertEquals("custom_catalog", entity.tableName());
        assertEquals(RecipeCatalog.EXISTING_ENTITY, entity.draft().recipe());
        var key = field(entity, "externalKey");
        assertEquals("STRING", key.type());
        assertEquals("Внешний ключ", key.label());
        assertTrue(key.serverRequired());
        assertEquals("12", key.constraints().length());
        assertEquals(DraftText.auto(), key.columnName());
        assertEquals(FieldApplications.root(), key.applications());
        var caption = field(entity, "caption");
        assertFalse(caption.serverRequired());
        assertEquals("255", caption.constraints().length());
        assertEquals(List.of("externalKey", "caption"), entity.draft().fields().stream().map(FieldDraft::name).toList());
        assertTrue(entity.lockedFields().isEmpty());
        assertEquals(Set.of("id", "version", "createdAt", "modifiedAt", "createdBy", "modifiedBy"), entity.inheritedNames());
        assertEquals("Внешние обозначения", entity.draft().entity().titles().list().text());
        assertEquals(DraftText.Mode.AUTO, entity.draft().entity().titles().item().mode());
        assertEquals("com/example/entitytypes/model/CustomCatalog.java", entity.relativePath());
        assertEquals(64, entity.sourceSha256().length());
    }

    @Test void productWithAnEmptyBodyHasNoFieldsAndInheritsCodeAndName() {
        var entity = consumer("Product");
        assertTrue(entity.draft().fields().isEmpty());
        assertTrue(entity.lockedFields().isEmpty());
        assertTrue(entity.inheritedNames().containsAll(Set.of("code", "name", "id")));
        assertEquals("product", entity.tableName());
        assertTrue(entity.occupiedMethodNames().containsAll(Set.of("getDisplayName", "getCode", "setName", "getId")));
    }

    @Test void extendedProductLocksTheFieldWithoutMetadata() {
        var entity = consumer("ExtendedProduct");
        var description = field(entity, "description");
        assertEquals("Описание", description.label());
        assertEquals("NO_FIELD_METADATA", reason(entity, "storageOnly"));
        assertEquals("storage_only", entity.lockedFields().getFirst().columnName());
        assertEquals("String", entity.lockedFields().getFirst().javaType());
    }

    @Test void orderIgnoresStaticFieldsInitializersAndConstructorsButKeepsItsRlsAnnotationsOutOfTheModel() {
        var entity = consumer("Order");
        assertEquals("et5_order", entity.tableName());
        assertEquals(List.of("teamId"), entity.draft().fields().stream().map(FieldDraft::name).toList());
        var team = field(entity, "teamId");
        assertEquals("LONG", team.type());
        assertTrue(team.serverRequired());
        assertEquals(DraftText.explicit("team_id"), team.columnName());
        assertFalse(entity.inheritedNames().contains("TEAM"));
        assertTrue(entity.inheritedNames().containsAll(Set.of("number", "date")));
        assertTrue(entity.occupiedMethodNames().containsAll(Set.of("getTeamId", "setTeamId", "getNumber")));
        assertEquals(EntityShape.ROOT, entity.shape());
    }

    @Test void orderLineIsASectionRowAndPublishesToTheTable() {
        var entity = consumer("OrderLine");
        assertEquals(EntityShape.SECTION_ROW, entity.shape());
        var product = field(entity, "product");
        assertEquals("REFERENCE", product.type());
        assertEquals(CONSUMER_PACKAGE + "Product", product.reference().typeId());
        assertEquals(new FieldApplications(true, false, false, true), product.applications());
        assertEquals("NO_FIELD_METADATA", reason(entity, "owner"));
        assertEquals("NO_FIELD_METADATA", reason(entity, "line"));
    }

    @Test void workshopReadsReferencesRequiredModesAndOrder() {
        var entity = application("Workshop");
        assertEquals(List.of("code", "name", "branch"), entity.draft().fields().stream().map(FieldDraft::name).toList());
        var code = field(entity, "code");
        assertEquals(FieldDraft.UiRequired.REQUIRED, code.uiRequired());
        assertEquals("20", code.constraints().length());
        var branch = field(entity, "branch");
        assertEquals("org.ip.model.Branch", branch.reference().typeId());
        assertEquals(FieldDraft.UiRequired.OPTIONAL, branch.uiRequired());
        assertEquals(DraftText.explicit("branch_id"), branch.columnName());
        assertFalse(branch.serverRequired());
        assertTrue(entity.occupiedMethodNames().contains("getBranch"));
        assertEquals("Цеха", entity.draft().entity().titles().list().text());
        assertEquals("COGS", entity.draft().entity().icon());
    }

    @Test void receivingDocumentAndItsInheritanceChainThroughAMappedSuperclass() {
        var entity = application("ReceivingDocument");
        assertEquals(EntityShape.ROOT, entity.shape());
        var correction = application("AccountingObjectCorrection");
        assertEquals(EntityShape.ROOT, correction.shape());
        assertTrue(correction.inheritedNames().containsAll(Set.of("number", "date", "workshop", "id")));
        assertTrue(correction.occupiedMethodNames().containsAll(Set.of("getNumber", "getWorkshop")));
    }

    @Test void aRegisterIsReadButMarkedAsRegister() {
        var entity = application("NomenclaturePrice");
        assertEquals(EntityShape.REGISTER, entity.shape());
        assertEquals("price", field(entity, "price").columnName().text());
        assertEquals("19", field(entity, "price").constraints().precision());
        assertEquals("4", field(entity, "price").constraints().scale());
        assertTrue(entity.inheritedNames().contains("period"));
    }

    @Test void everyEntityOfBothModelsIsReadWithoutRefusalAndTwiceGivesEqualDrafts() {
        TestSources.existing(TestSources.CONSUMER);
        TestSources.existing(TestSources.APPLICATION_MODEL);
        int read = 0;
        for (var project : List.of(consumer, application)) {
            for (var type : project.types()) {
                if (!type.isEntity()) continue;
                String text = TestSources.read(type.root(), type.relativePath());
                var first = JavaEntityReader.read(type.typeId(), text, project);
                var second = JavaEntityReader.read(type.typeId(), text, project);
                assertEquals(first, second, type.typeId());
                read++;
            }
        }
        assertTrue(read > 30, "read " + read);
    }

    // ------------------------------------------------------------------ synthetic

    private static final String HEADER = """
        package t;
        import jakarta.persistence.*;
        import jakarta.validation.constraints.*;
        import java.math.BigDecimal;
        import java.time.*;
        import org.ipro.crud.BaseEntity;
        import org.ipro.metadata.annotation.*;
        """;

    private ExistingEntity synthetic(Path dir, String classAnnotations, String body, Map<String, String> extra) throws Exception {
        for (var entry : extra.entrySet()) Files.writeString(dir.resolve(entry.getKey()), entry.getValue());
        String text = HEADER + "@Entity " + classAnnotations + "\npublic class Probe extends BaseEntity {\n" + body + "\n}\n";
        Files.writeString(dir.resolve("Probe.java"), text);
        var project = ProjectSources.scan(List.of(dir));
        return JavaEntityReader.read("t.Probe", text, project);
    }
    private ExistingEntity synthetic(Path dir, String body) throws Exception { return synthetic(dir, "", body, Map.of()); }

    @Test void severalNamesInOneDeclarationLockAllOfThem(@TempDir Path dir) throws Exception {
        var entity = synthetic(dir, "@FieldMetadata(label = \"Пара\") private String a, b;\n@FieldMetadata private String c;");
        assertEquals("MULTIPLE_NAMES", reason(entity, "a"));
        assertEquals("MULTIPLE_NAMES", reason(entity, "b"));
        assertEquals(List.of("c"), entity.draft().fields().stream().map(FieldDraft::name).toList());
        assertEquals("Пара", entity.lockedFields().getFirst().label());
    }

    @Test void aConstantInsteadOfALiteralLocksTheFieldAndNeverBecomesADefault(@TempDir Path dir) throws Exception {
        var entity = synthetic(dir, """
            @Size(max = Limits.MAX) @FieldMetadata private String length;
            @FieldMetadata(label = LABEL) private String label;
            @FieldMetadata(order = ORDER) private String order;
            @FieldMetadata(hidden = HIDDEN) private String hidden;
            @FieldMetadata(required = REQUIRED) private String required;
            @FieldMetadata(required = Modes.REQUIRED) private String foreignMode;
            @FieldMetadata(grid = GRID) private String grid;
            @Column(name = NAMES.COLUMN) @FieldMetadata private String column;
            @Column(nullable = FLAG) @FieldMetadata private String nullable;
            @Digits(integer = INTS, fraction = 2) @FieldMetadata private BigDecimal digits;
            @FieldMetadata private String ok;
            """);
        assertEquals("NOT_LITERAL:length", reason(entity, "length"));
        assertEquals("NOT_LITERAL:label", reason(entity, "label"));
        assertEquals("NOT_LITERAL:order", reason(entity, "order"));
        assertEquals("NOT_LITERAL:hidden", reason(entity, "hidden"));
        assertEquals("NOT_LITERAL:required", reason(entity, "required"));
        assertEquals("NOT_LITERAL:required", reason(entity, "foreignMode"));
        assertEquals("NOT_LITERAL:grid", reason(entity, "grid"));
        assertEquals("NOT_LITERAL:column", reason(entity, "column"));
        assertEquals("NOT_LITERAL:nullable", reason(entity, "nullable"));
        assertEquals("NOT_LITERAL:precision", reason(entity, "digits"));
        assertEquals(List.of("ok"), entity.draft().fields().stream().map(FieldDraft::name).toList());
    }

    @Test void columnLengthWinsOverSizeAndPrecisionComesFromColumnOrDigits(@TempDir Path dir) throws Exception {
        var entity = synthetic(dir, """
            @Column(length = 30) @Size(max = 50) @FieldMetadata private String both;
            @Size(max = 50) @FieldMetadata private String size;
            @FieldMetadata private String plain;
            @Column(precision = 12, scale = 3) @Digits(integer = 5, fraction = 1) @FieldMetadata private BigDecimal column;
            @Digits(integer = 8, fraction = 2) @FieldMetadata private BigDecimal digits;
            @FieldMetadata private BigDecimal unset;
            """);
        assertEquals("30", field(entity, "both").constraints().length());
        assertEquals("50", field(entity, "size").constraints().length());
        assertEquals("255", field(entity, "plain").constraints().length());
        assertEquals(List.of("12", "3"), List.of(field(entity, "column").constraints().precision(), field(entity, "column").constraints().scale()));
        assertEquals(List.of("10", "2"), List.of(field(entity, "digits").constraints().precision(), field(entity, "digits").constraints().scale()));
        assertEquals(List.of("", ""), List.of(field(entity, "unset").constraints().precision(), field(entity, "unset").constraints().scale()));
    }

    @Test void serverRequirementComesFromBeanValidationOrJpaButNotForPrimitives(@TempDir Path dir) throws Exception {
        var entity = synthetic(dir, """
            @NotNull @FieldMetadata private String notNull;
            @NotBlank @FieldMetadata private String notBlank;
            @Column(nullable = false) @FieldMetadata private String column;
            @ManyToOne(optional = false) @FieldMetadata private Probe parent;
            @JoinColumn(nullable = false) @ManyToOne @FieldMetadata private Probe join;
            @Column(nullable = false) @FieldMetadata private int primitive;
            @NotNull @FieldMetadata private int validated;
            @FieldMetadata private String optional;
            """);
        assertEquals(Map.of("notNull", true, "notBlank", true, "column", true, "parent", true, "join", true,
                "primitive", false, "validated", true, "optional", false),
            entity.draft().fields().stream().collect(Collectors.toMap(FieldDraft::name, FieldDraft::serverRequired)));
        assertEquals("INTEGER", field(entity, "primitive").type());
    }

    @Test void typesThatAreNotUnderstoodAreLockedWithTheirReason(@TempDir Path dir) throws Exception {
        var entity = synthetic(dir, "", """
            @FieldMetadata private Kind kind;
            @FieldMetadata private Double ratio;
            @FieldMetadata private double primitiveRatio;
            @FieldMetadata private java.util.List<String> tags;
            @FieldMetadata private byte[] blob;
            @FieldMetadata private Missing missing;
            @ManyToOne @FieldMetadata private Missing target;
            @OneToOne @FieldMetadata private Probe one;
            @Transient @FieldMetadata private String temporary;
            @FieldMetadata private transient String javaTransient;
            @Id @FieldMetadata private Long key;
            @Enumerated(EnumType.STRING) @FieldMetadata private String stored;
            @ManyToOne @FieldMetadata private Kind badReference;
            """, Map.of("Kind.java", "package t;\npublic enum Kind { A }\n"));
        assertEquals("ENUM", reason(entity, "kind"));
        assertEquals("TYPE_NOT_SUPPORTED", reason(entity, "ratio"));
        assertEquals("TYPE_NOT_SUPPORTED", reason(entity, "primitiveRatio"));
        assertEquals("COLLECTION", reason(entity, "tags"));
        assertEquals("TYPE_NOT_SUPPORTED", reason(entity, "blob"));
        assertEquals("TYPE_UNRESOLVED", reason(entity, "missing"));
        assertEquals("REFERENCE_UNRESOLVED", reason(entity, "target"));
        assertEquals("ASSOCIATION_NOT_SUPPORTED", reason(entity, "one"));
        assertEquals("TRANSIENT", reason(entity, "temporary"));
        assertEquals("TRANSIENT", reason(entity, "javaTransient"));
        assertEquals("ID", reason(entity, "key"));
        assertEquals("ENUM", reason(entity, "stored"));
        assertEquals("ENUM", reason(entity, "badReference"));
        assertTrue(entity.draft().fields().isEmpty());
        assertEquals("Kind", entity.lockedFields().getFirst().javaType());
    }

    @Test void manualListsWinOverGridFlagsAndPathsPublishTheReference(@TempDir Path dir) throws Exception {
        var entity = synthetic(dir, "@EntityMetadata(listColumns = {\"code\", \"owner.name\"}, selectColumns = \"code\")", """
            @FieldMetadata(order = 1) private String code;
            @FieldMetadata(order = 2, grid = @GridColumn(visible = false)) private String name;
            @FieldMetadata(order = 3) @ManyToOne private Probe owner;
            """, Map.of());
        assertEquals(new FieldApplications(true, true, true, false), field(entity, "code").applications());
        assertEquals(new FieldApplications(true, false, false, false), field(entity, "name").applications());
        assertEquals(new FieldApplications(true, true, false, false), field(entity, "owner").applications());
    }

    @Test void anEmptyManualListFallsBackToGridFlagsAndSelectionFollowsTheList(@TempDir Path dir) throws Exception {
        var entity = synthetic(dir, "@EntityMetadata(listColumns = {})", """
            @FieldMetadata private String shown;
            @FieldMetadata(grid = @GridColumn(visible = false), hidden = true) private String hiddenOne;
            """, Map.of());
        assertEquals(new FieldApplications(true, true, true, false), field(entity, "shown").applications());
        assertEquals(new FieldApplications(false, false, false, false), field(entity, "hiddenOne").applications());
    }

    @Test void aSingleValueListIsAListOfOne(@TempDir Path dir) throws Exception {
        var entity = synthetic(dir, "@EntityMetadata(listColumns = \"b\")", "@FieldMetadata private String a;\n@FieldMetadata private String b;", Map.of());
        assertFalse(field(entity, "a").applications().list());
        assertTrue(field(entity, "b").applications().list());
        assertTrue(field(entity, "b").applications().selection());
    }

    @Test void aListThatIsAConstantLocksEveryFieldBecausePublicationIsUnknown(@TempDir Path dir) throws Exception {
        var entity = synthetic(dir, "@EntityMetadata(listColumns = COLUMNS)", "@FieldMetadata private String a;\n@FieldMetadata private String b;", Map.of());
        assertTrue(entity.draft().fields().isEmpty());
        assertEquals(List.of("a", "b"), entity.lockedFields().stream().map(f -> f.name()).toList());
        assertEquals("NOT_LITERAL:listColumns", reason(entity, "a"));
    }

    @Test void fieldsAreOrderedByDeclaredOrderWithStableTies(@TempDir Path dir) throws Exception {
        var entity = synthetic(dir, """
            @FieldMetadata(order = 30) private String third;
            @FieldMetadata private String lastByDefault;
            @FieldMetadata(order = 10) private String first;
            @FieldMetadata(order = 30) private String thirdToo;
            """);
        assertEquals(List.of("first", "third", "thirdToo", "lastByDefault"), entity.draft().fields().stream().map(FieldDraft::name).toList());
    }

    @Test void labelDefaultsToTheFieldNameAndUuidIsStablePerTypeAndName(@TempDir Path dir) throws Exception {
        var entity = synthetic(dir, "@FieldMetadata private String bare;");
        var bare = field(entity, "bare");
        assertEquals("bare", bare.label());
        assertEquals(java.util.UUID.nameUUIDFromBytes("t.Probe#bare".getBytes(java.nio.charset.StandardCharsets.UTF_8)), bare.id());
        assertEquals(DraftText.auto(), bare.columnName());
    }

    @Test void implicitColumnsThatDifferFromTheEditorSuggestionAreWrittenDown(@TempDir Path dir) throws Exception {
        var entity = synthetic(dir, "@FieldMetadata private String xmlParser;\n@FieldMetadata private String lineNumber;\n@ManyToOne @FieldMetadata private Probe parentLink;");
        assertEquals(DraftText.auto(), field(entity, "xmlParser").columnName(), "xml_parser both ways");
        assertEquals(DraftText.auto(), field(entity, "lineNumber").columnName());
        assertEquals(DraftText.auto(), field(entity, "parentLink").columnName());
        var acronym = synthetic(dir, "@FieldMetadata private String getURLValue;");
        assertEquals(DraftText.explicit("geturlvalue"), field(acronym, "getURLValue").columnName());
    }

    @Test void classesThatAreNotEntitiesAreRefused(@TempDir Path dir) throws Exception {
        var project = ProjectSources.scan(List.of(dir));
        var plain = assertThrows(SourceRefusal.class, () -> JavaEntityReader.read("t.Probe", "package t;\npublic class Probe {}\n", project));
        assertEquals(SourceRefusal.Code.NOT_AN_ENTITY_CLASS, plain.code());
        var other = assertThrows(SourceRefusal.class, () -> JavaEntityReader.read("t.Other", "package t;\n@jakarta.persistence.Entity\npublic class Probe {}\n", project));
        assertEquals(SourceRefusal.Code.NOT_AN_ENTITY_CLASS, other.code());
        var broken = assertThrows(SourceRefusal.class, () -> JavaEntityReader.read("t.Probe", "package t;\npublic class Probe {", project));
        assertEquals(SourceRefusal.Code.SOURCE_UNPARSABLE, broken.code());
    }

    @Test void anUnresolvedBaseIsReportedAsSuch(@TempDir Path dir) throws Exception {
        var project = ProjectSources.scan(List.of(dir));
        var entity = JavaEntityReader.read("t.Probe", "package t;\n@jakarta.persistence.Entity\npublic class Probe extends Unknown {}\n", project);
        assertEquals(EntityShape.UNRESOLVED_BASE, entity.shape());
        var noBase = JavaEntityReader.read("t.Probe", "package t;\n@jakarta.persistence.Entity\npublic class Probe {}\n", project);
        assertEquals(EntityShape.UNRESOLVED_BASE, noBase.shape());
    }

    @Test void methodsOfKnownSubclassesAreOccupiedToo(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Child.java"), "package t;\n@jakarta.persistence.Entity\npublic class Child extends Probe { public String getAddress() { return null; } }\n");
        var entity = synthetic(dir, "", "@FieldMetadata private String name;\npublic void setAddress(StringBuilder b) {}", Map.of());
        assertTrue(entity.occupiedMethodNames().containsAll(Set.of("setAddress", "getAddress")));
    }
}

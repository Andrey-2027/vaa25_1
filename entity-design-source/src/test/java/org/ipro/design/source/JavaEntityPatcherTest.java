package org.ipro.design.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftText;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.existing.EntityChange;
import org.ipro.design.existing.ExistingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JavaEntityPatcherTest {
    private static final String CONSUMER = "com.example.entitytypes.model.";
    private static ProjectSources consumer;

    @BeforeAll static void scan() {
        if (java.nio.file.Files.isDirectory(TestSources.CONSUMER)) consumer = ProjectSources.scan(List.of(TestSources.CONSUMER));
    }

    // ------------------------------------------------------------------ helpers

    static FieldDraft field(String name, String type, boolean form, boolean list, boolean selection) {
        return new FieldDraft(UUID.nameUUIDFromBytes(name.getBytes()), name, "Подпись " + name, type, DraftText.auto(), false,
            FieldDraft.UiRequired.AUTO, switch (type) {
                case "STRING" -> new ConstraintDraft("200", "", "");
                case "DECIMAL" -> new ConstraintDraft("", "19", "3");
                default -> ConstraintDraft.empty();
            }, type.equals("REFERENCE") ? new ReferenceDraft(CONSUMER + "Product") : null, new FieldApplications(form, list, selection, false));
    }
    static FieldDraft field(String name, String type) { return field(name, type, true, true, true); }
    static EntityChange add(FieldDraft field) { return new EntityChange.FieldAdded(field); }

    private record Subject(String text, ExistingEntity entity, ProjectSources project) {
        JavaEntityPatcher.Patch patch(EntityChange... changes) { return JavaEntityPatcher.patch(text, entity, List.of(changes), project); }
        String apply(EntityChange... changes) { return patch(changes).text(); }
    }
    private static Subject consumerFile(String simple) {
        TestSources.existing(TestSources.CONSUMER);
        String id = CONSUMER + simple;
        String text = TestSources.read(TestSources.CONSUMER, consumer.type(id).orElseThrow().relativePath());
        return new Subject(text, JavaEntityReader.read(id, text, consumer), consumer);
    }
    private static Subject text(Path dir, String text) throws Exception {
        Files.writeString(dir.resolve("Probe.java"), text);
        var project = ProjectSources.scan(List.of(dir));
        return new Subject(text, JavaEntityReader.read("t.Probe", text, project), project);
    }
    private static final String HEAD = "package t;\n\nimport jakarta.persistence.*;\nimport org.ipro.crud.BaseEntity;\nimport org.ipro.metadata.annotation.*;\n\n";
    private static Subject probe(Path dir, String annotations, String body) throws Exception {
        return text(dir, HEAD + "@Entity " + annotations + "\npublic class Probe extends BaseEntity {\n" + body + "}\n");
    }

    /** Every byte of the original survives outside the edits, and the result is the original with the edits. */
    private static void assertOnlyEdits(String original, JavaEntityPatcher.Patch patch) {
        var out = new StringBuilder(); int from = 0;
        for (var edit : patch.edits()) { out.append(original, from, edit.start()).append(edit.text()); from = edit.end(); }
        assertEquals(patch.text(), out.append(original.substring(from)).toString());
    }

    private static List<String> editTexts(JavaEntityPatcher.Patch patch) { return patch.edits().stream().map(SourceEdits.Edit::text).toList(); }

    // ------------------------------------------------------------------ real files

    @Test void customCatalogGetsTheFieldAfterTheLastFieldAndItsAccessorsAfterTheLastAccessor() {
        var subject = consumerFile("CustomCatalog");
        var patch = subject.patch(add(field("address", "STRING")));
        assertOnlyEdits(subject.text(), patch);
        assertEquals(List.of(
            "import jakarta.validation.constraints.Size;\n",
            "\n\n    @Size(max = 200)\n    @Column(name = \"address\", length = 200)\n    @FieldMetadata(label = \"Подпись address\")\n    private String address;",
            "\n\n    public String getAddress() {\n        return address;\n    }\n\n    public void setAddress(String address) {\n        this.address = address;\n    }"),
            editTexts(patch));
        assertTrue(patch.text().indexOf("private String address;") > patch.text().indexOf("private String caption;"));
        assertTrue(patch.text().indexOf("getAddress") > patch.text().indexOf("setCaption"));
        assertTrue(patch.text().contains("import jakarta.validation.constraints.NotBlank;\nimport jakarta.validation.constraints.Size;\nimport org.ipro.crud.BaseEntity;"));
    }

    @Test void anEmptyBodyOfAStandardCatalogGetsThe30AndASortedImportBlock() {
        var subject = consumerFile("Product");
        var patch = subject.patch(add(field("weight", "DECIMAL")));
        assertOnlyEdits(subject.text(), patch);
        assertTrue(patch.text().contains("public class Product extends StandardCatalogEntity {\n    @Digits(integer = 16, fraction = 3)\n"
            + "    @Column(name = \"weight\", precision = 19, scale = 3)\n"
            + "    @FieldMetadata(label = \"Подпись weight\", order = 30, grid = @GridColumn(order = 30))\n    private BigDecimal weight;\n\n"
            + "    public BigDecimal getWeight() {"), patch.text());
        assertTrue(patch.text().endsWith("    }\n}\n"), patch.text());
        assertTrue(patch.text().contains("import jakarta.persistence.Column;\nimport jakarta.persistence.Entity;\nimport jakarta.validation.constraints.Digits;\n"
            + "import java.math.BigDecimal;\nimport org.ipro.crud.StandardCatalogEntity;\nimport org.ipro.metadata.annotation.FieldMetadata;\nimport org.ipro.metadata.annotation.GridColumn;\n"));
    }

    @Test void extendedProductContinuesItsOwnNumbering() {
        var patch = consumerFile("ExtendedProduct").patch(add(field("weight", "DECIMAL")));
        assertTrue(patch.text().contains("order = 40, grid = @GridColumn(order = 40))"));
        assertTrue(patch.text().indexOf("private BigDecimal weight;") > patch.text().indexOf("private String storageOnly;"));
        assertTrue(patch.text().indexOf("getWeight") > patch.text().indexOf("setStorageOnly"));
    }

    @Test void orderKeepsItsStaticFieldConstructorAndRlsAnnotations() {
        var subject = consumerFile("Order");
        var patch = subject.patch(add(field("note", "STRING")));
        assertOnlyEdits(subject.text(), patch);
        assertTrue(patch.text().contains("@Filter(name = Order.TEAM, condition = \"team_id in (:allowedIds)\")"));
        assertTrue(patch.text().contains("    private Long teamId = 1L;\n\n    @Size(max = 200)"));
        assertTrue(patch.text().indexOf("setTeamId") < patch.text().indexOf("getNote"));
        assertTrue(patch.text().contains("order = 40, grid = @GridColumn(order = 40))"));
        // The import block is unsorted, so the new import goes to the end of the block.
        assertTrue(patch.text().contains("import java.time.LocalDate;\nimport jakarta.validation.constraints.Size;\n"));
    }

    @Test void aSecondPatchOfAnAlreadyPatchedFileWorks(@TempDir Path dir) throws Exception {
        var first = consumerFile("CustomCatalog");
        String once = first.apply(add(field("address", "STRING")));
        var second = text(dir, once.replace("package com.example.entitytypes.model;", "package t;").replace("class CustomCatalog", "class Probe"));
        String twice = second.apply(add(field("phone", "STRING")));
        assertTrue(twice.indexOf("private String phone;") > twice.indexOf("private String address;"));
        assertTrue(twice.indexOf("getPhone") > twice.indexOf("setAddress"));
    }

    // ------------------------------------------------------------------ layout

    @Test void aCrLfFileGetsOnlyCrLfInsertions(@TempDir Path dir) throws Exception {
        String crlf = (HEAD + "@Entity\npublic class Probe extends BaseEntity {\n    @FieldMetadata\n    private String a;\n\n    public String getA() { return a; }\n}\n").replace("\n", "\r\n");
        var subject = text(dir, crlf);
        var patch = subject.patch(add(field("b", "STRING")));
        assertOnlyEdits(crlf, patch);
        for (String inserted : editTexts(patch)) assertFalse(inserted.matches("(?s).*[^\r]\n.*"), inserted);
        assertFalse(patch.text().matches("(?s).*[^\r]\n.*"));
    }

    @Test void aFileWithoutAFinalLineBreakKeepsItsEnd(@TempDir Path dir) throws Exception {
        var subject = text(dir, (HEAD + "@Entity\npublic class Probe extends BaseEntity {\n    @FieldMetadata\n    private String a;\n    public String getA() { return a; }\n}"));
        String result = subject.apply(add(field("b", "STRING")));
        assertTrue(result.endsWith("    }\n}"), result);
    }

    @Test void orderFollowsTheFileWhenOrdersRepeatAreMissingOrAreUnreadable(@TempDir Path dir) throws Exception {
        String equal = probe(dir, "", "    @FieldMetadata(order = 10) private String a;\n    @FieldMetadata(order = 10) private String b;\n").apply(add(field("c", "STRING")));
        assertTrue(equal.contains("order = 20, grid = @GridColumn(order = 20)"), equal);
        String unset = probe(dir, "", "    @FieldMetadata private String a;\n").apply(add(field("c", "STRING")));
        assertFalse(unset.contains("order ="), unset);
        assertFalse(unset.contains("grid"), unset);
        String manual = probe(dir, "", "    @FieldMetadata(order = 1) private String a;\n    @FieldMetadata(order = 2) private String b;\n").apply(add(field("c", "STRING")));
        assertTrue(manual.contains("order = 3, grid = @GridColumn(order = 3)"), manual);
        String spread = probe(dir, "", "    @FieldMetadata(order = 10) private String a;\n    @FieldMetadata(order = 25) private String b;\n").apply(add(field("c", "STRING")));
        assertTrue(spread.contains("order = 40, grid = @GridColumn(order = 40)"), spread);
        String opaque = probe(dir, "", "    @FieldMetadata(order = ORDER) private String a;\n").apply(add(field("c", "STRING")));
        assertEquals(1, opaque.split("order =", -1).length - 1, opaque);
    }

    @Test void twoNewFieldsAreNumberedInSequenceAndPlacedTogether(@TempDir Path dir) throws Exception {
        var subject = probe(dir, "", "    @FieldMetadata(order = 1) private String a;\n    public String getA() { return a; }\n");
        String result = subject.apply(add(field("b", "STRING")), add(field("c", "INTEGER")));
        assertTrue(result.contains("order = 11,") || result.contains("order = 2"), result);
        assertTrue(result.indexOf("private String b;") < result.indexOf("private Integer c;"));
        assertTrue(result.indexOf("getB") < result.indexOf("getC"));
        assertTrue(result.indexOf("getA") < result.indexOf("getB"));
    }

    @Test void aClassWithOnlyMethodsGetsTheFieldRightAfterTheBrace(@TempDir Path dir) throws Exception {
        var subject = probe(dir, "", "    public Probe() { }\n");
        var patch = subject.patch(add(field("a", "STRING")));
        assertOnlyEdits(subject.text(), patch);
        assertTrue(patch.text().contains("extends BaseEntity {\n    @Size(max = 200)\n"), patch.text());
        assertTrue(patch.text().contains("    }\n\n    public Probe() { }\n}"), patch.text());
    }

    @Test void aClassWithOnlyStaticFieldsGetsTheFieldAfterTheLastStaticOne(@TempDir Path dir) throws Exception {
        String result = probe(dir, "", "    public static final String TEAM = \"T\";\n").apply(add(field("a", "STRING")));
        assertTrue(result.contains("TEAM = \"T\";\n\n    @Size(max = 200)"), result);
    }

    // ------------------------------------------------------------------ publication

    private static final String LISTS = "@EntityMetadata(listColumns = {\"code\"}, selectColumns = {\"code\"})";

    @Test void aFieldInTheListsIsAppendedToBothManualLists(@TempDir Path dir) throws Exception {
        String result = probe(dir, LISTS, "    @FieldMetadata private String code;\n").apply(add(field("a", "STRING")));
        assertTrue(result.contains("listColumns = {\"code\", \"a\"}, selectColumns = {\"code\", \"a\"}"), result);
        assertTrue(result.contains("@FieldMetadata(label = \"Подпись a\", grid = @GridColumn(visible = false))"), result);
    }

    @Test void aFieldOutsideTheListsLeavesThemAloneAndStaysOutOfTheGrid(@TempDir Path dir) throws Exception {
        String result = probe(dir, LISTS, "    @FieldMetadata private String code;\n").apply(add(field("a", "STRING", true, false, false)));
        assertTrue(result.contains(LISTS), result);
        assertTrue(result.contains("grid = @GridColumn(visible = false))"), result);
    }

    @Test void aFieldInTheListOnlyIsAppendedToTheListOnlyWhenSelectionIsManual(@TempDir Path dir) throws Exception {
        String result = probe(dir, LISTS, "    @FieldMetadata private String code;\n").apply(add(field("a", "STRING", true, true, false)));
        assertTrue(result.contains("listColumns = {\"code\", \"a\"}, selectColumns = {\"code\"}"), result);
    }

    @Test void aSingleValueListBecomesAnArray(@TempDir Path dir) throws Exception {
        String result = probe(dir, "@EntityMetadata(listColumns = \"code\")", "    @FieldMetadata private String code;\n").apply(add(field("a", "STRING")));
        assertTrue(result.contains("listColumns = {\"code\", \"a\"})"), result);
    }

    @Test void anEmptyListMeansNoManualList(@TempDir Path dir) throws Exception {
        String result = probe(dir, "@EntityMetadata(listColumns = {})", "    @FieldMetadata(order = 5) private String code;\n").apply(add(field("a", "STRING")));
        assertTrue(result.contains("listColumns = {}"), result);
        assertTrue(result.contains("order = 15, grid = @GridColumn(order = 15)"), result);
    }

    @Test void selectionThatDiffersFromTheListNeedsAManualSelectList(@TempDir Path dir) throws Exception {
        var subject = probe(dir, "@EntityMetadata(listColumns = {\"code\"})", "    @FieldMetadata private String code;\n");
        var refused = assertThrows(SourceRefusal.class, () -> subject.apply(add(field("a", "STRING", true, true, false))));
        assertEquals(SourceRefusal.Code.MODIFICATION_UNSUPPORTED, refused.code());
        assertEquals(SourceRefusal.Code.MODIFICATION_UNSUPPORTED, assertThrows(SourceRefusal.class, () -> subject.apply(add(field("a", "STRING", true, false, true)))).code());
        String same = subject.apply(add(field("a", "STRING", true, true, true)));
        assertTrue(same.contains("listColumns = {\"code\", \"a\"}"), same);
        var none = probe(dir, "", "    @FieldMetadata private String code;\n");
        assertEquals(SourceRefusal.Code.MODIFICATION_UNSUPPORTED, assertThrows(SourceRefusal.class, () -> none.apply(add(field("a", "STRING", true, false, true)))).code());
    }

    @Test void aConstantListRefusesOnlyAFieldThatNeedsToBeInIt(@TempDir Path dir) throws Exception {
        var subject = probe(dir, "@EntityMetadata(listColumns = COLUMNS, selectColumns = COLUMNS)", "    @FieldMetadata private String code;\n");
        assertEquals(SourceRefusal.Code.MODIFICATION_UNSUPPORTED, assertThrows(SourceRefusal.class, () -> subject.apply(add(field("a", "STRING")))).code());
        String outside = subject.apply(add(field("a", "STRING", true, false, false)));
        assertTrue(outside.contains("COLUMNS, selectColumns = COLUMNS"), outside);
    }

    @Test void aFieldOutsideTheFormIsHiddenAndWithoutAnyListsStaysOutOfTheGrid(@TempDir Path dir) throws Exception {
        String result = probe(dir, "", "    @FieldMetadata private String code;\n").apply(add(field("a", "STRING", false, false, false)));
        assertTrue(result.contains("@FieldMetadata(label = \"Подпись a\", hidden = true, grid = @GridColumn(visible = false))"), result);
    }

    @Test void anExplicitRequiredModeIsWrittenWithItsImport(@TempDir Path dir) throws Exception {
        var required = field("a", "STRING");
        var edited = new FieldDraft(required.id(), "a", "A", "STRING", DraftText.auto(), false, FieldDraft.UiRequired.REQUIRED, required.constraints(), null, required.applications());
        String result = probe(dir, "", "    @FieldMetadata private String code;\n").apply(add(edited));
        assertTrue(result.contains("@FieldMetadata(label = \"A\", required = RequiredMode.REQUIRED)"), result);
    }

    @Test void aReferenceIsWrittenWithItsFullyQualifiedTypeAndImports(@TempDir Path dir) throws Exception {
        String result = text(dir, "package t;\n\n@jakarta.persistence.Entity\npublic class Probe extends org.ipro.crud.BaseEntity {\n}\n").apply(add(field("product", "REFERENCE")));
        assertTrue(result.contains("    @ManyToOne(fetch = FetchType.LAZY, optional = true)\n    @JoinColumn(name = \"product_id\")\n"), result);
        assertTrue(result.contains("private com.example.entitytypes.model.Product product;"), result);
        assertTrue(result.startsWith("package t;\n\nimport jakarta.persistence.Column;\nimport jakarta.persistence.FetchType;\nimport jakarta.persistence.JoinColumn;\n"
            + "import jakarta.persistence.ManyToOne;\nimport org.ipro.metadata.annotation.FieldMetadata;\n\n@jakarta.persistence.Entity"), result);
    }

    // ------------------------------------------------------------------ imports

    @Test void coveredImportsAreNotTouched(@TempDir Path dir) throws Exception {
        var subject = text(dir, "package t;\n\nimport jakarta.persistence.*;\nimport jakarta.validation.constraints.Size;\nimport org.ipro.crud.BaseEntity;\nimport org.ipro.metadata.annotation.FieldMetadata;\n"
            + "import org.ipro.metadata.annotation.GridColumn;\n\n@Entity\npublic class Probe extends BaseEntity {\n    @FieldMetadata private String a;\n}\n");
        var patch = subject.patch(add(field("b", "STRING")));
        assertEquals(1, patch.edits().size(), "the field and its accessors only: no import is needed");
    }

    @Test void aWildcardCoversThePersistenceNamesButNotOthers(@TempDir Path dir) throws Exception {
        var patch = probe(dir, "", "    @FieldMetadata private String a;\n").patch(add(field("b", "DECIMAL")));
        assertTrue(patch.text().contains("import jakarta.persistence.*;\nimport jakarta.validation.constraints.Digits;\nimport java.math.BigDecimal;\nimport org.ipro.crud.BaseEntity;"), patch.text());
    }

    @Test void aFileWithoutImportsGetsThemAfterThePackage(@TempDir Path dir) throws Exception {
        String result = text(dir, "package t;\n\n@jakarta.persistence.Entity\npublic class Probe extends org.ipro.crud.BaseEntity {\n}\n").apply(add(field("a", "STRING")));
        assertTrue(result.startsWith("package t;\n\nimport jakarta.persistence.Column;\nimport jakarta.validation.constraints.Size;\n"
            + "import org.ipro.metadata.annotation.FieldMetadata;\n\n@jakarta.persistence.Entity"), result);
    }

    @Test void aFileWithoutAPackageOrImportsGetsThemFirst(@TempDir Path dir) throws Exception {
        var project = ProjectSources.scan(List.of(dir));
        String text = "@jakarta.persistence.Entity\npublic class Probe extends org.ipro.crud.BaseEntity {\n}\n";
        var entity = JavaEntityReader.read("Probe", text, project);
        String result = JavaEntityPatcher.apply(text, entity, List.of(add(field("a", "STRING"))));
        assertTrue(result.startsWith("import jakarta.persistence.Column;\n"), result);
        assertTrue(result.contains("\n\n@jakarta.persistence.Entity"), result);
    }

    @Test void aNameTakenByAnotherTypeIsRefused(@TempDir Path dir) throws Exception {
        var imported = text(dir, "package t;\n\nimport other.Size;\nimport jakarta.persistence.*;\nimport org.ipro.crud.BaseEntity;\nimport org.ipro.metadata.annotation.*;\n\n@Entity\npublic class Probe extends BaseEntity {\n}\n");
        assertEquals(SourceRefusal.Code.IMPORT_CONFLICT, assertThrows(SourceRefusal.class, () -> imported.apply(add(field("a", "STRING")))).code());
        var declared = text(dir, HEAD + "@Entity\npublic class Probe extends BaseEntity {\n    static class Column {}\n}\n");
        assertEquals(SourceRefusal.Code.IMPORT_CONFLICT, assertThrows(SourceRefusal.class, () -> declared.apply(add(field("a", "STRING")))).code());
        Files.writeString(dir.resolve("Size.java"), "package t;\npublic class Size {}\n");
        var samePackage = text(dir, HEAD + "@Entity\npublic class Probe extends BaseEntity {\n}\n");
        assertEquals(SourceRefusal.Code.IMPORT_CONFLICT, assertThrows(SourceRefusal.class, () -> samePackage.apply(add(field("a", "STRING")))).code());
    }

    @Test void importsAreAlphabeticalInASortedBlockAndAppendedOtherwise(@TempDir Path dir) throws Exception {
        var sorted = text(dir, "package t;\n\nimport jakarta.persistence.Entity;\nimport org.ipro.crud.BaseEntity;\n\n@Entity\npublic class Probe extends BaseEntity {\n}\n");
        assertTrue(sorted.apply(add(field("a", "INTEGER"))).contains("import jakarta.persistence.Column;\nimport jakarta.persistence.Entity;\n"));
        var unsorted = text(dir, "package t;\n\nimport org.ipro.crud.BaseEntity;\nimport jakarta.persistence.Entity;\n\n@Entity\npublic class Probe extends BaseEntity {\n}\n");
        assertTrue(unsorted.apply(add(field("a", "INTEGER"))).contains("import jakarta.persistence.Entity;\nimport jakarta.persistence.Column;\n"));
    }

    // ------------------------------------------------------------------ label

    @Test void aLabelInAMultiLineAnnotationIsReplacedInPlace(@TempDir Path dir) throws Exception {
        var subject = text(dir, HEAD + "@Entity\npublic class Probe extends BaseEntity {\n    @FieldMetadata(\n        label = \"Старая\", order = 1,\n        grid = @GridColumn(order = 1)\n    )\n    private String a;\n}\n");
        var patch = subject.patch(new EntityChange.LabelChanged(subject.entity().draft().fields().getFirst().id(), "a", "Старая", "Новая"));
        assertOnlyEdits(subject.text(), patch);
        assertEquals(subject.text().replace("\"Старая\"", "\"Новая\""), patch.text());
        assertEquals(1, patch.edits().size());
    }

    @Test void aLabelInASingleLineAnnotationAndQuotesAreEscaped(@TempDir Path dir) throws Exception {
        var subject = text(dir, HEAD + "@Entity\npublic class Probe extends BaseEntity {\n    @FieldMetadata(label = \"Старая\") private String a;\n}\n");
        String result = subject.apply(new EntityChange.LabelChanged(subject.entity().draft().fields().getFirst().id(), "a", "Старая", "Он сказал \"да\"\\\nи ушёл"));
        assertTrue(result.contains("@FieldMetadata(label = \"Он сказал \\\"да\\\"\\\\\\nи ушёл\") private String a;"), result);
    }

    @Test void aMissingLabelArgumentIsAdded(@TempDir Path dir) throws Exception {
        for (String written : List.of("@FieldMetadata", "@FieldMetadata()", "@FieldMetadata(order = 3)", "@FieldMetadata( order = 3, hidden = true )")) {
            var subject = text(dir, HEAD + "@Entity\npublic class Probe extends BaseEntity {\n    " + written + " private String a;\n}\n");
            var field = subject.entity().draft().fields().getFirst();
            String result = subject.apply(new EntityChange.LabelChanged(field.id(), "a", "a", "Адрес"));
            String expected = switch (written) {
                case "@FieldMetadata" -> "@FieldMetadata(label = \"Адрес\")";
                case "@FieldMetadata()" -> "@FieldMetadata(label = \"Адрес\")";
                case "@FieldMetadata(order = 3)" -> "@FieldMetadata(label = \"Адрес\", order = 3)";
                default -> "@FieldMetadata( label = \"Адрес\", order = 3, hidden = true )";
            };
            assertTrue(result.contains(expected), result);
        }
    }

    @Test void aLabelThatIsNotALiteralOrHasNoFieldIsRefused(@TempDir Path dir) throws Exception {
        var subject = text(dir, HEAD + "@Entity\npublic class Probe extends BaseEntity {\n    @FieldMetadata(label = LABEL) private String a;\n    @FieldMetadata private String b;\n}\n");
        var ghost = new EntityChange.LabelChanged(UUID.randomUUID(), "ghost", "x", "y");
        assertEquals(SourceRefusal.Code.MODIFICATION_UNSUPPORTED, assertThrows(SourceRefusal.class, () -> subject.apply(ghost)).code());
        var constant = new EntityChange.LabelChanged(UUID.randomUUID(), "a", "x", "y");
        assertEquals(SourceRefusal.Code.MODIFICATION_UNSUPPORTED, assertThrows(SourceRefusal.class, () -> subject.apply(constant)).code());
    }

    @Test void aLabelChangeAndANewFieldTogether(@TempDir Path dir) throws Exception {
        var subject = probe(dir, "", "    @FieldMetadata(label = \"Старая\") private String a;\n    public String getA() { return a; }\n");
        var patch = subject.patch(new EntityChange.LabelChanged(subject.entity().draft().fields().getFirst().id(), "a", "Старая", "Новая"), add(field("b", "STRING")));
        assertOnlyEdits(subject.text(), patch);
        assertTrue(patch.text().contains("label = \"Новая\"") && patch.text().contains("private String b;"));
        assertFalse(patch.text().contains("Старая"));
    }

    // ------------------------------------------------------------------ refusals

    @Test void aChangedFileOrANonRootEntityIsRefused(@TempDir Path dir) throws Exception {
        var subject = probe(dir, "", "    @FieldMetadata private String a;\n");
        var stale = assertThrows(SourceRefusal.class, () -> JavaEntityPatcher.apply(subject.text() + "// edited\n", subject.entity(), List.of(add(field("b", "STRING")))));
        assertEquals(SourceRefusal.Code.STALE_BASE, stale.code());
        var row = consumerFile("OrderLine");
        assertEquals(SourceRefusal.Code.MODIFICATION_UNSUPPORTED, assertThrows(SourceRefusal.class, () -> row.apply(add(field("b", "STRING")))).code());
    }

    @Test void aRequiredNewFieldIsRefused(@TempDir Path dir) throws Exception {
        var required = field("a", "STRING");
        var edited = new FieldDraft(required.id(), "a", "A", "STRING", DraftText.auto(), true, FieldDraft.UiRequired.REQUIRED, required.constraints(), null, required.applications());
        assertEquals(SourceRefusal.Code.MODIFICATION_UNSUPPORTED, assertThrows(SourceRefusal.class, () -> probe(dir, "", "").apply(add(edited))).code());
    }

    @Test void nothingElseChangesInTheFileForAnyType(@TempDir Path dir) throws Exception {
        for (String type : List.of("STRING", "INTEGER", "LONG", "DECIMAL", "BOOLEAN", "DATE", "DATETIME", "REFERENCE")) {
            var subject = probe(dir, "", "    @FieldMetadata(order = 1) private String a;\n    public String getA() { return a; }\n");
            assertOnlyEdits(subject.text(), subject.patch(add(field("extra", type))));
        }
    }
}

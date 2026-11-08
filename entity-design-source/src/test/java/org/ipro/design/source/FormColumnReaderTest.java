package org.ipro.design.source;

import java.nio.file.*;
import java.util.*;
import org.ipro.design.FieldApplicationMapping.CompositionMode;
import org.ipro.design.existing.ExistingEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FormColumnReaderTest {
    private static final String IMPORTS = "package p; import jakarta.persistence.*; import org.ipro.metadata.annotation.*; ";
    @TempDir Path root;
    private void put(String file, String text) throws Exception {
        Path path = root.resolve(file); Files.createDirectories(path.getParent()); Files.writeString(path, text);
    }
    private void item(String metadata, String fields) throws Exception {
        put("p/Item.java", IMPORTS + "@Entity " + metadata + " public class Item extends org.ipro.crud.StandardCatalogEntity { " + fields + " }");
    }
    private void config(String declarations) throws Exception { config("Config", declarations, ""); }
    private void config(String name, String declarations, String body) throws Exception {
        put("p/" + name + ".java", IMPORTS + "@FormConfigurationFor(Item.class) " + declarations + " public class " + name + " {" + body + "}");
    }
    private ExistingEntity read() throws Exception {
        var project = ProjectSources.scan(List.of(root));
        assertTrue(project.complete(), project.unparsable().toString());
        return JavaEntityReader.read("p.Item", Files.readString(root.resolve("p/Item.java")), project);
    }
    private SourceRefusal refuse(String property) throws Exception {
        var refusal = assertThrows(SourceRefusal.class, this::read);
        assertEquals(SourceRefusal.Code.FORM_COLUMNS_UNSUPPORTED, refusal.code());
        assertTrue(refusal.target().contains(property), refusal.getMessage());
        return refusal;
    }
    @Test void literalDeclarationsPreserveOrderNestedPathsIntentAndSourceHash() throws Exception {
        item("@EntityMetadata", "@FieldMetadata private String a; @FieldMetadata @ManyToOne private Other ref;");
        put("p/Other.java", IMPORTS + "@Entity class Other extends org.ipro.crud.StandardCatalogEntity {}");
        config("@ListColumns({\"ref.name\",\"code\",\"a\"}) @SelectionColumns(\"name\")");
        var entity = read();
        assertEquals(List.of("ref.name", "code", "a"), entity.listColumns().resolvedPaths());
        assertEquals(entity.listColumns().declaredPaths(), entity.listColumns().resolvedPaths());
        assertEquals(CompositionMode.EXPLICIT, entity.listColumns().mode());
        assertEquals(List.of("name"), entity.selectionColumns().resolvedPaths());
        assertEquals("p.Config", entity.listColumns().sourceTypeId());
        assertEquals("p/Config.java", entity.listColumns().sourcePath());
        assertEquals(JavaSources.parse("Config.java", Files.readString(root.resolve("p/Config.java"))).sha256(), entity.listColumns().sourceSha256());
        assertTrue(entity.draft().fields().stream().allMatch(f -> f.applications().list() && !f.applications().selection()));
    }
    @Test void explicitEqualToAutoDoesNotBecomeAuto() throws Exception {
        item("@EntityMetadata", ""); config("@ListColumns({\"code\",\"name\"})");
        var entity = read();
        assertEquals(entity.listColumns().resolvedPaths(), entity.selectionColumns().resolvedPaths());
        assertEquals(CompositionMode.EXPLICIT, entity.listColumns().mode());
        assertEquals(CompositionMode.AUTO, entity.selectionColumns().mode());
    }
    @Test void autoUsesGridOrderStableTiesInheritedFieldsAndHiddenGridFlags() throws Exception {
        put("p/Base.java", IMPORTS + "class Base extends org.ipro.crud.StandardCatalogEntity { @FieldMetadata(grid=@GridColumn(order=5)) String base; }");
        put("p/Item.java", IMPORTS + "@Entity @EntityMetadata public class Item extends Base { @FieldMetadata(grid=@GridColumn(order=5)) String first; @FieldMetadata(grid=@GridColumn(visible=false)) String hidden; }");
        config("@ListColumns(properties=@ListColumns.Column(path=\"code\",width=\"100px\")) @SelectionColumns({})");
        var entity = read();
        assertEquals(List.of("first", "base", "code", "name"), entity.listColumns().resolvedPaths());
        assertEquals(entity.listColumns().resolvedPaths(), entity.selectionColumns().resolvedPaths());
        assertEquals(CompositionMode.AUTO, entity.listColumns().mode());
        assertEquals(List.of(), entity.listColumns().declaredPaths());
    }
    @Test void absentSelectionInheritsOnlyLegacyListAndNotNewList() throws Exception {
        item("@EntityMetadata(listColumns=\"name\")", "@FieldMetadata String a;");
        config("@ListColumns(\"a\")");
        var entity = read();
        assertEquals(List.of("a"), entity.listColumns().resolvedPaths());
        assertEquals(List.of("name"), entity.selectionColumns().resolvedPaths());
        assertEquals(CompositionMode.AUTO, entity.selectionColumns().mode());
        assertFalse(entity.draft().fields().getFirst().applications().selection());
    }
    @Test void explicitAutoSelectionUsesGridInsteadOfLegacyOrNewList() throws Exception {
        item("@EntityMetadata(listColumns=\"name\")", "@FieldMetadata String a;");
        config("@ListColumns(\"a\") @SelectionColumns");
        var entity = read();
        assertEquals(List.of("code", "name", "a"), entity.selectionColumns().resolvedPaths());
        assertEquals(CompositionMode.AUTO, entity.selectionColumns().mode());
    }
    @Test void newAutoListOverridesLegacyWhileAbsentSelectionKeepsLegacyFallback() throws Exception {
        item("@EntityMetadata(listColumns=\"name\")", "@FieldMetadata String a;"); config("@ListColumns");
        var entity = read();
        assertEquals(List.of("code", "name", "a"), entity.listColumns().resolvedPaths());
        assertEquals(List.of("name"), entity.selectionColumns().resolvedPaths());
    }
    @Test void repeatableAnnotationsAndContainerVariantsDoNotChangeDefault() throws Exception {
        item("@EntityMetadata", "");
        config("@ListColumns(value=\"name\",variant=\"short\") @ListColumns({\"name\",\"code\"}) @SelectionColumns.Declarations({@SelectionColumns(\"code\"),@SelectionColumns(value=\"name\",variant=\"short\")})");
        var entity = read();
        assertEquals(List.of("name", "code"), entity.listColumns().resolvedPaths());
        assertEquals(List.of("code"), entity.selectionColumns().resolvedPaths());
    }
    @Test void duplicateRoleVariantIsAnAddressedConflictAcrossFiles() throws Exception {
        item("@EntityMetadata", ""); config("@ListColumns(\"code\")"); config("Second", "@ListColumns(\"name\")", "");
        var refusal = assertThrows(SourceRefusal.class, this::read);
        assertEquals(SourceRefusal.Code.FORM_COLUMNS_CONFLICT, refusal.code());
        assertTrue(refusal.getMessage().contains("p.Second")); assertTrue(refusal.getMessage().contains("p.Config"));
    }
    @Test void literalOnlyContractRejectsConstantsConcatenationConditionsAndCalls() throws Exception {
        item("@EntityMetadata", "");
        for (String value : List.of("COLUMNS", "{\"co\" + \"de\"}", "{PATH}", "true ? \"code\" : \"name\"", "columns()")) {
            config("@ListColumns(" + value + ")");
            var refusal = refuse("#value"); assertTrue(refusal.target().matches("p.Config:[0-9]+#value"));
        }
    }
    @Test void constantsInVariantTitleAndPropertiesAreRejected() throws Exception {
        item("@EntityMetadata", "");
        for (var declaration : Map.of("@ListColumns(value=\"code\",variant=VARIANT)", "variant",
            "@SelectionColumns(value=\"code\",title=TITLE)", "title",
            "@ListColumns(properties=@ListColumns.Column(path=\"code\",width=WIDTH))", "width").entrySet()) {
            config(declaration.getKey()); refuse("#" + declaration.getValue());
        }
    }
    @Test void dynamicOrUnresolvedBindingIsRejected() throws Exception {
        item("@EntityMetadata", "");
        for (String value : List.of("TARGET", "target()", "Missing.class")) {
            put("p/Config.java", IMPORTS + "@FormConfigurationFor(" + value + ") @ListColumns(\"code\") class Config {}");
            refuse("#FormConfigurationFor.value");
        }
    }
    @Test void columnsWithoutStaticBindingAreRejected() throws Exception {
        item("@EntityMetadata", ""); put("p/Config.java", IMPORTS + "@ListColumns(\"code\") class Config {}"); refuse("#FormConfigurationFor");
    }
    @Test void inheritedAndConditionalConfigurationsAreRejected() throws Exception {
        item("@EntityMetadata", "");
        put("p/Config.java", IMPORTS + "@FormConfigurationFor(Item.class) @ListColumns(\"code\") class Config extends Helper {} class Helper {}");
        refuse("#extends");
        config("@org.springframework.context.annotation.Profile(\"dev\") @ListColumns(\"code\")"); refuse("#Profile");
    }
    @Test void unknownDuplicateBlankPathsAndPropertiesOutsideCompositionAreRejected() throws Exception {
        item("@EntityMetadata", "");
        config("@ListColumns(\"missing\")"); refuse("#columns.missing");
        config("@ListColumns({\"code\",\"code\"})"); refuse("#value");
        config("@ListColumns(\" \")"); refuse("#value");
        config("@ListColumns(value=\"code\",properties=@ListColumns.Column(path=\"name\",label=\"Name\"))"); refuse("#properties.name");
    }
    @Test void unrelatedLiteralBindingDoesNotBlockReadingAnotherEntity() throws Exception {
        item("@EntityMetadata", ""); put("p/Other.java", IMPORTS + "@Entity class Other extends org.ipro.crud.StandardCatalogEntity {}");
        put("p/Config.java", IMPORTS + "@FormConfigurationFor(Other.class) @ListColumns(COLUMNS) class Config {}");
        assertEquals(List.of("code", "name"), read().listColumns().resolvedPaths());
    }
    @Test void bothNewDeclarationsOverrideOpaqueLegacyArrays() throws Exception {
        item("@EntityMetadata(listColumns=COLUMNS,selectColumns=COLUMNS)", "@FieldMetadata String a;");
        config("@ListColumns(\"a\") @SelectionColumns(\"a\")");
        assertEquals(1, read().draft().fields().size());
    }
    @Test void blankVariantMeansDefaultAndUnicodePathsRemainLiteral() throws Exception {
        item("@EntityMetadata", "@FieldMetadata String название;");
        config("@ListColumns(value=\"название\",variant=\" \")");
        assertEquals(List.of("название"), read().listColumns().resolvedPaths());
    }
    @Test void inheritedReferencePathUsesTheDeclaringTypesImports() throws Exception {
        put("q/Target.java", "package q; class Target extends org.ipro.crud.StandardCatalogEntity {}");
        put("r/Base.java", "package r; import q.Target; class Base extends org.ipro.crud.StandardCatalogEntity { Target target; }");
        put("p/Item.java", IMPORTS + "@Entity @EntityMetadata public class Item extends r.Base {}");
        config("@ListColumns(\"target.name\")");
        assertEquals(List.of("target.name"), read().listColumns().resolvedPaths());
    }
    @Test void readingNeverLoadsOrInstantiatesConfigurationClasses() throws Exception {
        item("@EntityMetadata", "");
        config("Config", "@ListColumns(\"code\")", "static { if (true) throw new AssertionError(\"must not run\"); } Config() { throw new AssertionError(); }");
        assertEquals(List.of("code"), read().listColumns().resolvedPaths());
    }
    @Test void unsupportedNewAttributesAndMalformedPropertiesAreAddressed() throws Exception {
        item("@EntityMetadata", "");
        config("@ListColumns(value=\"code\",future=\"value\")"); refuse("#future");
        config("@ListColumns(properties=@Other(path=\"code\"))"); refuse("#properties");
    }
    @Test void fieldAdditionsWithNewDeclarationsLeaveExternalCompositionUnchanged() throws Exception {
        item("@EntityMetadata", "@FieldMetadata String a;"); config("@ListColumns(\"code\")");
        var entity = read();
        var added = new org.ipro.design.FieldDraft(UUID.randomUUID(), "added", "Added", "STRING", org.ipro.design.DraftText.auto(), false,
            org.ipro.design.FieldDraft.UiRequired.AUTO, new org.ipro.design.ConstraintDraft("40", "", ""), null, org.ipro.design.FieldApplications.root());
        String patched = JavaEntityPatcher.apply(Files.readString(root.resolve("p/Item.java")), entity,
            List.of(new org.ipro.design.existing.EntityChange.FieldAdded(added)));
        assertTrue(patched.contains("private String added;"));
        assertFalse(patched.contains("listColumns"));
        String changed = JavaEntityPatcher.apply(Files.readString(root.resolve("p/Item.java")), entity,
            List.of(new org.ipro.design.existing.EntityChange.LabelChanged(entity.draft().fields().getFirst().id(), "a", "", "A")));
        assertTrue(changed.contains("label = \"A\""));
        assertFalse(changed.contains("listColumns"));
    }
    @Test void unresolvedLegacyGridOrderIsMarkedIncompleteWithoutGuessingOrLockingOtherFields() throws Exception {
        item("@EntityMetadata", "@FieldMetadata(grid=@GridColumn(order=ORDER)) String a; @FieldMetadata String b;");
        var entity = read();
        assertEquals(CompositionMode.AUTO, entity.listColumns().mode());
        assertFalse(entity.listColumns().resolutionComplete()); assertTrue(entity.listColumns().resolvedPaths().isEmpty());
        assertEquals(2, entity.draft().fields().size());
        config("@ListColumns(\"code\") @SelectionColumns(\"name\")");
        assertTrue(read().listColumns().resolutionComplete());
        config("@ListColumns"); refuse("#grid");
    }
    @Test void conditionalMetaAnnotationsAreNotTreatedAsUnconditionalRegistration() throws Exception {
        item("@EntityMetadata", "");
        put("p/Development.java", "package p; @org.springframework.context.annotation.Profile(\"dev\") @interface Development {}");
        config("@Development @ListColumns(\"code\")"); refuse("#Development");
    }
    @Test void nestedConfigurationIsAddressedInsteadOfSilentlyBecomingAutomatic() throws Exception {
        item("@EntityMetadata", "");
        put("p/Outer.java", IMPORTS + "public class Outer { @FormConfigurationFor(Item.class) @ListColumns(\"name\") static class Config {} }");
        refuse("#nested");
    }
    @Test void interfaceAndAbstractDeclarationsAreNotConcreteConfigurationClasses() throws Exception {
        item("@EntityMetadata", "");
        for (String type : List.of("interface", "abstract class")) {
            put("p/Config.java", IMPORTS + "@FormConfigurationFor(Item.class) @ListColumns(\"code\") public " + type + " Config {}");
            refuse("#type");
        }
    }
    @Test void compatibilityConstructorsKeepUnknownIntent() throws Exception {
        item("@EntityMetadata", ""); var e = read();
        var old = new ExistingEntity(e.typeId(), e.relativePath(), e.sourceSha256(), e.tableName(), e.shape(), e.draft(), e.lockedFields(), e.inheritedNames(), e.occupiedMethodNames());
        assertEquals(CompositionMode.UNKNOWN, old.listColumns().mode());
    }
}

package org.ipro.design.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftText;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.existing.EntityChange;
import org.ipro.design.existing.ExistingEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SchemaProposalTest {
    private static final String HEAD = "package t;\n\nimport jakarta.persistence.*;\nimport org.ipro.crud.StandardCatalogEntity;\nimport org.ipro.metadata.annotation.*;\n\n";

    private static void write(Path root, String name, String text) throws IOException {
        Files.createDirectories(root.resolve("t"));
        Files.writeString(root.resolve("t").resolve(name + ".java"), text, StandardCharsets.UTF_8);
    }

    private static String entity(String annotations, String name) {
        return HEAD + "@Entity " + annotations + "\npublic class " + name + " extends StandardCatalogEntity {\n}\n";
    }

    private record Setup(ProjectSources project, ExistingEntity entity) {}

    private static Setup setup(Path root, String name, String text) throws IOException {
        write(root, name, text);
        var project = ProjectSources.scan(List.of(root));
        return new Setup(project, JavaEntityReader.read("t." + name, text, project));
    }

    private static FieldDraft field(String name, String type, ConstraintDraft constraints, ReferenceDraft reference) {
        return new FieldDraft(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name, "Реквизит", type, DraftText.auto(), false,
            FieldDraft.UiRequired.AUTO, constraints, reference, FieldApplications.root());
    }

    private static List<String> sql(Setup setup, FieldDraft... fields) {
        return SchemaProposal.statements(setup.entity(), java.util.Arrays.stream(fields).<EntityChange>map(EntityChange.FieldAdded::new).toList(), setup.project());
    }

    private static SourceRefusal refusal(Setup setup, FieldDraft field) {
        var refused = assertThrows(SourceRefusal.class, () -> sql(setup, field));
        assertEquals(SourceRefusal.Code.SCHEMA_UNKNOWN, refused.code());
        assertEquals(setup.entity().typeId(), refused.target());
        return refused;
    }

    @Test void everyTypeHasItsColumnType(@TempDir Path root) throws IOException {
        var setup = setup(root, "Workshop", entity("@Table(name = \"workshops\")", "Workshop"));
        write(root, "Unit", entity("", "Unit"));
        setup = new Setup(ProjectSources.scan(List.of(root)), setup.entity());
        var empty = ConstraintDraft.empty();
        assertEquals(List.of("ALTER TABLE workshops ADD COLUMN title VARCHAR(120);"), sql(setup, field("title", "STRING", new ConstraintDraft("120", "", ""), null)));
        assertEquals(List.of("ALTER TABLE workshops ADD COLUMN title VARCHAR(255);"), sql(setup, field("title", "STRING", empty, null)));
        assertEquals(List.of("ALTER TABLE workshops ADD COLUMN amount INTEGER;"), sql(setup, field("amount", "INTEGER", empty, null)));
        assertEquals(List.of("ALTER TABLE workshops ADD COLUMN amount BIGINT;"), sql(setup, field("amount", "LONG", empty, null)));
        assertEquals(List.of("ALTER TABLE workshops ADD COLUMN price NUMERIC(15,4);"), sql(setup, field("price", "DECIMAL", new ConstraintDraft("", "15", "4"), null)));
        assertEquals(List.of("ALTER TABLE workshops ADD COLUMN active BOOLEAN;"), sql(setup, field("active", "BOOLEAN", empty, null)));
        assertEquals(List.of("ALTER TABLE workshops ADD COLUMN opened DATE;"), sql(setup, field("opened", "DATE", empty, null)));
        assertEquals(List.of("ALTER TABLE workshops ADD COLUMN stamp TIMESTAMP(6);"), sql(setup, field("stamp", "DATETIME", empty, null)));
        assertEquals(List.of("ALTER TABLE workshops ADD COLUMN main_unit_id BIGINT;",
            "ALTER TABLE workshops ADD CONSTRAINT fk_workshops_main_unit_id FOREIGN KEY (main_unit_id) REFERENCES unit (id);"),
            sql(setup, field("mainUnit", "REFERENCE", empty, new ReferenceDraft("t.Unit"))));
    }

    @Test void theTableNameIsExplicitOrDerivedLikeHibernateDoes(@TempDir Path root) throws IOException {
        var implicit = setup(root, "WorkshopUnit", entity("", "WorkshopUnit"));
        assertEquals(List.of("ALTER TABLE workshop_unit ADD COLUMN amount INTEGER;"), sql(implicit, field("amount", "INTEGER", ConstraintDraft.empty(), null)));
        var named = setup(root, "Named", entity("@Table(name = \"special\")", "Named"));
        assertEquals(List.of("ALTER TABLE special ADD COLUMN amount INTEGER;"), sql(named, field("amount", "INTEGER", ConstraintDraft.empty(), null)));
    }

    @Test void anExplicitColumnNameIsUsed(@TempDir Path root) throws IOException {
        var setup = setup(root, "Named", entity("@Table(name = \"special\")", "Named"));
        var base = field("amount", "INTEGER", ConstraintDraft.empty(), null);
        var explicit = new FieldDraft(base.id(), base.name(), base.label(), base.type(), DraftText.explicit("qty"), false, base.uiRequired(),
            base.constraints(), null, base.applications());
        assertEquals(List.of("ALTER TABLE special ADD COLUMN qty INTEGER;"), sql(setup, explicit));
    }

    @Test void aLongConstraintNameFitsTheLimitAndStaysDistinct(@TempDir Path root) throws IOException {
        String table = "a_table_with_quite_a_long_name_for_the_entity", targetTable = "unit";
        var setup = setup(root, "Long", entity("@Table(name = \"" + table + "\")", "Long"));
        write(root, "Unit", entity("@Table(name = \"" + targetTable + "\")", "Unit"));
        setup = new Setup(ProjectSources.scan(List.of(root)), setup.entity());
        var first = sql(setup, field("aReferenceWithAVeryLongFieldNameIndeed", "REFERENCE", ConstraintDraft.empty(), new ReferenceDraft("t.Unit")));
        var second = sql(setup, field("aReferenceWithAVeryLongFieldNameIndeeX", "REFERENCE", ConstraintDraft.empty(), new ReferenceDraft("t.Unit")));
        String firstName = first.get(1).split(" ")[5], secondName = second.get(1).split(" ")[5];
        assertTrue(firstName.length() <= 63, firstName);
        assertNotEquals(firstName, secondName);
        assertEquals(firstName, sql(setup, field("aReferenceWithAVeryLongFieldNameIndeed", "REFERENCE", ConstraintDraft.empty(), new ReferenceDraft("t.Unit"))).get(1).split(" ")[5]);
    }

    @Test void eachStatementEndsWithASemicolonAndNothingIsConditional(@TempDir Path root) throws IOException {
        var setup = setup(root, "Workshop", entity("", "Workshop"));
        write(root, "Unit", entity("", "Unit"));
        setup = new Setup(ProjectSources.scan(List.of(root)), setup.entity());
        var statements = sql(setup, field("a", "STRING", new ConstraintDraft("10", "", ""), null), field("b", "REFERENCE", ConstraintDraft.empty(), new ReferenceDraft("t.Unit")));
        assertEquals(3, statements.size());
        for (String statement : statements) {
            assertTrue(statement.endsWith(";"), statement);
            assertFalse(statement.toUpperCase().contains("IF NOT EXISTS"), statement);
            assertFalse(statement.contains("\n"), statement);
        }
    }

    @Test void aChangeOfALabelNeedsNoSql(@TempDir Path root) throws IOException {
        var setup = setup(root, "Workshop", entity("@Table(schema = \"s\")", "Workshop"));
        var statements = SchemaProposal.statements(setup.entity(), List.of(new EntityChange.LabelChanged(new UUID(1, 1), "code", "Код", "Номер")), setup.project());
        assertEquals(List.of(), statements);
    }

    @Test void aSchemaOrAnotherTableOrInheritanceIsRefused(@TempDir Path root) throws IOException {
        var plain = field("amount", "INTEGER", ConstraintDraft.empty(), null);
        assertTrue(refusal(setup(root, "WithSchema", entity("@Table(name = \"x\", schema = \"s\")", "WithSchema")), plain).reason().contains("schema"));
        assertTrue(refusal(setup(root, "WithCatalog", entity("@Table(name = \"x\", catalog = \"c\")", "WithCatalog")), plain).reason().contains("schema"));
        assertTrue(refusal(setup(root, "Second", entity("@SecondaryTable(name = \"y\")", "Second")), plain).reason().contains("SecondaryTable"));
        assertTrue(refusal(setup(root, "Inherits", entity("@Inheritance(strategy = InheritanceType.JOINED)", "Inherits")), plain).reason().contains("Inheritance"));
        write(root, "Parent", HEAD + "@Entity\npublic class Parent extends StandardCatalogEntity {\n}\n");
        var child = HEAD + "@Entity\npublic class Child extends Parent {\n}\n";
        write(root, "Child", child);
        var project = ProjectSources.scan(List.of(root));
        var read = new Setup(project, JavaEntityReader.read("t.Child", child, project));
        assertTrue(refusal(read, plain).reason().contains("parent"));
        var dynamic = setup(root, "Dynamic", HEAD + "@Entity @Table(name = Names.TABLE)\npublic class Dynamic extends StandardCatalogEntity {\n}\n");
        assertTrue(refusal(dynamic, plain).reason().contains("table name"));
    }

    @Test void aTargetThatCannotBeResolvedIsRefused(@TempDir Path root) throws IOException {
        var setup = setup(root, "Workshop", entity("", "Workshop"));
        var reference = field("unit", "REFERENCE", ConstraintDraft.empty(), new ReferenceDraft("t.Missing"));
        assertTrue(refusal(setup, reference).reason().contains("not in the indexed sources"));

        write(root, "Plain", HEAD + "public class Plain {\n}\n");
        setup = new Setup(ProjectSources.scan(List.of(root)), setup.entity());
        assertTrue(refusal(setup, field("unit", "REFERENCE", ConstraintDraft.empty(), new ReferenceDraft("t.Plain"))).reason().contains("not an @Entity"));

        write(root, "Orphan", HEAD + "@Entity\npublic class Orphan extends Unknown {\n}\n");
        setup = new Setup(ProjectSources.scan(List.of(root)), setup.entity());
        assertTrue(refusal(setup, field("unit", "REFERENCE", ConstraintDraft.empty(), new ReferenceDraft("t.Orphan"))).reason().contains("BaseEntity"));

        write(root, "OwnId", HEAD + "@Entity\npublic class OwnId extends StandardCatalogEntity {\n    private String id;\n}\n");
        setup = new Setup(ProjectSources.scan(List.of(root)), setup.entity());
        assertTrue(refusal(setup, field("unit", "REFERENCE", ConstraintDraft.empty(), new ReferenceDraft("t.OwnId"))).reason().contains("own id"));

        write(root, "Schema", entity("@Table(name = \"s\", schema = \"x\")", "Schema"));
        setup = new Setup(ProjectSources.scan(List.of(root)), setup.entity());
        assertTrue(refusal(setup, field("unit", "REFERENCE", ConstraintDraft.empty(), new ReferenceDraft("t.Schema"))).reason().contains("schema"));
    }

    @Test void aColumnOrTypeThatIsNotPlainIsRefused(@TempDir Path root) throws IOException {
        var setup = setup(root, "Workshop", entity("", "Workshop"));
        var base = field("amount", "INTEGER", ConstraintDraft.empty(), null);
        var quoted = new FieldDraft(base.id(), base.name(), base.label(), base.type(), DraftText.explicit("Amount; DROP TABLE x"), false, base.uiRequired(),
            base.constraints(), null, base.applications());
        assertTrue(refusal(setup, quoted).reason().contains("not a plain lowercase identifier"));
        assertTrue(refusal(setup, field("amount", "FLOAT", ConstraintDraft.empty(), null)).reason().contains("not known"));
        assertTrue(refusal(setup, field("price", "DECIMAL", ConstraintDraft.empty(), null)).reason().contains("not a number"));
    }

    @Test void theResultIsDeterministic(@TempDir Path root) throws IOException {
        var setup = setup(root, "Workshop", entity("", "Workshop"));
        var field = field("title", "STRING", new ConstraintDraft("20", "", ""), null);
        assertEquals(sql(setup, field), sql(setup, field));
    }
}

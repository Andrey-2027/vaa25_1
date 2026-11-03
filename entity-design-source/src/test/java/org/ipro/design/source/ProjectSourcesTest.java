package org.ipro.design.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.ipro.design.ReferenceTargetDescriptor.Eligibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProjectSourcesTest {
    @Test void consumerTablesAreExplicitAndImplicit() {
        var project = ProjectSources.scan(List.of(TestSources.existing(TestSources.CONSUMER)));
        assertTrue(project.complete(), project.unparsable().toString());
        assertEquals("et5_order", project.type("com.example.entitytypes.model.Order").orElseThrow().tableName());
        assertEquals("custom_catalog", project.type("com.example.entitytypes.model.CustomCatalog").orElseThrow().tableName());
        assertEquals("extended_product", project.type("com.example.entitytypes.model.ExtendedProduct").orElseThrow().tableName());
        assertEquals("com/example/entitytypes/model/Order.java", project.type("com.example.entitytypes.model.Order").orElseThrow().relativePath());
    }

    @Test void orderChainEndsAtAPlatformBase() {
        var project = ProjectSources.scan(List.of(TestSources.existing(TestSources.CONSUMER)));
        var order = project.type("com.example.entitytypes.model.Order").orElseThrow();
        assertEquals("StandardDocumentEntity", order.superName());
        assertEquals("org.ipro.crud.StandardDocumentEntity", project.superTypeOf(order.typeId()).orElseThrow());
        var chain = project.chain(order.typeId());
        assertTrue(chain.reachesBaseEntity());
        assertEquals(List.of("org.ipro.crud.StandardDocumentEntity", "org.ipro.crud.BaseEntity"),
            chain.platformBases().stream().map(base -> base.typeId()).toList());
        assertEquals(ProjectSources.Kind.ROOT, ProjectSources.classify(order.annotations(), chain));
    }

    @Test void aSectionRowIsNotAReferenceTarget() {
        var project = ProjectSources.scan(List.of(TestSources.existing(TestSources.CONSUMER)));
        var line = project.type("com.example.entitytypes.model.OrderLine").orElseThrow();
        assertEquals(ProjectSources.Kind.SECTION_ROW, ProjectSources.classify(line.annotations(), project.chain(line.typeId())));
        var targets = project.designContext("consumer", "v1", null).referenceTargets();
        assertEquals(Eligibility.UNSUPPORTED, targets.get(line.typeId()).eligibility());
        assertFalse(targets.get(line.typeId()).supported());
        assertTrue(targets.get("com.example.entitytypes.model.Product").supported());
        assertTrue(targets.get("com.example.entitytypes.model.Order").supported());
        assertTrue(targets.get("com.example.entitytypes.model.CustomCatalog").supported(), "declared by @EntityMetadata");
    }

    @Test void applicationModelChainsThroughAMappedSuperclass() {
        var project = ProjectSources.scan(List.of(TestSources.existing(TestSources.APPLICATION_MODEL)));
        var correction = project.type("org.ip.model.AccountingObjectCorrection").orElseThrow();
        var chain = project.chain(correction.typeId());
        assertEquals(List.of("org.ip.model.AccountingObjectCorrection", "org.ip.model.AccountingObjectDocument"),
            chain.projectTypes().stream().map(SourceType::typeId).toList());
        assertTrue(chain.reachesBaseEntity());
        assertEquals(List.of("org.ip.model.AccountingObjectCorrection"),
            project.descendants("org.ip.model.AccountingObjectDocument").stream().map(SourceType::typeId)
                .filter(id -> id.equals("org.ip.model.AccountingObjectCorrection")).toList());
        var targets = project.designContext("app", "v1", "org.ip.model").referenceTargets();
        assertTrue(targets.get(correction.typeId()).supported());
        assertFalse(targets.containsKey("org.ip.model.AccountingObjectDocument"), "a mapped superclass is not an entity");
    }

    @Test void aRegisterIsNotAReferenceTarget() {
        var project = ProjectSources.scan(List.of(TestSources.existing(TestSources.APPLICATION_MODEL)));
        var price = project.type("org.ip.model.NomenclaturePrice").orElseThrow();
        assertEquals(ProjectSources.Kind.REGISTER, ProjectSources.classify(price.annotations(), project.chain(price.typeId())));
        var targets = project.designContext("app", "v1", "org.ip.model").referenceTargets();
        assertFalse(targets.get(price.typeId()).supported());
        assertEquals(Eligibility.UNSUPPORTED, targets.get(price.typeId()).eligibility());
    }

    @Test void scanIsDeterministicAndNeedsNoClasspath() {
        var first = ProjectSources.scan(List.of(TestSources.existing(TestSources.APPLICATION_MODEL)));
        var second = ProjectSources.scan(List.of(TestSources.APPLICATION_MODEL));
        assertEquals(first.types().stream().map(SourceType::typeId).toList(), second.types().stream().map(SourceType::typeId).toList());
        assertEquals(first.types().stream().map(SourceType::sha256).toList(), second.types().stream().map(SourceType::sha256).toList());
        assertEquals(first.designContext("p", "v", null), second.designContext("p", "v", null));
    }

    @Test void wildcardImportsResolveOnlyAgainstTheIndex(@TempDir Path dir) throws Exception {
        write(dir, "a/Base.java", "package a;\n@jakarta.persistence.MappedSuperclass\npublic abstract class Base extends org.ipro.crud.BaseEntity {}\n");
        write(dir, "b/Child.java", "package b;\nimport a.*;\nimport jakarta.persistence.*;\n@Entity\npublic class Child extends Base {}\n");
        write(dir, "b/Lost.java", "package b;\nimport unknown.*;\n@jakarta.persistence.Entity\npublic class Lost extends Missing {}\n");
        var project = ProjectSources.scan(List.of(dir));
        assertEquals("a.Base", project.superTypeOf("b.Child").orElseThrow());
        assertTrue(project.chain("b.Child").reachesBaseEntity());
        assertTrue(project.superTypeOf("b.Lost").isEmpty());
        assertFalse(project.chain("b.Lost").reachesBaseEntity());
        assertEquals(ProjectSources.Kind.UNRESOLVED_BASE, ProjectSources.classify(project.type("b.Lost").orElseThrow().annotations(), project.chain("b.Lost")));
        assertEquals(List.of("b.Child"), project.descendants("a.Base").stream().map(SourceType::typeId).toList());
        var targets = project.designContext("p", "v", null).referenceTargets();
        assertEquals(Eligibility.UNKNOWN, targets.get("b.Child").eligibility(), "no Standard base and no @EntityMetadata");
        assertEquals(Eligibility.UNKNOWN, targets.get("b.Lost").eligibility());
    }

    @Test void anUnparsableFileMakesTheContextIncomplete(@TempDir Path dir) throws Exception {
        write(dir, "a/Ok.java", "package a;\n@jakarta.persistence.Entity\npublic class Ok extends org.ipro.crud.BaseEntity {}\n");
        write(dir, "a/Broken.java", "package a;\npublic class Broken {\n");
        var project = ProjectSources.scan(List.of(dir));
        assertFalse(project.complete());
        assertEquals("a/Broken.java", project.unparsable().getFirst().relativePath());
        var context = project.designContext("p", "v", null);
        assertFalse(context.existingTypes().complete());
        assertFalse(context.referencesComplete());
        assertFalse(context.existingTables().complete());
    }

    @Test void explicitEntityNameAndNonLiteralTableAreHandled(@TempDir Path dir) throws Exception {
        write(dir, "a/Named.java", "package a;\n@jakarta.persistence.Entity(name = \"XMLItem\")\npublic class Named extends org.ipro.crud.BaseEntity {}\n");
        write(dir, "a/Const.java", "package a;\n@jakarta.persistence.Entity @jakarta.persistence.Table(name = Names.T)\npublic class Const extends org.ipro.crud.BaseEntity {}\n");
        write(dir, "a/Schema.java", "package a;\n@jakarta.persistence.Entity @jakarta.persistence.Table(name = \"s\", schema = \"x\")\npublic class Schema extends org.ipro.crud.BaseEntity {}\n");
        var project = ProjectSources.scan(List.of(dir));
        assertEquals("xmlitem", project.type("a.Named").orElseThrow().tableName());
        assertEquals("", project.type("a.Const").orElseThrow().tableName());
        assertTrue(project.type("a.Schema").orElseThrow().schemaDeclared());
        assertFalse(project.designContext("p", "v", null).existingTables().complete());
    }

    private static void write(Path root, String relative, String text) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}

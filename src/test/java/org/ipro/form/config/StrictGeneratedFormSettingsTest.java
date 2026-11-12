package org.ipro.form.config;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.ipro.design.*;
import org.ipro.design.generation.*;
import org.ipro.form.TableSectionCustomization;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.MetadataConsistencyValidator;
import org.ipro.metadata.MetadataDiagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** Generate, compile and load the new recipes using the actual platform; no database or mocks. */
class StrictGeneratedFormSettingsTest {
    private FieldDraft field(String name, FieldApplications applications) {
        return new FieldDraft(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name, name, "STRING",
            DraftText.auto(), false, FieldDraft.UiRequired.AUTO, new ConstraintDraft("100", "", ""), null, applications);
    }
    @Test void strictRecipesCompileAndKeepRootAndRowComposition(@TempDir Path work) throws Exception {
        for (var recipe : List.of(RecipeCatalog.STANDARD_CATALOG_V4, RecipeCatalog.STANDARD_DOCUMENT_V3)) {
            var names = ProjectDesignContext.Names.known(Set.of());
            var prerequisite = new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "fixture");
            var context = new ProjectDesignContext("af72", "v1", "af72.generated", names, names, names,
                ProjectDesignContext.Names.known(Set.of("FILE")), Map.of(), true,
                new RuntimeDesignSnapshot("af72", true, Map.of(), Map.of("STANDARD_CATALOG_APP_API", prerequisite,
                    "STANDARD_DOCUMENT_APP_API", prerequisite, "ET0_ET7_RUNTIME", prerequisite)));
            var properties = new EntityProperties("StrictRoot", DraftText.explicit("af72.generated"), "Strict root",
                EntityProperties.Titles.auto(), DraftText.auto(), null, "FILE");
            var formOnly = field("formOnly", new FieldApplications(true,false,false,false));
            var tableOnly = field("tableOnly", new FieldApplications(false,false,false,true));
            var both = field("sharedValue", FieldApplications.row());
            var section = new SectionDraft(new UUID(1,1), "Rows", "StrictRow", DraftText.auto(), DraftText.auto(), DraftText.auto(), DraftText.auto(), List.of(formOnly, tableOnly, both));
            var empty = new SectionDraft(new UUID(1,2), "No columns", "EmptyRow", DraftText.auto(), DraftText.auto(), DraftText.auto(), DraftText.auto(), List.of(field("emptyForm", new FieldApplications(true,false,false,false))));
            var draft = new EntityDraft(1, recipe, new UUID(2,2), properties,
                List.of(field("extra", FieldApplications.root())), List.of(section, empty));
            var validation = new DraftValidator().validate(draft, context);
            assertThat(validation.hasErrors()).as(validation.problems().toString()).isFalse();
            var generated = new EntityJavaGenerator().generate(validation.checkedStructure().orElseThrow(), draft, context);
            assertThat(generated.succeeded()).as(generated.diagnostics().toString()).isTrue();
            var plan = generated.plan().orElseThrow();
            var source = Files.createDirectories(work.resolve(recipe.id()).resolve("src"));
            var output = Files.createDirectories(work.resolve(recipe.id()).resolve("classes"));
            var args = new ArrayList<>(List.of("-proc:none", "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()));
            for (var file : plan.files()) {
                Path path = source.resolve(file.relativePath()); Files.createDirectories(path.getParent()); Files.writeString(path, file.content()); args.add(path.toString());
            }
            assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new))).isZero();
            try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader());
                 var spring = new AnnotationConfigApplicationContext()) {
                spring.setClassLoader(loader);
                spring.register(SourceFormColumnsPlatformParityTest.Infrastructure.class, loader.loadClass("af72.generated.StrictRootFormConfiguration"));
                spring.refresh();
                Class<?> root = loader.loadClass("af72.generated.StrictRoot");
                var resolver = spring.getBean(MetadataResolver.class);
                var inherited = recipe.equals(RecipeCatalog.STANDARD_CATALOG_V4) ? List.of("code", "name", "extra") : List.of("number", "date", "extra");
                assertThat(resolver.resolve(root).getListColumnPaths()).extracting(ColumnPath::getKey).containsExactlyElementsOf(inherited);
                assertThat(resolver.resolve(root).getSelectColumnPaths()).extracting(ColumnPath::getKey).containsExactlyElementsOf(inherited);
                var managed = new ArrayList<Class<?>>(); managed.add(root);
                var customizations = spring.getBeansOfType(TableSectionCustomization.class).values();
                assertThat(customizations).hasSize(2);
                for (var table : customizations) {
                    Class<?> row = table.rowClass(); managed.add(row);
                    String formField = row.getSimpleName().equals("StrictRow") ? "formOnly" : "emptyForm";
                    assertThat(resolver.resolveRowMetadata(row).getFormFields()).extracting(FieldMetadataInfo::getName)
                        .contains(formField).doesNotContain("tableOnly");
                    var paths = table.columns().resolve(row, List.of(ColumnPath.resolve(row, formField)), "generated");
                    if (row.getSimpleName().equals("StrictRow")) assertThat(paths).extracting(ColumnPath::getKey).containsExactly("tableOnly", "sharedValue");
                    else assertThat(paths).isEmpty();
                }
                assertThat(MetadataConsistencyValidator.validate(resolver, managed, List.of()))
                    .noneMatch(d -> d.severity() == MetadataDiagnostic.Severity.ERROR);
            }
        }
    }
}

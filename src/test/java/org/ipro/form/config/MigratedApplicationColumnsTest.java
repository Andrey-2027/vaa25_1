package org.ipro.form.config;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.tools.ToolProvider;
import org.ipro.design.*;
import org.ipro.design.existing.EntityChange;
import org.ipro.design.source.*;
import org.ipro.form.FieldFactory;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.layout.ItemFormLayout;
import org.ipro.form.builder.layout.RemainingFieldsNode;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises migrated application sources, rather than a parallel hand-written metadata fixture. */
class MigratedApplicationColumnsTest {
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class Infrastructure {
        @org.springframework.context.annotation.Bean
        static FormColumnDeclarationsCollector columns() { return new FormColumnDeclarationsCollector(); }
        @org.springframework.context.annotation.Bean
        MetadataResolver metadata() { return new MetadataResolver(); }
        @org.springframework.context.annotation.Bean
        org.ipro.form.registry.FormRegistry registry() { return new org.ipro.form.registry.FormRegistry(); }
    }

    @Test
    void addedPropertyReachesAutoAndRemainingFieldsButDoesNotExtendExplicitColumns(@TempDir Path root)
            throws Exception {
        Path sources = Files.createDirectories(root.resolve("src/af45/copy"));
        Path output = Files.createDirectories(root.resolve("classes"));
        var files = new ArrayList<String>();
        var configurations = new java.util.LinkedHashMap<Path, String>();
        for (String name : List.of("Branch", "AccountingType")) {
            String entity = Files.readString(Path.of("src/main/java/org/ip/model", name + ".java"))
                .replace("package org.ip.model;", "package af45.copy;");
            Path path = sources.resolve(name + ".java");
            Files.writeString(path, entity);
            files.add(path.toString());
            String configName = name + "ColumnsConfiguration";
            String config = Files.readString(Path.of("src/main/java/org/ip/views/forms", configName + ".java"))
                .replace("package org.ip.views.forms;", "package af45.copy;")
                .replace("import org.ip.model." + name + ";", "");
            Path configPath = sources.resolve(configName + ".java");
            Files.writeString(configPath, config);
            files.add(configPath.toString());
            configurations.put(configPath, config);
        }
        var project = ProjectSources.scan(List.of(root.resolve("src")));
        var added = new FieldDraft(UUID.randomUUID(), "address", "Адрес", "STRING", DraftText.auto(), false,
            FieldDraft.UiRequired.AUTO, new ConstraintDraft("80", "", ""), null,
            new FieldApplications(true, true, false, false));
        for (String name : List.of("Branch", "AccountingType")) {
            Path path = sources.resolve(name + ".java");
            String original = Files.readString(path);
            var existing = JavaEntityReader.read("af45.copy." + name, original, project);
            Files.writeString(path, JavaEntityPatcher.patch(original, existing,
                List.of(new EntityChange.FieldAdded(added)), project).text());
        }
        // Configurations stay byte-for-byte unchanged while the entity gains the property.
        for (var configuration : configurations.entrySet()) {
            assertThat(Files.readString(configuration.getKey())).isEqualTo(configuration.getValue());
        }
        var args = new ArrayList<>(List.of("-proc:none", "-encoding", "UTF-8", "-classpath",
            System.getProperty("java.class.path"), "-d", output.toString()));
        args.addAll(files);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)))
            .isZero();
        try (var loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
             var context = new AnnotationConfigApplicationContext()) {
            context.register(Infrastructure.class);
            for (String name : List.of("Branch", "AccountingType")) {
                context.register(loader.loadClass("af45.copy." + name + "ColumnsConfiguration"));
            }
            context.refresh();
            var resolver = context.getBean(MetadataResolver.class);
            for (String name : List.of("Branch", "AccountingType")) {
                var metadata = resolver.resolve(loader.loadClass("af45.copy." + name));
                assertThat(metadata.getSelectColumnPaths()).extracting(ColumnPath::getKey)
                    .containsExactly("code", "name");
                assertThat(metadata.getListColumnPaths()).extracting(ColumnPath::getKey)
                    .containsExactlyElementsOf(name.equals("Branch")
                        ? List.of("code", "name", "address") : List.of("code", "name"));
                var fields = new FieldFactory(null, null);
                ItemForm<?> automatic = new ItemForm<>(metadata, fields);
                ItemForm<?> remaining = new ItemForm<>(metadata, fields,
                    new ItemFormLayout(List.of(new RemainingFieldsNode())));
                assertThat(automatic.getField("address")).isNotNull();
                assertThat(remaining.getField("address")).isNotNull();
                assertThat(remaining.getBindingRegistry().getAll()).extracting(binding -> binding.getFieldName())
                    .containsExactly("code", "name", "address");
            }
        }
    }
}

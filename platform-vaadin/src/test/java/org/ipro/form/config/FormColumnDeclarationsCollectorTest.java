package org.ipro.form.config;

import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.builder.SelectionColumnResolver;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.*;

class FormColumnDeclarationsCollectorTest {
    @EntityMetadata
    static class Workshop {
        @FieldMetadata String code;
        @FieldMetadata String name;
        @FieldMetadata String branch;
    }

    @FormConfigurationFor(Workshop.class)
    @ListColumns({"branch", "name"})
    @ListColumns(value = "code", variant = "compact")
    @SelectionColumns({"name", "code"})
    @SelectionColumns(value = "name", variant = "short", title = "Short")
    @Configuration
    static class WorkshopFormConfig {
        WorkshopFormConfig(MetadataResolver resolver) {
            // A real dependency on metadata is safe: declarations did not instantiate this bean.
            assertThat(resolver.resolve(Workshop.class).getListColumnPaths())
                .extracting(ColumnPath::getKey).containsExactly("branch", "name");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean static FormColumnDeclarationsCollector columns() { return new FormColumnDeclarationsCollector(); }
        @Bean MetadataResolver metadataResolver() { return new MetadataResolver(); }
        @Bean FormRegistry formRegistry() { return new FormRegistry(); }
    }

    @Test
    void declarationsAreReadyBeforeAConfigurationDependingOnMetadataIsConstructed() {
        try (var context = new AnnotationConfigApplicationContext(Infrastructure.class, WorkshopFormConfig.class)) {
            var resolver = context.getBean(MetadataResolver.class);
            var registry = context.getBean(FormRegistry.class);
            var metadata = resolver.resolve(Workshop.class);
            assertThat(metadata.getSelectColumnPaths()).extracting(ColumnPath::getKey).containsExactly("name", "code");
            assertThat(registry.getListColumns(Workshop.class, "compact")).containsExactly("code");
            var variant = SelectionColumnResolver.resolve(resolver, registry, Workshop.class, "short");
            assertThat(variant.columns()).extracting(ColumnPath::getKey).containsExactly("name");
            assertThat(variant.title()).isEqualTo("Short");
            assertThat(resolver.resolve(Workshop.class)).isSameAs(metadata);
            assertThat(metadata.getSelectColumnPaths()).extracting(ColumnPath::getKey).containsExactly("name", "code");
            // Both default opening paths read the same early metadata.
            assertThat(new SelectionFormAssembler(resolver, null).resolveColumns(Workshop.class).columns())
                .containsExactlyElementsOf(metadata.getSelectColumnPaths());
            assertThat(SelectionColumnResolver.resolve(resolver, registry, Workshop.class, null).columns())
                .containsExactlyElementsOf(metadata.getSelectColumnPaths());
        }
    }

    @FormConfigurationFor(Workshop.class)
    @ListColumns("name")
    static class DuplicateConfig { }

    @Test
    void duplicateDeclarationsFailWithBothSourcesBeforeConstructingBeans() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Infrastructure.class, WorkshopFormConfig.class, DuplicateConfig.class);
            assertThatThrownBy(context::refresh).hasStackTraceContaining(WorkshopFormConfig.class.getName())
                .hasStackTraceContaining(DuplicateConfig.class.getName()).hasStackTraceContaining("Duplicate form columns");
        }
    }

    @FormConfigurationFor(Workshop.class)
    @ListColumns(value = "typo", variant = "unused")
    static class InvalidConfig { }

    @Test
    void invalidUnusedVariantFailsDuringStartup() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Infrastructure.class, InvalidConfig.class);
            assertThatThrownBy(context::refresh).hasStackTraceContaining("typo")
                .hasStackTraceContaining("unused").hasStackTraceContaining(InvalidConfig.class.getName());
        }
    }

    @ListColumns("name")
    static class UnboundConfig { }

    @Test
    void entityBindingIsRequired() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Infrastructure.class, UnboundConfig.class);
            assertThatThrownBy(context::refresh).hasStackTraceContaining("require @FormConfigurationFor");
        }
    }

    @FormConfigurationFor(Workshop.class)
    @ListColumns("name")
    static class MismatchedConfig implements org.ipro.form.builder.ListFormCustomization {
        public Class<?> entityClass() { return String.class; }
        public void configure(org.ipro.form.builder.ListFormVariants variants) { }
    }

    @Test
    void staticEntityBindingMustAgreeWithCustomization() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Infrastructure.class, MismatchedConfig.class);
            assertThatThrownBy(context::refresh).hasStackTraceContaining("conflicts with entityClass()");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HiddenConfiguration {
        @Bean Object hidden() { return new HiddenColumnsConfig(); }
    }

    @FormConfigurationFor(Workshop.class)
    @ListColumns("name")
    static class HiddenColumnsConfig { }

    @Test
    void hiddenBeanTypeCannotSilentlyLoseItsDeclarations() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Infrastructure.class, HiddenConfiguration.class);
            assertThatThrownBy(context::refresh).hasStackTraceContaining("concrete bean return type");
        }
    }

    @FormConfigurationFor(Workshop.class)
    @ListColumns(properties = @ListColumns.Column(path = "code", width = "8em", flexGrow = 0))
    @SelectionColumns(value = {"name", "code"}, properties = @ListColumns.Column(
        path = "name", label = "Title", inlineFilter = ListColumns.InlineFilter.DISABLED))
    static class PropertiesConfig { }

    @Test
    void earlyPropertiesAreIndependentOfCompositionAndFormKind() {
        try (var context = new AnnotationConfigApplicationContext(Infrastructure.class, PropertiesConfig.class)) {
            var meta = context.getBean(MetadataResolver.class).resolve(Workshop.class);
            assertThat(meta.getListColumnPaths()).extracting(ColumnPath::getKey).containsExactly("code", "name", "branch");
            assertThat(meta.hasExplicitListColumns()).isFalse();
            assertThat(meta.getListColumnPaths().getFirst().getWidth()).isEqualTo("8em");
            assertThat(meta.getSelectColumnPaths()).extracting(ColumnPath::getKey).containsExactly("name", "code");
            assertThat(meta.getSelectColumnPaths().getFirst().getLabel()).isEqualTo("Title");
            assertThat(meta.getSelectColumnPaths().getFirst().isInlineFilterEnabled()).isFalse();
            assertThat(meta.getListColumnPaths().get(1).isInlineFilterEnabled()).isTrue();
        }
    }

    @FormConfigurationFor(Workshop.class)
    @ListColumns(value = "code", properties = @ListColumns.Column(path = "branch", width = "12em"))
    static class OutsideCompositionConfig { }

    @Test
    void propertiesOfExcludedColumnsFailBeforeBeansAreCreated() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Infrastructure.class, OutsideCompositionConfig.class);
            assertThatThrownBy(context::refresh).hasStackTraceContaining("branch")
                .hasStackTraceContaining("outside composition").hasStackTraceContaining(OutsideCompositionConfig.class.getName());
        }
    }
}

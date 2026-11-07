package org.ipro.form.builtin;

import org.ipro.crud.BaseEntity;
import org.ipro.form.builder.ItemFormCustomizationRegistrar;
import org.ipro.form.builder.FormFor;
import org.ipro.form.builder.layout.*;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class FormReadDependenciesTest {
    @jakarta.persistence.Entity @EntityMetadata static class Leaf extends BaseEntity { @FieldMetadata String name; }
    @jakarta.persistence.Entity @EntityMetadata static class Target extends BaseEntity { @jakarta.persistence.ManyToOne @FieldMetadata Leaf leaf; }
    @EntityMetadata static class Root extends BaseEntity { @jakarta.persistence.ManyToOne @FieldMetadata Target target; @FieldMetadata String code; }
    private final MetadataResolver metadata = new MetadataResolver();

    @FormFor(Root.class)
    static class Description extends ItemForm<Root> {
        @Override protected ItemFormLayout layout(Layout l) {
            return l.of(l.field("target").setLookupPaths("leaf.name"),
                l.tabs("tabs").add(l.tab("later").add(
                    l.display("target.leaf.name"),
                    l.display("formatted", value -> "computed").dependsOn("target.leaf"))));
        }
    }

    @FormFor(value = Root.class, variant = "dynamic")
    static class DynamicDescription extends ItemForm<Root> {
        static boolean includeNested;
        @Override protected ItemFormLayout layout(Layout l) {
            return l.of(l.display(includeNested ? "target.leaf.name" : "code"));
        }
    }

    @Test void pathsComeFromTheFinalTreeIncludingLaterTabsAndExplicitFormatterInputs() {
        var layout = new Description().describeLayout(metadata.resolve(Root.class));
        assertThat(FormReadDependencies.readPaths(Root.class, layout))
            .containsExactlyInAnyOrder("target", "target.leaf");
    }

    @Test void removedDisplayDoesNotContributePathsAndScalarInputsNeedNoFetch() {
        var l = new Layout(metadata.resolve(Root.class));
        l.of(l.display("target.leaf.name"), l.display("code"));
        l.expandRemaining();
        l.element("target.leaf.name").remove();
        assertThat(FormReadDependencies.readPaths(Root.class, l.compile())).isEmpty();
    }

    @Test void invalidLookupAndFormatterPathsFailBeforeEditorsAreBuilt() {
        for (String path : List.of("absent", "leaf.absent")) {
            var l = new Layout(metadata.resolve(Root.class));
            l.of(l.field("target").setLookupPaths(path));
            assertThatThrownBy(l::compile).isInstanceOf(IllegalArgumentException.class);
        }
        var l = new Layout(metadata.resolve(Root.class));
        l.of(l.display("computed", value -> "text").dependsOn("target.absent"));
        assertThatThrownBy(l::compile).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void registrySuppliesValidatedPathsBeforeBuildingAForm() {
        var registry = new FormRegistry();
        var registrar = new ItemFormCustomizationRegistrar(registry, List.of());
        registrar.setAnnotatedForms(List.of(Description.class), metadata, new DefaultListableBeanFactory());
        registrar.afterPropertiesSet();
        registrar.afterSingletonsInstantiated();
        assertThat(registry.getItemReadPaths(Root.class, null)).containsExactlyInAnyOrder("target", "target.leaf");
        assertThat(registry.getItemReadPaths(Root.class, "other")).isEmpty();
    }

    @Test void preReadEvaluatesThisOpeningsDescriptionInsteadOfCachingStartupState() {
        var registry = new FormRegistry();
        var registrar = new ItemFormCustomizationRegistrar(registry, List.of());
        registrar.setAnnotatedForms(List.of(DynamicDescription.class), metadata, new DefaultListableBeanFactory());
        DynamicDescription.includeNested = false;
        registrar.afterPropertiesSet();
        registrar.afterSingletonsInstantiated();
        assertThat(registry.getItemReadPaths(Root.class, "dynamic")).isEmpty();
        try {
            DynamicDescription.includeNested = true;
            assertThat(registry.getItemReadPaths(Root.class, "dynamic")).containsExactly("target.leaf");
        } finally {
            DynamicDescription.includeNested = false;
        }
        assertThat(registry.getItemReadPaths(Root.class, "dynamic")).isEmpty();
    }
}

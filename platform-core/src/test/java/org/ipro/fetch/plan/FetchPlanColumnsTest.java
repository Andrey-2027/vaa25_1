package org.ipro.fetch.plan;

import jakarta.persistence.ManyToOne;
import org.ipro.fetch.instance.InstanceName;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.FormColumnDeclarations;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FetchPlanColumnsTest {
    @EntityMetadata
    @InstanceName("code")
    static class Branch { @FieldMetadata String code; }

    @EntityMetadata
    @InstanceName("branch.code")
    static class Target { @ManyToOne @FieldMetadata Branch branch; }

    @EntityMetadata(listColumns = "hidden.branch.code")
    static class Root {
        @ManyToOne @FieldMetadata(grid = @GridColumn(visible = false)) Target hidden;
        @ManyToOne @FieldMetadata Target other;
    }

    static class Row {
        @ManyToOne @FieldMetadata Target visible;
        @ManyToOne @FieldMetadata(grid = @GridColumn(visible = false)) Target dynamic;
    }

    private final MetadataResolver metadata = new MetadataResolver();

    private FetchPlanRegistry registry() {
        var types = List.<Class<?>>of(Root.class, Row.class, Target.class, Branch.class);
        return new FetchPlanRegistry(types, metadata, new InstanceNameResolver(types, metadata));
    }

    @Test
    void listUsesResolvedNestedColumnsInsteadOfVisibleReferences() {
        var registry = registry();
        assertThat(registry.paths(Root.class, FetchScenario.LIST)).containsExactly("hidden.branch");
        assertThat(registry.paths(Root.class, FetchScenario.DETAIL)).contains("other", "other.branch");
    }

    @Test
    void frozenDefaultDeclarationChangesListWhileVariantDoesNotChangeCachedBase() {
        var declarations = new FormColumnDeclarations();
        declarations.register(new FormColumnDeclarations.Declaration(Root.class,
            FormColumnDeclarations.Kind.LIST, null, List.of("other"), "", "DefaultColumns"));
        declarations.register(new FormColumnDeclarations.Declaration(Root.class,
            FormColumnDeclarations.Kind.LIST, "nested", List.of("hidden.branch.code"), "", "NestedVariant"));
        declarations.freeze();
        metadata.setFormColumnDeclarations(declarations);
        var registry = registry();
        var base = registry.plan(Root.class, FetchScenario.LIST);
        assertThat(base.paths()).containsExactly("other", "other.branch");
        var columns = declarations.resolveColumns(metadata.resolve(Root.class),
            FormColumnDeclarations.Kind.LIST, "nested");
        assertThat(registry.pathsWith(Root.class, FetchScenario.LIST, fetchPaths(columns)))
            .containsExactly("hidden.branch", "other", "other.branch");
        assertThat(registry.plan(Root.class, FetchScenario.LIST)).isSameAs(base);
        assertThat(base.paths()).doesNotContain("hidden.branch");
    }

    @Test
    void rowBaseUsesAutomaticColumnsAndDeepensReferencePresentation() {
        assertThat(registry().paths(Row.class, FetchScenario.ROW))
            .containsExactly("visible", "visible.branch");
    }

    @Test
    void sectionDeclarationAndSavedViewCanAddHiddenNestedRowColumnsWithoutMutatingBase() {
        var registry = registry();
        var base = registry.plan(Row.class, FetchScenario.ROW);
        var columns = FormColumnDeclarations.explicit("dynamic.branch.code").resolve(Row.class,
            metadata.resolveRowMetadata(Row.class).getColumnPaths(), "SectionColumns");
        assertThat(registry.pathsWith(Row.class, FetchScenario.ROW, fetchPaths(columns)))
            .containsExactly("dynamic.branch", "visible", "visible.branch");
        assertThat(registry.pathsWith(Row.class, FetchScenario.ROW, List.of("dynamic")))
            .containsExactly("dynamic", "dynamic.branch", "visible", "visible.branch");
        assertThat(registry.plan(Row.class, FetchScenario.ROW)).isSameAs(base);
        assertThat(base.paths()).containsExactly("visible", "visible.branch");
    }

    private static List<String> fetchPaths(List<ColumnPath> columns) {
        return columns.stream().flatMap(column -> column.getFetchPaths().stream()).toList();
    }
}

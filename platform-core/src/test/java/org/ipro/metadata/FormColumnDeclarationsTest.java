package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class FormColumnDeclarationsTest {
    @EntityMetadata(listColumns = "name", selectColumns = "code")
    static class Legacy {
        @FieldMetadata String code;
        @FieldMetadata String name;
        @FieldMetadata String extra;
    }

    @EntityMetadata
    static class Automatic {
        @FieldMetadata String code;
        @FieldMetadata String name;
    }

    @EntityMetadata
    static class Typed {
        @FieldMetadata java.time.LocalDate date;
        @FieldMetadata java.time.LocalDateTime timestamp;
        @FieldMetadata Long count;
        @FieldMetadata java.math.BigDecimal amount;
        @FieldMetadata Boolean enabled;
        @FieldMetadata(grid = @org.ipro.metadata.annotation.GridColumn(width = "180px")) String text;
        @FieldMetadata String legacyFilter;
    }

    @Test
    void defaultsFixOnlyDatesNumbersAndBooleansAndFormCanOverrideLegacyFilter() {
        var columns = new MetadataResolver().resolve(Typed.class).getListColumnPaths();
        var byKey = columns.stream().collect(java.util.stream.Collectors.toMap(ColumnPath::getKey, path -> path));
        assertThat(byKey.get("date").getWidth()).isEqualTo("10em");
        assertThat(byKey.get("timestamp").getWidth()).isEqualTo("15em");
        assertThat(byKey.get("count").getWidth()).isEqualTo("8em");
        assertThat(byKey.get("amount").getWidth()).isEqualTo("10em");
        assertThat(byKey.get("enabled").getWidth()).isEqualTo("6em");
        assertThat(byKey.get("text").getWidth()).isEmpty();
        assertThat(byKey.get("text").getFlexGrow()).isEqualTo(1);
        assertThat(byKey.get("legacyFilter").withProperties(new ColumnPath.Spec("legacyFilter", null, null, null, true))
            .isInlineFilterEnabled()).isTrue();
        assertThat(byKey.get("legacyFilter").isInlineFilterEnabled()).isTrue();
    }

    private FormColumnDeclarations.Declaration declaration(Class<?> entity,
            FormColumnDeclarations.Kind kind, String variant, String source, String... paths) {
        return new FormColumnDeclarations.Declaration(entity, kind, variant, List.of(paths), "", source);
    }

    @Test
    void frozenDeclarationsOverrideLegacyAndPublishTheirOrigin() {
        var declarations = new FormColumnDeclarations();
        declarations.register(declaration(Legacy.class, FormColumnDeclarations.Kind.LIST, null, "ListConfig", "extra", "code"));
        declarations.register(declaration(Legacy.class, FormColumnDeclarations.Kind.SELECTION, null, "SelectionConfig", "name"));
        declarations.freeze();
        var resolver = new MetadataResolver();
        resolver.setFormColumnDeclarations(declarations);
        var metadata = resolver.resolve(Legacy.class);
        assertThat(metadata.getListColumnPaths()).extracting(ColumnPath::getKey).containsExactly("extra", "code");
        assertThat(metadata.getSelectColumnPaths()).extracting(ColumnPath::getKey).containsExactly("name");
        assertThat(metadata.getPropertySymbol("listColumns")).isEqualTo("ListConfig");
        assertThat(declarations.diagnostics(Legacy.class)).allSatisfy(d -> {
            assertThat(d.code()).isEqualTo(MetadataDiagnosticCodes.DUPLICATE_FORM_COLUMN_SOURCE);
            assertThat(d.message()).contains("Config");
        });
    }

    @Test
    void listDefaultDoesNotBecomeSelectionDefaultAndVariantDoesNotChangeMetadata() {
        var declarations = new FormColumnDeclarations();
        declarations.register(declaration(Automatic.class, FormColumnDeclarations.Kind.LIST, null, "Config", "name"));
        declarations.register(declaration(Automatic.class, FormColumnDeclarations.Kind.LIST, "compact", "Config", "code"));
        declarations.freeze();
        var resolver = new MetadataResolver();
        resolver.setFormColumnDeclarations(declarations);
        var metadata = resolver.resolve(Automatic.class);
        assertThat(metadata.getListColumnPaths()).extracting(ColumnPath::getKey).containsExactly("name");
        assertThat(metadata.getSelectColumnPaths()).extracting(ColumnPath::getKey).containsExactly("code", "name");
        assertThat(declarations.find(Automatic.class, FormColumnDeclarations.Kind.LIST, "compact").paths()).containsExactly("code");
        assertThat(resolver.resolve(Automatic.class)).isSameAs(metadata);
        assertThat(metadata.getListColumnPaths()).extracting(ColumnPath::getKey).containsExactly("name");
    }

    @Test
    void backendOnlyPreservesLegacyAndAutomaticColumns() {
        var resolver = new MetadataResolver();
        assertThat(resolver.resolve(Legacy.class).getListColumnPaths()).extracting(ColumnPath::getKey).containsExactly("name");
        assertThat(resolver.resolve(Legacy.class).getSelectColumnPaths()).extracting(ColumnPath::getKey).containsExactly("code");
        assertThat(resolver.resolve(Automatic.class).getListColumnPaths()).hasSize(2);
        assertThat(resolver.getFormColumnDeclarations().diagnostics(Legacy.class)).hasSize(2)
            .allSatisfy(d -> assertThat(d.message()).contains("LegacyFormConfig"));
    }

    @Test
    void emptyDeclarationUsesAutomaticColumnsAndVariantDoesNotInheritExplicitDefault() {
        var declarations = new FormColumnDeclarations();
        declarations.register(declaration(Legacy.class, FormColumnDeclarations.Kind.LIST, null, "Config", new String[0]));
        declarations.register(new FormColumnDeclarations.Declaration(Legacy.class,
            FormColumnDeclarations.Kind.SELECTION, null, List.of("name"), "Pick", "Config"));
        declarations.register(declaration(Legacy.class, FormColumnDeclarations.Kind.SELECTION, "auto", "Config", new String[0]));
        declarations.freeze();
        var resolver = new MetadataResolver();
        resolver.setFormColumnDeclarations(declarations);
        var metadata = resolver.resolve(Legacy.class);
        assertThat(metadata.getListColumnPaths()).hasSize(3);
        assertThat(metadata.hasExplicitListColumns()).isFalse();
        assertThat(metadata.getSelectionFormTitle()).isEqualTo("Pick");
        assertThat(metadata.getPropertySymbol("selectionFormTitle")).isEqualTo("Config");
        assertThat(declarations.resolveColumns(metadata, FormColumnDeclarations.Kind.SELECTION, "auto")).hasSize(3);
        assertThat(metadata.getSelectColumnPaths()).extracting(ColumnPath::getKey).containsExactly("name");
    }

    @Test
    void rejectsReadingBeforeFreezeAndInstallingAfterResolution() {
        var declarations = new FormColumnDeclarations();
        var resolver = new MetadataResolver();
        assertThatThrownBy(() -> resolver.setFormColumnDeclarations(declarations)).hasMessageContaining("frozen");
        assertThatThrownBy(declarations::all).hasMessageContaining("frozen");
        declarations.register(declaration(Automatic.class, FormColumnDeclarations.Kind.LIST, null, "Config", "name"));
        declarations.freeze();
        resolver.resolve(Automatic.class);
        assertThatThrownBy(() -> resolver.setFormColumnDeclarations(declarations)).hasMessageContaining("first metadata resolution");
        assertThatThrownBy(() -> declarations.register(declaration(Automatic.class,
            FormColumnDeclarations.Kind.LIST, "late", "Late", "name"))).hasMessageContaining("frozen");
    }

    @Test
    void duplicateSourcesAndInvalidUnopenedVariantFailBeforePublication() {
        var declarations = new FormColumnDeclarations();
        declarations.register(declaration(Automatic.class, FormColumnDeclarations.Kind.LIST, null, "First", "name"));
        assertThatThrownBy(() -> declarations.register(declaration(Automatic.class,
            FormColumnDeclarations.Kind.LIST, "", "Second", "code"))).hasMessageContaining("First").hasMessageContaining("Second");
        declarations.register(declaration(Automatic.class, FormColumnDeclarations.Kind.LIST, "neverOpened", "BrokenConfig", "missing"));
        assertThatThrownBy(declarations::freeze).hasMessageContaining("BrokenConfig")
            .hasMessageContaining("neverOpened").hasMessageContaining("missing");
    }

    @Test
    void propertiesDoNotFreezeOrReorderAutomaticComposition() {
        var auto = FormColumnDeclarations.auto();
        auto.column("name").setWidth("20em").setFlexGrow(1).setLabel("Title").setInlineFilter(false);
        var explicit = FormColumnDeclarations.explicit("name", "code");
        explicit.column("name").setWidth("12em");
        var declarations = new FormColumnDeclarations();
        declarations.register(new FormColumnDeclarations.Declaration(Legacy.class,
            FormColumnDeclarations.Kind.LIST, null, auto, "", "AutoConfig"));
        declarations.register(new FormColumnDeclarations.Declaration(Legacy.class,
            FormColumnDeclarations.Kind.LIST, "fixed", explicit, "", "FixedConfig"));
        declarations.freeze();
        // The declaration captures a snapshot, not this mutable builder.
        auto.column("name").setWidth("99em");
        var resolver = new MetadataResolver();
        resolver.setFormColumnDeclarations(declarations);
        var meta = resolver.resolve(Legacy.class);
        assertThat(meta.getListColumnPaths()).extracting(ColumnPath::getKey).containsExactly("code", "name", "extra");
        var name = meta.getListColumnPaths().get(1);
        assertThat(name.getWidth()).isEqualTo("20em");
        assertThat(name.getFlexGrow()).isEqualTo(1);
        assertThat(name.getLabel()).isEqualTo("Title");
        assertThat(name.isInlineFilterEnabled()).isFalse();
        assertThat(declarations.find(Legacy.class, FormColumnDeclarations.Kind.LIST, null).mode())
            .isEqualTo(FormColumnDeclarations.Mode.AUTO);
        var fixed = declarations.resolveColumns(meta, FormColumnDeclarations.Kind.LIST, "fixed");
        assertThat(fixed).extracting(ColumnPath::getKey).containsExactly("name", "code");
        assertThat(fixed.getFirst().getWidth()).isEqualTo("12em");
        assertThat(meta.hasExplicitListColumns()).isFalse();
    }

    @Test
    void propertyOutsideCompositionFailsAtFreezeWithItsSource() {
        var columns = FormColumnDeclarations.explicit("code");
        columns.column("name").setInlineFilter(false);
        var declarations = new FormColumnDeclarations();
        declarations.register(new FormColumnDeclarations.Declaration(Automatic.class,
            FormColumnDeclarations.Kind.LIST, "unused", columns, "", "BrokenConfig"));
        assertThatThrownBy(declarations::freeze).hasMessageContaining("name")
            .hasMessageContaining("unused").hasMessageContaining("BrokenConfig");
    }

    @Test
    void savedPropertiesRoundTripAndOldViewsKeepTheirFormat() {
        var column = ColumnPath.resolve(Automatic.class, "name")
            .withProperties(new ColumnPath.Spec("name", "Title", "24em", 2, false));
        var state = GridViewState.of(List.of(column), Automatic.class, List.of());
        var restored = ColumnPath.fromJson(state.toJson(), Automatic.class).getFirst();
        assertThat(restored.toSpec(Automatic.class)).isEqualTo(column.toSpec(Automatic.class));
        assertThat(ColumnPath.toJson(List.of(ColumnPath.resolve(Automatic.class, "code")), Automatic.class))
            .isEqualTo("[{\"path\":\"code\",\"label\":null}]");
        assertThat(ColumnPath.fromJson("code;name=Old title", Automatic.class))
            .extracting(ColumnPath::getLabel).containsExactly("code", "Old title");
    }
}

package org.ipro.form;

import com.vaadin.flow.component.grid.Grid;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.TableSectionService;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ItemTable;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridView;
import org.ipro.form.spi.GridViewStore;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.FormColumnDeclarations;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.TableSectionGridMetadata;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.metadata.annotation.TableSections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TableSectionColumnsTest {
    @TableSections({Row.class, ExtendedRow.class})
    static class Document extends BaseEntity { }

    static class RowBase extends BaseEntity {
        Document document;
        @FieldMetadata(label = "Код", grid = @GridColumn(order = 1, width = "140px")) String code;
    }

    @TableSectionMetadata(parentEntity = Document.class, parentField = "document")
    static class Row extends RowBase {
        @FieldMetadata(label = "Имя", grid = @GridColumn(order = 2, flexGrow = 1)) String name;
    }

    @TableSectionMetadata(parentEntity = Document.class, parentField = "document")
    static class ExtendedRow extends Row {
        @FieldMetadata(grid = @GridColumn(order = 3)) String extra;
    }

    private final MetadataResolver metadata = new MetadataResolver();
    private final FieldFactory fields = mock(FieldFactory.class);
    private final ApplicationContext context = mock(ApplicationContext.class);
    private final GenericOwnedSectionService service = mock(GenericOwnedSectionService.class);

    private TableSectionMetadataInfo section(Class<?> row) {
        return metadata.resolveTableSections(Document.class).stream()
            .filter(section -> section.getRowClass() == row).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private TableSectionFactory factory(List<TableSectionCustomization<?>> customizations) {
        var resolver = mock(FormResolver.class);
        when(resolver.getFormRegistry()).thenReturn(new FormRegistry());
        var provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(resolver);
        return new TableSectionFactory(metadata, fields, context, service, provider, customizations);
    }

    private static Grid<?> grid(ItemTable<?, ?> table) {
        return (Grid<?>) table.getChildren().filter(Grid.class::isInstance).findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void explicitColumnsExistBeforeBehaviorAndDoNotMutateSectionMetadata() {
        var configured = new AtomicBoolean();
        var customization = new TableSectionCustomization<ExtendedRow>() {
            public Class<ExtendedRow> rowClass() { return ExtendedRow.class; }
            public FormColumnDeclarations.Columns columns() {
                var columns = FormColumnDeclarations.explicit("name", "code");
                columns.column("name").setWidth("24em").setFlexGrow(2).setLabel("Название");
                return columns;
            }
            public void configure(ItemTable<ExtendedRow, ?> table) {
                assertThat(table.getActiveColumns()).extracting(ColumnPath::getKey).containsExactly("name", "code");
                assertThat(grid(table).getColumnByKey("name").getWidth()).isEqualTo("24em");
                assertThat(grid(table).getColumnByKey("name").getFlexGrow()).isEqualTo(2);
                assertThat(grid(table).getColumnByKey("code").getWidth()).isEqualTo("140px");
                assertThat(table.getActiveColumns().getFirst().getLabel()).isEqualTo("Название");
                configured.set(true);
            }
        };
        var factory = factory(List.of(customization));
        factory.run(new DefaultApplicationArguments());
        verifyNoInteractions(fields, context, service);
        ItemForm<Document> form = mock(ItemForm.class);
        factory.attachTableSections(form, Document.class, List.of(ExtendedRow.class));
        assertThat(configured).isTrue();
        var table = org.mockito.ArgumentCaptor.forClass(ItemTable.class);
        verify(form).addTableSection(anyString(), table.capture());
        var adapter = new TableSectionGridMetadata(section(ExtendedRow.class), factory.resolveColumns(section(ExtendedRow.class)));
        assertThat(adapter.getListColumnPaths()).extracting(ColumnPath::getKey).containsExactly("name", "code");
        assertThat(new TableSectionGridMetadata(section(ExtendedRow.class)).getListColumnPaths())
            .extracting(ColumnPath::getKey).containsExactly("code", "name", "extra");
    }

    @Test
    void automaticCompositionKeepsAddedFieldsAndFreezesOnlyPropertiesAtStartup() {
        var columns = FormColumnDeclarations.auto();
        columns.column("code").setWidth("8em").setFlexGrow(1);
        var factory = factory(List.of(config(ExtendedRow.class, columns)));
        factory.run(new DefaultApplicationArguments());
        columns.column("code").setWidth("99em");
        var resolved = factory.resolveColumns(section(ExtendedRow.class));
        assertThat(resolved).extracting(ColumnPath::getKey).containsExactly("code", "name", "extra");
        assertThat(resolved.getFirst().getWidth()).isEqualTo("8em");
        assertThat(resolved.getFirst().getFlexGrow()).isEqualTo(1);
        assertThat(section(ExtendedRow.class).getGridFields().getFirst().getGridWidth()).isEqualTo("140px");
    }

    @Test
    @SuppressWarnings("unchecked")
    void baseDeclarationDoesNotFreezeSubclassesAndLegacyBehaviorDoesNotInherit() {
        TableSectionCustomization<RowBase> base = spy(config(RowBase.class, FormColumnDeclarations.explicit("code")));
        var factory = factory(List.of(base));
        factory.run(new DefaultApplicationArguments());
        assertThat(factory.resolveColumns(section(ExtendedRow.class))).extracting(ColumnPath::getKey)
            .containsExactly("code", "name", "extra");
        assertThat(factory.resolveColumns(section(Row.class))).extracting(ColumnPath::getKey)
            .containsExactly("code", "name");
        factory.attachTableSections(mock(ItemForm.class), Document.class, List.of(ExtendedRow.class));
        verify(base, never()).configure(any());
    }

    @Test
    void invalidColumnsFailDuringStartupWithoutConstructingTables() {
        var outside = FormColumnDeclarations.explicit("code");
        outside.column("name").setWidth("12em");
        for (var columns : List.of(FormColumnDeclarations.explicit("missing"), outside,
                FormColumnDeclarations.explicit("code", "code"))) {
            assertThatThrownBy(() -> factory(List.of(config(Row.class, columns))).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(Row.class.getName())
                .hasMessageContaining(TableSectionColumnsTest.class.getName());
        }
        verifyNoInteractions(fields, context, service);
    }

    @Test
    void duplicateDeclarationsAndUnsupportedHeaderFiltersAreRejected() {
        var first = config(Row.class, FormColumnDeclarations.auto());
        var second = config(Row.class, FormColumnDeclarations.explicit("name"));
        assertThatThrownBy(() -> factory(List.of(first, second)).run(new DefaultApplicationArguments()))
            .hasMessageContaining("Duplicate section columns").hasMessageContaining(Row.class.getName());
        var columns = FormColumnDeclarations.auto();
        columns.column("code").setInlineFilter(false);
        assertThatThrownBy(() -> factory(List.of(config(Row.class, columns))).run(new DefaultApplicationArguments()))
            .hasMessageContaining("inlineFilter").hasMessageContaining(Row.class.getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void savedWidthWinsAndResetRestoresFormWithoutLosingDirtyRows() {
        var columns = FormColumnDeclarations.auto();
        columns.column("code").setWidth("8em").setFlexGrow(2);
        var factory = factory(List.of(config(Row.class, columns)));
        var defaults = factory.resolveColumns(section(Row.class));
        var store = mock(GridViewStore.class);
        var settings = mock(FormSettingsStore.class);
        when(settings.get("listform.defaultview.Row")).thenReturn(Optional.of("7"));
        when(store.findById(7L)).thenReturn(Optional.of(new GridView(7L, "Row", "Saved",
            "{\"columns\":[{\"path\":\"code\",\"width\":\"32em\",\"flexGrow\":0}]}", false, "user")));
        var rows = mock(TableSectionService.class);
        ItemTable<Row, Document> table = new ItemTable<>(section(Row.class), fields, rows, metadata,
            store, settings, null, () -> null, defaults);
        assertThat(grid(table).getColumnByKey("code").getWidth()).isEqualTo("32em");
        assertThat(grid(table).getColumnByKey("code").getFlexGrow()).isZero();
        grid(table).getColumnByKey("code").setWidth("35em");
        assertThat(table.captureViewState().columns().getFirst().width()).isEqualTo("35em");
        table.markDirty();
        table.resetActiveColumns();
        assertThat(table.getActiveColumns()).extracting(ColumnPath::getKey).containsExactly("code", "name");
        assertThat(grid(table).getColumnByKey("code").getWidth()).isEqualTo("8em");
        assertThat(grid(table).getColumnByKey("code").getFlexGrow()).isEqualTo(2);
        assertThat(table.isDirty()).isTrue();
        verifyNoInteractions(rows);
    }

    @Test
    @SuppressWarnings("unchecked")
    void oldConstructorRetainsLegacyWidthsAndFlexibleName() {
        ItemTable<Row, Document> table = new ItemTable<>(section(Row.class), fields,
            mock(TableSectionService.class), metadata, null, null, null, () -> null);
        assertThat(table.getActiveColumns()).extracting(ColumnPath::getKey).containsExactly("code", "name");
        assertThat(grid(table).getColumnByKey("code").getWidth()).isEqualTo("140px");
        assertThat(grid(table).getColumnByKey("code").getFlexGrow()).isZero();
        assertThat(grid(table).getColumnByKey("name").getFlexGrow()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void migratedWidthHintKeepsLegacyAutoWidthAndSavedWidthStillWins() {
        var columns = FormColumnDeclarations.auto();
        columns.column("code").setWidth("140px").setAutoWidth(true);
        var defaults = factory(List.of(config(Row.class, columns))).resolveColumns(section(Row.class));
        var store = mock(GridViewStore.class);
        var settings = mock(FormSettingsStore.class);
        when(settings.get("listform.defaultview.Row")).thenReturn(Optional.of("7"));
        when(store.findById(7L)).thenReturn(Optional.of(new GridView(7L, "Row", "Saved",
            "{\"columns\":[{\"path\":\"code\",\"width\":\"32em\",\"flexGrow\":0}]}", false, "user")));
        ItemTable<Row, Document> table = new ItemTable<>(section(Row.class), fields,
            mock(TableSectionService.class), metadata, store, settings, null, () -> null, defaults);
        assertThat(grid(table).getColumnByKey("code").getWidth()).isEqualTo("32em");
        assertThat(grid(table).getColumnByKey("code").isAutoWidth()).isFalse();
        table.resetActiveColumns();
        assertThat(grid(table).getColumnByKey("code").getWidth()).isEqualTo("140px");
        assertThat(grid(table).getColumnByKey("code").isAutoWidth()).isTrue();
        var json = ColumnPath.toJson(defaults, Row.class);
        assertThat(ColumnPath.fromJson(json, Row.class).getFirst().toSpec(Row.class).autoWidth()).isTrue();
    }

    private static <T extends org.ipro.identity.IdentifiableEntity<Long>> TableSectionCustomization<T> config(
            Class<T> type, FormColumnDeclarations.Columns columns) {
        return new TableSectionCustomization<>() {
            public Class<T> rowClass() { return type; }
            public FormColumnDeclarations.Columns columns() { return columns; }
            public void configure(ItemTable<T, ?> table) { }
        };
    }
}

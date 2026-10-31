package org.ipro.query.ui.constructor;

import org.ipro.query.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListSettingsDialogTest {
    private QueryConstructorDraft draft() {
        var catalog = mock(QueryBuilderMetadataCatalog.class);
        var entity = new QueryBuilderMetadataCatalog.Entity("Product", String.class, List.of(
                new QueryBuilderMetadataCatalog.Field("id", "Id", Long.class, true),
                new QueryBuilderMetadataCatalog.Field("code", "Code", String.class, false),
                new QueryBuilderMetadataCatalog.Field("name", "Name", String.class, false)), List.of());
        when(catalog.roots()).thenReturn(List.of(entity)); when(catalog.root("Product")).thenReturn(entity);
        var draft = new QueryConstructorDraft(); draft.setCatalog(catalog);
        draft.load(source().main()); return draft;
    }
    private VisualQueryPackage source() {
        return new VisualQueryPackage(List.of(), new VisualQueryDefinition("Product", "p", List.of(
                new VisualQueryDefinition.SelectField("p.code", "code"), new VisualQueryDefinition.SelectField("p.name", "name"))));
    }

    @Test
    void addEditAndDeleteResultField() {
        var draft = draft();
        var dialog = new ListSettingsDialog(draft, null, () -> { });
        dialog.setSource(source()); dialog.keyField().setValue("code"); dialog.valueField().setValue("name");
        dialog.targetField().setValue("p.code"); dialog.aliasField().setValue("names");
        dialog.separatorField().setValue("  ' ;  ");
        assertThat(dialog.apply()).isTrue();
        assertThat(draft.lists()).hasSize(1);
        var list = draft.lists().getFirst();
        assertThat(list.separator()).isEqualTo("  ' ;  ");
        var tab = new TablesAndFieldsTab(draft, () -> { }); tab.refreshFromDraft();
        var node = tab.tableRoots().stream().filter(n -> n.entity().entityName().equals(list.name())).findFirst().orElseThrow();
        assertThat(node.entity().caption()).startsWith("Список: ");
        assertThat(node.children()).noneMatch(n -> n.field() != null && n.field().technical());
        var edit = new ListSettingsDialog(draft, list, () -> { });
        assertThat(edit.targetField().getListDataView().getItems().toList())
                .noneMatch(path -> path.startsWith(draft.cteFieldAlias(list.name()) + "."));
        edit.separatorField().setValue(""); edit.aliasField().setValue("labels");
        edit.targetField().setValue("p.name");
        assertThat(edit.apply()).isTrue();
        assertThat(draft.lists().getFirst().separator()).isEmpty();
        assertThat(draft.compile().jpql()).contains(".key = p.name");
        var selected = draft.selections().stream().filter(f -> f.resultName().equals("labels")).findFirst().orElseThrow();
        draft.removeSelection(selected); draft.pruneUnusedLists();
        assertThat(draft.lists()).isEmpty();
    }

    @Test
    void rejectedInputAndCancelDoNotChangeDraft() {
        var draft = draft(); var original = draft.packageDefinition();
        var dialog = new ListSettingsDialog(draft, null, () -> { });
        dialog.setSource(source()); dialog.keyField().setValue("code"); dialog.valueField().setValue("name");
        dialog.targetField().setValue("p.id");
        assertThat(dialog.apply()).isFalse();
        assertThat(draft.lists()).isEmpty();
        dialog.targetField().setValue("p.code"); dialog.aliasField().setValue("code");
        assertThat(dialog.apply()).isFalse();
        dialog.close();
        assertThat(draft.packageDefinition()).isEqualTo(original);
    }
}

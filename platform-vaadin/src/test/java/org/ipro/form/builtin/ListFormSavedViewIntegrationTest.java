package org.ipro.form.builtin;

import com.vaadin.flow.component.grid.Grid;
import org.ipro.crud.BaseEntity;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.filter.LogicalOperator;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.GridViewState;
import org.ipro.form.spi.GridView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListFormSavedViewIntegrationTest {
    @Test
    void editingSavedColumnsKeepsWidthAndFlexGrow() {
        var resolver = new org.ipro.metadata.MetadataResolver();
        var metadata = resolver.resolve(EditorEntity.class);
        var store = mock(org.ipro.form.spi.GridViewStore.class);
        var initial = ColumnPath.resolve(EditorEntity.class, "code")
            .withProperties(new ColumnPath.Spec("code", "Custom code", "21em", 0, false));
        var view = new GridView(1L, "editor", "Saved", "", false, "test");
        when(store.createView(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(view);
        var dialog = new GridViewEditorDialog(metadata, resolver, store, null, "editor", null,
            List.of(initial), null, "Saved", false, saved -> { }, null);
        dialog.getFooter().getElement().getChildren().map(element -> element.getComponent().orElse(null))
            .filter(component -> component instanceof com.vaadin.flow.component.button.Button)
            .map(component -> (com.vaadin.flow.component.button.Button) component)
            .filter(button -> button.getText().equals("Сохранить")).findFirst().orElseThrow().click();
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(store).createView(org.mockito.ArgumentMatchers.eq("editor"),
            org.mockito.ArgumentMatchers.eq("Saved"), json.capture(), org.mockito.ArgumentMatchers.eq(false));
        assertThat(GridViewState.fromJson(json.getValue()).columns()).containsExactly(initial.toSpec(EditorEntity.class));
    }

    @org.ipro.metadata.annotation.EntityMetadata
    static class EditorEntity extends BaseEntity {
        @org.ipro.metadata.annotation.FieldMetadata String code;
    }

    @Test
    void savedViewRestoresNestedUserFilterAndKeepsBottomBarAction() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestEntity.class).when(metadata).getEntityClass();
        when(metadata.getListColumnPaths()).thenReturn(List.of(ColumnPath.resolve(TestEntity.class, "id")));
        ListForm<TestEntity, Long> form = newForm(metadata);

        FilterNode fixed = node("id", FilterOperator.EQ, "1");
        FilterNode user = FilterGroup.or(node("id", FilterOperator.EQ, "2"), node("id", FilterOperator.EQ, "3"));
        GridView view = new GridView(null, "test", "saved", GridViewState.of(
            List.of(ColumnPath.resolve(TestEntity.class, "id")), TestEntity.class, fixed, user).toJson(), true, null);

        invokeApplyView(form, view);

        assertThat(form.getVisualFilter()).isNotNull();
        assertThat(form.getFilterGrid().visualFilterButton()).isNotNull();
        assertThat(form.getFilterGrid().visualFilter()).isNull();
    }

    @Test
    void savedViewWithEmptyConditionGroupIsNotAFilter() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestEntity.class).when(metadata).getEntityClass();
        when(metadata.getListColumnPaths()).thenReturn(List.of(ColumnPath.resolve(TestEntity.class, "id")));
        ListForm<TestEntity, Long> form = newForm(metadata);

        // Пустая группа — артефакт редактора вида: удаление последнего условия из группы
        // оставляет пустую группу, а у корня нет кнопки удаления. В JPA такая группа
        // компилируется в cb.or() без аргументов, то есть в «ложь» (ноль строк),
        // поэтому сохранённый вид без условий не должен становиться фильтром.
        FilterNode empty = new FilterGroup(LogicalOperator.OR, List.of());
        GridView view = new GridView(null, "test", "saved-empty", GridViewState.of(
            List.of(ColumnPath.resolve(TestEntity.class, "id")), TestEntity.class, null, empty)
            .toJson(), true, null);

        invokeApplyView(form, view);

        assertThat(form.getVisualFilter()).isNull();
    }

    private static void invokeApplyView(ListForm<TestEntity, Long> form, GridView view) {
        try {
            var method = ListForm.class.getDeclaredMethod("applyView", GridView.class);
            method.setAccessible(true);
            method.invoke(form, view);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static ListForm<TestEntity, Long> newForm(EntityMetadataInfo metadata) {
        return new ListForm<>(metadata, new org.ipro.filtergrid.FilterGrid<TestEntity>(TestEntity.class) {
            private final Grid<TestEntity> grid = new Grid<>(TestEntity.class, false);
            { add(grid); }
            @Override protected org.ipro.filtergrid.FilterSpecification<TestEntity> buildSpecification() {
                return (root, query, cb) -> cb.conjunction();
            }
            @Override public Grid<TestEntity> getGrid() { return grid; }
        });
    }

    private static FilterNode node(String path, FilterOperator operator, String value) {
        return FilterConditionNode.of(new FilterCondition(path, operator, value, null, FilterDataType.TEXT));
    }

    static class TestEntity extends BaseEntity {
    }
}

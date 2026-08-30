package org.ipro.filter;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FilterTreeEditorTest {
    enum Status { ACTIVE, ARCHIVED }

    @Test
    void rendersGroupAndControlsAndReportsChanges() {
        var field = new FilterFieldResolver.ResolvedFilterField("status", "Статус", Status.class,
                FilterDataType.ENUM, true);
        var resolver = new FilterFieldResolver() {
            public List<ResolvedFilterField> fields() { return List.of(field); }
            public ResolvedFilterField resolve(String path) { return field; }
            public List<?> valueOptions(ResolvedFilterField ignored) { return List.of(Status.values()); }
        };
        var changed = new AtomicReference<FilterNode>();
        var editor = new FilterTreeEditor(resolver, FilterGroup.and(), changed::set);

        assertThat(editor.getValue()).isInstanceOf(FilterGroup.class);
        assertThat(editor.getElement().getChildCount()).isGreaterThan(0);
    }

    @Test
    void addingConditionDoesNotCreateInvalidEmptyValue() {
        var field = new FilterFieldResolver.ResolvedFilterField("name", "Наименование", String.class,
                FilterDataType.TEXT, true);
        var resolver = new FilterFieldResolver() {
            public List<ResolvedFilterField> fields() { return List.of(field); }
            public ResolvedFilterField resolve(String path) { return field; }
        };
        var editor = new FilterTreeEditor(resolver, FilterGroup.and(), ignored -> { });
        assertThat(editor.validationErrors()).contains("корень: пустая группа");

        // The newly inserted draft condition is valid before the user supplies a value.
        var draft = new FilterCondition("name", FilterOperator.IS_NULL, null, null, FilterDataType.TEXT);
        assertThat(draft.value()).isNull();
    }

    @Test
    void emptyStateOffersAddConditionAndGroupButtons() {
        var field = new FilterFieldResolver.ResolvedFilterField("name", "Наименование", String.class,
                FilterDataType.TEXT, true);
        var resolver = new FilterFieldResolver() {
            public List<ResolvedFilterField> fields() { return List.of(field); }
            public ResolvedFilterField resolve(String path) { return field; }
        };
        var editor = new FilterTreeEditor(resolver, null, ignored -> { });
        assertThat(editor.getValue()).isNull();
        assertThat(buttonsOf(editor)).extracting(Button::getText)
                .contains("+ Условие", "+ Группа");
    }

    @Test
    void entityReferenceConditionRendersSelectorButtonWhenProvided() {
        Object unit = new Object() { @Override public String toString() { return "м2"; } };
        var field = new FilterFieldResolver.ResolvedFilterField("unitOfMeasurement", "Единица измерения",
                Object.class, FilterDataType.ENTITY_REFERENCE, true);
        var resolver = new FilterFieldResolver() {
            public List<ResolvedFilterField> fields() { return List.of(field); }
            public ResolvedFilterField resolve(String path) { return field; }
            public List<?> valueOptions(ResolvedFilterField ignored) { return List.of(unit); }
        };
        var opened = new AtomicReference<Class<?>>();
        var editor = new FilterTreeEditor(resolver,
                FilterGroup.and(FilterConditionNode.of(new FilterCondition(
                        "unitOfMeasurement", FilterOperator.EQ, null, null, FilterDataType.ENTITY_REFERENCE))),
                ignored -> { });
        editor.setEntitySelector((entityClass, onSelect) -> opened.set(entityClass));

        assertThat(buttonsOf(editor)).extracting(Button::getText).contains("Выбрать…");
        assertThat(opened.get()).isNull();
    }

    @Test
    void validationRejectsMissingValueAndIncompleteBetween() {
        var field = new FilterFieldResolver.ResolvedFilterField("name", "Наименование", String.class,
                FilterDataType.TEXT, true);
        var resolver = new FilterFieldResolver() {
            public List<ResolvedFilterField> fields() { return List.of(field); }
            public ResolvedFilterField resolve(String path) { return field; }
        };
        var blankValue = new FilterTreeEditor(resolver,
                FilterGroup.and(FilterConditionNode.of(new FilterCondition(
                        "name", FilterOperator.EQ, "", null, FilterDataType.TEXT))),
                ignored -> { });
        assertThat(blankValue.validationErrors()).contains("корень.1: укажите значение");

        var incompleteBetween = new FilterTreeEditor(resolver,
                FilterGroup.and(FilterConditionNode.of(new FilterCondition(
                        "name", FilterOperator.BETWEEN, "a", null, FilterDataType.TEXT))),
                ignored -> { });
        assertThat(incompleteBetween.validationErrors()).contains("корень.1: укажите оба значения (от и до)");
    }

    private static List<Button> buttonsOf(Component component) {
        List<Button> result = new ArrayList<>();
        if (component instanceof Button button) {
            result.add(button);
        }
        component.getChildren().forEach(child -> result.addAll(buttonsOf(child)));
        return result;
    }

    @Test
    void lookupResolverProvidesRealObjectsAndCanonicalEnumValues() {
        Object lookup = new Object() { @Override public String toString() { return "Warehouse 1"; } };
        var field = new FilterFieldResolver.ResolvedFilterField("status", "Статус", Status.class,
                FilterDataType.ENUM, true);
        var resolver = new LookupFilterFieldResolver(new FilterFieldResolver() {
            public List<ResolvedFilterField> fields() { return List.of(field); }
            public ResolvedFilterField resolve(String path) { return field; }
        }, ignored -> List.of(lookup));
        var lookupField = new FilterFieldResolver.ResolvedFilterField("warehouse", "Склад", Object.class,
                FilterDataType.ENTITY_REFERENCE, true);
        assertThat(resolver.valueOptions(lookupField).get(0)).isSameAs(lookup);
    }
}

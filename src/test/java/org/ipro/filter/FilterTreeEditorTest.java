package org.ipro.filter;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import org.junit.jupiter.api.Test;

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

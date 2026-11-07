package org.ipro.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.Query;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.BaseService;
import org.ipro.crud.LookupService;
import org.ipro.crud.ServiceLocator;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.layout.*;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FieldLookupDependenciesTest {
    @jakarta.persistence.Entity @EntityMetadata static class Leaf extends BaseEntity { @FieldMetadata String name; }
    @jakarta.persistence.Entity @EntityMetadata static class Target extends BaseEntity implements HasDisplayName {
        @FieldMetadata String code;
        @jakarta.persistence.ManyToOne @FieldMetadata Leaf leaf;
        public String getDisplayName() { return code; }
    }
    @EntityMetadata static class Root extends BaseEntity { @jakarta.persistence.ManyToOne @FieldMetadata Target target; }

    private static TextField text(Component root) {
        if (root instanceof TextField field) return field;
        return root.getChildren().map(FieldLookupDependenciesTest::text).filter(java.util.Objects::nonNull)
            .findFirst().orElse(null);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void formPathsReachAutocompleteAndSelectionWithTheSameFixedFilter() throws Exception {
        var metadata = new MetadataResolver();
        var lookup = mock(LookupService.class);
        var assembler = mock(SelectionFormAssembler.class);
        var resolved = new SelectionFormAssembler.ResolvedSelection(List.of(), "Target");
        when(assembler.resolveColumns(Target.class)).thenReturn(resolved);
        SelectionForm<Target> selection = mock(SelectionForm.class);
        when(assembler.assemble(eq(Target.class), any(), anyMap(), eq(resolved), isNull(), anyCollection()))
            .thenReturn(selection);
        var fields = new FieldFactory(lookup, assembler, metadata, new FormRegistry());
        var l = new Layout(metadata.resolve(Root.class));
        l.of(l.field("target").setLookupPaths("leaf"));
        ItemForm<Root> form = new ItemForm<>(metadata.resolve(Root.class), fields, l.compile());
        EntityField<Target> field = form.entityField("target");
        var filters = Map.<String, Object>of("code", "A");
        field.setSelectionFilter(filters);
        text(field).setValue("a");
        verify(lookup).search(eq(Target.class), any(String[].class), eq("a"), eq(20), eq(List.of("leaf")), eq(filters));
        var open = EntityField.class.getDeclaredMethod("openSelectionDialog");
        open.setAccessible(true);
        open.invoke(field);
        verify(assembler).assemble(eq(Target.class), any(), eq(filters), eq(resolved), isNull(), eq(List.of("leaf")));
        verify(selection).open();
        assertThatThrownBy(() -> field.setSelectionFilter(filters, (onSelect, fixed) -> selection))
            .isInstanceOf(IllegalStateException.class);
        ItemForm<Root> independent = new ItemForm<>(metadata.resolve(Root.class), fields);
        assertThat(independent.entityField("target").getLookupPaths()).isEmpty();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void selectionGridExpandsLookupReadWithoutChangingTargetDefaults() {
        var metadata = new MetadataResolver();
        var locator = mock(ServiceLocator.class);
        BaseService<Target, Long> service = mock(BaseService.class);
        when(locator.<Target, Long>findService(Target.class)).thenReturn(service);
        when(service.findAllByScenario(any(), any(), any(), anyCollection())).thenReturn(Page.empty());
        var assembler = new SelectionFormAssembler(metadata, locator);
        var resolved = assembler.resolveColumns(Target.class);
        var form = assembler.assemble(Target.class, value -> { }, Map.of(), resolved, null, List.of("leaf"));
        form.getFilterGrid().getGrid().getDataProvider().fetch(new Query<>()).toList();
        verify(service, atLeastOnce()).findAllByScenario(eq(FetchScenario.LOOKUP), any(), any(),
            argThat(paths -> paths.contains("leaf")));
        assertThat(metadata.getFormColumnDeclarations().all()).isEmpty();
    }

    @Test void filterOnlySetterCannotLoseFiltersInALegacySelectionFactory() {
        EntityField<Target> field = new EntityField<>("Target", (FilterableSearchFunction<Target>)
            (term, filters) -> List.of());
        field.setSelectionFormFactory(onSelect -> null);
        assertThatThrownBy(() -> field.setSelectionFilter(Map.of("code", "A")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("fixed filters");
    }
}

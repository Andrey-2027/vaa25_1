package org.ipro.form.builtin;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.textfield.TextField;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.LookupService;
import org.ipro.form.*;
import org.ipro.form.builder.FormBehavior;
import org.ipro.form.builder.layout.*;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RelatedFieldsTest {
    @Entity @EntityMetadata public static class Unit extends BaseEntity implements HasDisplayName {
        @FieldMetadata String name;
        public String getName() { return name; }
        public String getDisplayName() { return name; }
    }
    @Entity @EntityMetadata public static class Source extends BaseEntity implements HasDisplayName {
        @FieldMetadata String name;
        @ManyToOne @FieldMetadata Unit unit;
        public Unit getUnit() { return unit; }
        public String getDisplayName() { return name; }
    }
    @Entity @EntityMetadata public static class Parent extends BaseEntity implements HasDisplayName {
        @FieldMetadata String name;
        public String getDisplayName() { return name; }
    }
    @Entity @EntityMetadata public static class Choice extends BaseEntity implements HasDisplayName {
        @FieldMetadata String name;
        @FieldMetadata String kind;
        @ManyToOne @FieldMetadata Parent owner;
        @ManyToOne @FieldMetadata Root rootOwner;
        public Parent getOwner() { return owner; }
        public Root getRootOwner() { return rootOwner; }
        public String getKind() { return kind; }
        public String getDisplayName() { return name; }
    }
    @Entity @EntityMetadata public static class Root extends BaseEntity implements HasDisplayName {
        @FieldMetadata String code;
        @FieldMetadata String copy;
        @FieldMetadata Long large;
        @FieldMetadata Long largeCopy;
        @FieldMetadata Integer small;
        @ManyToOne @FieldMetadata Source source;
        @ManyToOne @FieldMetadata Unit unit;
        @ManyToOne @FieldMetadata Parent parent;
        @ManyToOne @FieldMetadata Choice choice;
        public String getDisplayName() { return code; }
    }

    private final MetadataResolver metadata = new MetadataResolver();
    private ItemForm<Root> form(Function<Layout, ItemFormLayout> description) {
        var assembler = mock(SelectionFormAssembler.class);
        when(assembler.resolveColumns(any())).thenReturn(new SelectionFormAssembler.ResolvedSelection(List.of(), "Select"));
        var fields = new FieldFactory(mock(LookupService.class), assembler, metadata, new FormRegistry());
        var l = new Layout(metadata.resolve(Root.class));
        description.apply(l); l.expandRemaining();
        return new ItemForm<>(metadata.resolve(Root.class), fields, l.compile());
    }

    private static Unit unit(long id) { var value = new Unit(); value.setId(id); value.name = "Unit " + id; return value; }
    private static Source source(long id, Unit unit) { var value = new Source(); value.setId(id); value.name = "Source " + id; value.unit = unit; return value; }
    private static Parent parent(long id) { var value = new Parent(); value.setId(id); value.name = "Parent " + id; return value; }
    private static Choice choice(Parent owner) { var value = new Choice(); value.setId(50L); value.name = "Choice"; value.kind = "A"; value.owner = owner; return value; }
    private static void choose(EntityField<?> field, HasDisplayName value) throws Exception {
        var method = EntityField.class.getDeclaredMethod("onSelectedInDialog", HasDisplayName.class);
        method.setAccessible(true); method.invoke(field, value);
    }

    @Test void fillReplacesOnlyOnUserSelectionAndUserClear() throws Exception {
        var form = form(l -> l.of(l.field("source"), l.field("unit").setFillFrom("source.unit")));
        var preferred = unit(1); var manual = unit(2);
        var a = source(1, preferred); var b = source(2, preferred);
        var row = new Root(); row.source = a; row.unit = manual;
        form.setEntity(row);
        assertThat(form.<Unit>entityField("unit").getValue()).isSameAs(manual);
        form.<Source>entityField("source").setValue(b);
        assertThat(form.<Unit>entityField("unit").getValue()).isSameAs(manual);
        choose(form.entityField("source"), a);
        assertThat(form.<Unit>entityField("unit").getValue()).isSameAs(preferred);
        var text = (TextField) descendants(form.entityField("source")).stream()
            .filter(TextField.class::isInstance).findFirst().orElseThrow();
        text.setValue("");
        assertThat(form.entityField("unit").getValue()).isNull();
        assertThat(form.isDirty()).isTrue();
    }

    @Test void initialCopyAndSavedStateKeepTheStoredUnit() {
        var form = form(l -> l.of(l.field("source"), l.field("unit").setFillFrom("source.unit")));
        var manual = unit(2); var a = source(1, unit(1));
        var copy = new Root(); copy.source = a; copy.unit = manual;
        form.setEntityFactory(() -> copy); form.initializeNewEntity();
        assertThat(form.<Unit>entityField("unit").getValue()).isSameAs(manual);
        var saved = new Root(); saved.setId(3L); saved.source = source(2, unit(4)); saved.unit = manual;
        form.applySavedState(() -> form.applyPersistedEntity(saved));
        assertThat(form.<Unit>entityField("unit").getValue()).isSameAs(manual);
        assertThat(form.isDirty()).isFalse();
    }

    @Test void fillOnlyIfEmptyPreservesTheManualValue() throws Exception {
        var form = form(l -> l.of(l.field("source"), l.field("unit").setFillFrom("source.unit").setFillOnlyIfEmpty(true)));
        var manual = unit(2); var preferred = unit(1);
        form.<Unit>entityField("unit").setValue(manual);
        choose(form.entityField("source"), source(1, preferred));
        assertThat(form.<Unit>entityField("unit").getValue()).isSameAs(manual);
        form.entityField("unit").clear();
        choose(form.entityField("source"), source(2, preferred));
        assertThat(form.<Unit>entityField("unit").getValue()).isSameAs(preferred);
    }

    @Test void currentInputsDriveFiltersAndInvalidSelectionIsCleared() {
        var form = form(l -> l.of(l.field("parent"), l.field("choice")
            .setSelectionFilter("owner", l.value("parent")).setSelectionFilter("kind", "A")));
        var a = parent(1); var b = parent(2); var selected = choice(a);
        var row = new Root(); row.parent = a; row.choice = selected;
        form.setEntity(row);
        assertThat(form.entityField("choice").getSelectionFilters()).containsEntry("owner", a).containsEntry("kind", "A");
        form.<Parent>entityField("parent").setValue(parent(1));
        assertThat(form.<Choice>entityField("choice").getValue()).isSameAs(selected);
        form.<Parent>entityField("parent").setValue(b);
        assertThat(row.parent).isSameAs(a); // the filter reads the UI, not the unflushed model
        assertThat(form.entityField("choice").getSelectionFilters()).containsEntry("owner", b);
        assertThat(form.entityField("choice").getValue()).isNull();
        form.entityField("parent").clear();
        assertThat(form.entityField("choice").getSelectionFilters()).containsOnlyKeys("kind");
    }

    @Test void keepModeAndLoadingDoNotClearAStoredSelection() {
        var form = form(l -> l.of(l.field("parent"), l.field("choice")
            .setSelectionFilter("owner", l.value("parent")).setClearInvalidSelection(false)));
        var selected = choice(parent(1));
        var row = new Root(); row.parent = parent(2); row.choice = selected;
        form.setEntity(row);
        form.<Parent>entityField("parent").setValue(parent(3));
        assertThat(form.<Choice>entityField("choice").getValue()).isSameAs(selected);
    }

    @Test void loadingAndSavedStateDoNotClearEvenWithTheDefaultClearMode() {
        var form = form(l -> l.of(l.field("parent"), l.field("choice").setSelectionFilter("owner", l.value("parent"))));
        var selected = choice(parent(1));
        var row = new Root(); row.parent = parent(2); row.choice = selected;
        form.setEntity(row);
        assertThat(form.<Choice>entityField("choice").getValue()).isSameAs(selected);
        var saved = new Root(); saved.parent = parent(3); saved.choice = selected;
        form.applyPersistedEntity(saved);
        assertThat(form.<Choice>entityField("choice").getValue()).isSameAs(selected);
        assertThat(form.isDirty()).isFalse();
    }

    @Test void requiredSourceProducesNoChoicesAndClearsWhenTheSourceBecomesEmpty() {
        var form = form(l -> l.of(l.field("parent"), l.field("choice")
            .setSelectionFilter("owner", l.value("parent")).setAllowEmptySelectionSource(false)));
        assertThat(form.entityField("choice").getSelectionFilters()).containsEntry("owner", List.of());
        var a = parent(1);
        form.<Parent>entityField("parent").setValue(a);
        form.<Choice>entityField("choice").setValue(choice(a));
        form.entityField("parent").clear();
        assertThat(form.entityField("choice").getValue()).isNull();
    }

    @Test void selfIsTheCurrentEntityAndAnUnsavedSelfDoesNotConstrainSelection() {
        var form = form(l -> l.of(l.field("choice").setSelectionFilter("rootOwner", l.self())));
        var row = new Root(); form.setEntity(row);
        assertThat(form.entityField("choice").getSelectionFilters()).isEmpty();
        row.setId(8L);
        assertThat(form.entityField("choice").getSelectionFilters()).containsEntry("rootOwner", row);
    }

    @Test void sourcesDistinguishUserHandlerInitialAndSavedWithoutChangingLegacyEvents() throws Exception {
        var form = form(l -> l.of(l.field("source")));
        var changes = new ArrayList<ValueChangeSource>();
        var behavior = new ArrayList<ValueChangeSource>();
        var legacy = new ArrayList<Source>();
        form.addFieldChangeListener("source", event -> changes.add(event.source()));
        new FormBehavior(form).onChange("source", event -> behavior.add(event.source()));
        form.<Source>entityField("source").addValueChangeListener(legacy::add);
        var row = new Root(); row.source = source(1, null); form.setEntity(row);
        form.<Source>entityField("source").setValue(source(2, null));
        choose(form.entityField("source"), source(3, null));
        var saved = new Root(); saved.source = source(4, null); form.applyPersistedEntity(saved);
        assertThat(changes).containsExactly(ValueChangeSource.INITIAL_LOAD, ValueChangeSource.HANDLER,
            ValueChangeSource.USER, ValueChangeSource.SAVED_STATE);
        assertThat(behavior).containsExactly(ValueChangeSource.HANDLER, ValueChangeSource.USER);
        assertThat(legacy).hasSize(1);
    }

    @Test void scalarEditorsFillOnlyOnClientInput() {
        var form = form(l -> l.of(l.field("code"), l.field("copy").setFillFrom("code")));
        var code = (TextField) form.getField("code"); var copy = (TextField) form.getField("copy");
        code.setValue("server"); assertThat(copy.getValue()).isEmpty();
        code.setValue("client");
        ComponentUtil.fireEvent(code, new AbstractField.ComponentValueChangeEvent<>(code, code, "server", true));
        assertThat(copy.getValue()).isEqualTo("client");
    }

    @Test void longFillPreservesTheModelTypeAndEveryDigit() {
        var form = form(l -> l.of(l.field("large"), l.field("largeCopy").setFillFrom("large")));
        var input = (com.vaadin.flow.component.textfield.BigDecimalField) form.getField("large");
        input.setValue(java.math.BigDecimal.valueOf(Long.MAX_VALUE));
        ComponentUtil.fireEvent(input, new AbstractField.ComponentValueChangeEvent<>(input, input, null, true));
        assertThat(form.getEntity().largeCopy).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> form(l -> l.of(l.field("small"), l.field("largeCopy").setFillFrom("small"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fillAndFilterPathsAreValidatedAndAddedToSourceReads() {
        var form = form(l -> l.of(l.field("source"), l.field("unit").setFillFrom("source.unit")));
        assertThat(form.entityField("source").getLookupPaths()).containsExactly("unit");
        assertThat(form.getReadPaths()).contains("source", "source.unit");
        for (var invalid : List.<Function<Layout, ItemFormLayout>>of(
                l -> l.of(l.field("unit").setFillFrom("absent.unit")),
                l -> l.of(l.field("source"), l.field("unit").setFillFrom("source.absent")),
                l -> l.of(l.field("source"), l.field("unit").setFillFrom("source.name")),
                l -> l.of(l.field("parent"), l.field("choice").setSelectionFilter("absent", l.value("parent"))),
                l -> l.of(l.field("choice").setSelectionFilter("owner", l.self())),
                l -> l.of(l.field("choice").setSelectionFilter("owner", l.value("parent"))))) {
            assertThatThrownBy(() -> form(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static List<com.vaadin.flow.component.Component> descendants(com.vaadin.flow.component.Component root) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(root), root.getChildren()
            .flatMap(child -> descendants(child).stream())).toList();
    }
}

package org.ipro.form.builtin;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.IntegerField;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.LookupService;
import org.ipro.form.*;
import org.ipro.form.builder.*;
import org.ipro.form.builder.layout.*;
import org.ipro.metadata.*;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomEditorsTest {
    @Entity @EntityMetadata public static class Ref extends BaseEntity implements HasDisplayName {
        @FieldMetadata String name;
        public String getDisplayName() { return name; }
    }
    @Entity @EntityMetadata public static class Doc extends BaseEntity {
        @FieldMetadata(required = RequiredMode.REQUIRED, label = "Name", order = 1) String name;
        @ManyToOne @FieldMetadata(required = RequiredMode.REQUIRED, label = "Ref", order = 2) Ref ref;
    }
    private final MetadataResolver metadata = new MetadataResolver();
    private final FieldFactory fields = new FieldFactory(mock(LookupService.class), mock(SelectionFormAssembler.class));
    static final AtomicInteger created = new AtomicInteger();
    static class Form extends ItemForm<Doc> {
        int changes;
        final List<ValueChangeSource> sources = new ArrayList<>();
        @Override protected ItemFormLayout layout(Layout l) { return l.of(l.remainingFields()); }
        @Override protected void adjust(FormAdjust a) {
            a.element("name").setLabel("Custom name").setWidth("12em").setEditor(() -> { created.incrementAndGet(); return new TextField(); });
            a.element("ref").setEditor(() -> new EntityField<Ref>("", term -> List.of()));
        }
        @Override protected void behavior(FormBehavior b) {
            b.onChange("name", event -> { changes++; sources.add(event.source()); });
            b.onChange("ref", event -> { changes++; sources.add(event.source()); });
        }
    }
    private Form form() { var form = new Form(); form.buildDescription(metadata.resolve(Doc.class), fields); return form; }
    private static Ref ref() { var r = new Ref(); r.name = "Selected"; return r; }

    @Test void factoryIsLazyAndEditorsAreCreatedOncePerOpening() {
        created.set(0);
        var form = new Form(); var layout = form.describeLayout(metadata.resolve(Doc.class));
        form.validateDescriptionBehavior(layout); assertThat(created).hasValue(0);
        form.buildDescription(metadata.resolve(Doc.class), fields);
        var first = form.getField("name"); form.buildDescription(metadata.resolve(Doc.class), fields);
        assertThat(form.getField("name")).isSameAs(first); assertThat(created).hasValue(1);
        var second = form(); assertThat(second.getField("name")).isNotSameAs(first);
        assertThat(created).hasValue(2);
        assertThat(form.getBindingRegistry().getAll()).extracting(FormBinding::getFieldName).containsExactly("name", "ref");
        assertThat(first.getElement().getStyle().get("width")).isEqualTo("12em");
    }

    @Test void metadataBindingCoversRequiredDirtyReadOnlyAndSavedState() throws Exception {
        var form = form(); var value = new Doc(); value.name = "Stored"; value.ref = ref();
        form.setEntity(value);
        assertThat(form.isDirty()).isFalse(); assertThat(form.changes).isZero();
        assertThat(form.getEntityField("name", String.class).getValue()).isEqualTo("Stored");
        assertThat(form.getEntityField("ref", Ref.class).getValue()).isSameAs(value.ref);
        assertThat(((HasValue<?, ?>) form.getField("name")).isRequiredIndicatorVisible()).isTrue();
        assertThat(form.entityField("ref").isRequiredIndicatorVisible()).isTrue();
        form.getEntityField("name", String.class).clear();
        assertThat(form.getBindingRegistry().getBinding("name").orElseThrow().getDescriptor().required()).isTrue();
        assertThat(form.validate()).anyMatch(message -> message.startsWith("Custom name:"));
        assertThat(form.isDirty()).isTrue(); assertThat(form.changes).isEqualTo(1);
        TextField input = (TextField) form.getField("name");
        String old = input.getValue(); input.setValue("Input");
        ComponentUtil.fireEvent(input, new AbstractField.ComponentValueChangeEvent<>(input, input, old, true));
        assertThat(form.sources).contains(ValueChangeSource.USER, ValueChangeSource.HANDLER);
        int beforeReference = form.changes;
        form.<Ref>entityField("ref").setValue(ref());
        assertThat(form.changes).isEqualTo(beforeReference + 1); // EntityField is observed once despite also being HasValue.
        assertThat(form.getEntity().name).isEqualTo("Input");
        var saved = new Doc(); saved.name = "Server"; saved.ref = ref();
        int before = form.changes;
        form.applySavedState(() -> form.applyPersistedEntity(saved));
        assertThat(form.changes).isEqualTo(before); assertThat(form.isDirty()).isFalse();
        form.setReadOnly(true);
        assertThat(((HasValue<?, ?>) form.getField("name")).isReadOnly()).isTrue();
        assertThat(form.entityField("ref").isReadOnly()).isTrue();
        form.setReadOnly(false);
        assertThat(((HasValue<?, ?>) form.getField("name")).isReadOnly()).isFalse();
        assertThat(form.entityField("ref").isReadOnly()).isFalse();
    }

    @Test void declaredReadOnlyAndExistingExternalBindingsRemainSupported() {
        var layout = new Layout(metadata.resolve(Doc.class));
        layout.of(layout.field("name").setEditor(TextField::new).setReadOnly(true), layout.field("ref").setEditor(() -> new EntityField<Ref>("", term -> List.of())));
        var form = new ItemForm<Doc>(metadata.resolve(Doc.class), fields, layout.compile());
        form.setReadOnly(false);
        assertThat(((TextField) form.getField("name")).isReadOnly()).isTrue();
        var reference = new EntityField<Ref>("External", term -> List.of());
        var empty = new ItemForm<Doc>(Doc.class, List.of(), fields);
        empty.bindExternal(new BindingDescriptor("ref", "External", true), reference, doc -> doc.ref,
            (doc, value) -> doc.ref = value, reference::getValue, reference::setValue, Objects::isNull, reference::setReadOnly);
        var value = new Doc(); value.ref = ref(); empty.setEntity(value);
        assertThat(reference.getValue()).isSameAs(value.ref); assertThat(empty.isDirty()).isFalse();
        reference.clear(); assertThat(empty.isDirty()).isTrue(); assertThat(empty.validate()).contains("External: обязательно для заполнения");
    }

    @Test void customEditorsParticipateInRelatedFieldFills() throws Exception {
        var l = new Layout(metadata.resolve(Doc.class));
        l.of(l.field("name").setEditor(TextField::new).setFillFrom("ref.name"),
            l.field("ref").setEditor(() -> new EntityField<Ref>("", term -> List.of())));
        var form = new ItemForm<Doc>(metadata.resolve(Doc.class), fields, l.compile());
        var value = new Doc(); value.name = "Stored"; value.ref = ref(); form.setEntity(value);
        assertThat(form.getEntityField("name", String.class).getValue()).isEqualTo("Stored");
        var selected = ref(); selected.name = "User choice";
        var choose = EntityField.class.getDeclaredMethod("onSelectedInDialog", HasDisplayName.class);
        choose.setAccessible(true); choose.invoke(form.entityField("ref"), selected);
        assertThat(form.getEntityField("name", String.class).getValue()).isEqualTo("User choice");
        assertThat(form.isDirty()).isTrue();
    }

    @Test void invalidPlacementsFailWithoutInvokingTheFactory() {
        var l = new Layout(metadata.resolve(Doc.class));
        l.of(l.display("name").setEditor(() -> { throw new AssertionError("Must stay lazy"); }));
        assertThatThrownBy(l::compile).hasMessageContaining("direct field");
        var another = new Layout(metadata.resolve(Doc.class));
        another.of(another.field("ref").setEditor(() -> new EntityField<Ref>("", term -> List.of())).setSelectionVariant("custom"));
        assertThatThrownBy(another::compile).hasMessageContaining("owns its selection");
    }

    @Test void metadataReadOnlySurvivesFormUnlockAndLabelOverride() {
        var info = spy(metadata.resolve(Doc.class).getFieldByName("name"));
        when(info.isReadOnly()).thenReturn(true);
        var input = new TextField(); var registry = new FormBindingRegistry();
        fields.bindEditor(info, input, registry);
        registry.replace(registry.getBinding("name").orElseThrow().withLabel("Alias"));
        registry.setReadOnly(true); registry.setReadOnly(false);
        assertThat(input.isReadOnly()).isTrue();
    }

    @Test void incompatibleTypesNullFactoriesAndSharedInstancesFailClearly() {
        var info = metadata.resolve(Doc.class).getFieldByName("name");
        assertThatThrownBy(() -> fields.bindEditor(info, new IntegerField(), new FormBindingRegistry()))
            .hasMessageContaining("value type");
        var l = new Layout(metadata.resolve(Doc.class));
        l.of(l.field("name").setEditor(() -> (TextField) null));
        assertThatThrownBy(() -> new ItemForm<Doc>(metadata.resolve(Doc.class), fields, l.compile()))
            .hasMessageContaining("returned null");
        var shared = new TextField();
        var one = new Layout(metadata.resolve(Doc.class)); one.of(one.field("name").setEditor(() -> shared));
        new ItemForm<Doc>(metadata.resolve(Doc.class), fields, one.compile());
        var two = new Layout(metadata.resolve(Doc.class)); two.of(two.field("name").setEditor(() -> shared));
        assertThatThrownBy(() -> new ItemForm<Doc>(metadata.resolve(Doc.class), fields, two.compile()))
            .hasMessageContaining("fresh editor");
        var refInfo = metadata.resolve(Doc.class).getFieldByName("ref");
        EntityField raw = new EntityField("Wrong entity", term -> List.of());
        fields.bindEditor(refInfo, raw, new FormBindingRegistry());
        raw.setValue(new org.ipro.metadata.HasDisplayName() { public String getDisplayName() { return "Wrong"; } });
        var registry = new FormBindingRegistry();
        assertThatThrownBy(() -> fields.bindEditor(refInfo, raw, registry)).hasMessageContaining("must be");
    }
}

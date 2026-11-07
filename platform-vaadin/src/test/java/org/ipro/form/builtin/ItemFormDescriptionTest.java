package org.ipro.form.builtin;

import com.vaadin.flow.component.html.Span;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.LookupService;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.builder.*;
import org.ipro.form.builder.layout.*;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemFormDescriptionTest {
    @EntityMetadata(itemFormTitle="Doc", listFormTitle="Docs")
    static class Doc extends BaseEntity {
        @FieldMetadata(label="Code", order=1) String code;
        @FieldMetadata(label="Name", order=2, required=RequiredMode.REQUIRED) String name;
    }
    private final MetadataResolver metadata = new MetadataResolver();
    private final FieldFactory fields = new FieldFactory(mock(LookupService.class), mock(SelectionFormAssembler.class));
    static final AtomicInteger customCreated = new AtomicInteger();

    @FormFor(Doc.class)
    static class Base extends ItemForm<Doc> {
        @Override protected ItemFormLayout layout(Layout l) {
            return l.of(l.field("name"), l.custom("hint", () -> { customCreated.incrementAndGet(); return new Span(); }), l.remainingFields());
        }
        @Override protected void adjust(FormAdjust a) { a.element("code").setReadOnly(true).setWidth("9em"); }
    }
    @FormFor(value=Doc.class, variant="short")
    static class Short extends Base {
        @Override protected void adjust(FormAdjust a) { super.adjust(a); a.element("name").setLabel("Short name").setVisible(false); }
    }
    @FormFor(value=Doc.class, variant="too-deep") static class TooDeep extends Short { }
    @FormFor(Doc.class) static class Typo extends ItemForm<Doc> {
        @Override protected ItemFormLayout layout(Layout l) { return l.of(l.field("misspelled")); }
    }
    @FormFor(Doc.class) static class BadReference extends ItemForm<Doc> {
        @Override protected void adjust(FormAdjust a) { a.element("absent").remove(); }
    }
    @FormFor(Doc.class) static class Duplicate extends ItemForm<Doc> {
        @Override protected ItemFormLayout layout(Layout l) { return l.of(l.field("name"), l.field("name")); }
    }
    @FormFor(Doc.class) static class Cycle extends ItemForm<Doc> {
        @Override protected ItemFormLayout layout(Layout l) {
            return l.of(l.panel("outer").add(l.panel("inner").add(l.remainingFields())));
        }
        @Override protected void adjust(FormAdjust a) { a.element("outer").placeInto("inner"); }
    }
    @FormFor(Doc.class) static class Removed extends ItemForm<Doc> {
        @Override protected void adjust(FormAdjust a) { a.element("name").remove(); }
    }
    @FormFor(Doc.class) static class BadBehavior extends ItemForm<Doc> {
        @Override protected void behavior(FormBehavior b) { b.onChange("missing", event -> { }); }
    }
    static class Events extends ItemForm<Doc> {
        int changes, loads;
        boolean returnedRowsApplied;
        @Override protected void behavior(FormBehavior b) {
            b.onChange("name", event -> changes++);
            b.onLoaded(() -> { if (peekEntity().getId() != null) assertThat(returnedRowsApplied).isTrue(); loads++; });
        }
    }
    static class Omitted extends ItemForm<Doc> {
        @Override protected ItemFormLayout layout(Layout l) { return l.of(l.field("code")); }
    }

    private ItemFormCustomizationRegistrar registrar(FormRegistry registry, Class<?>... types) {
        var registrar = new ItemFormCustomizationRegistrar(registry, List.of());
        registrar.setAnnotatedForms(List.of(types), metadata, new DefaultListableBeanFactory());
        return registrar;
    }
    @Test void startupChecksDescriptionsWithoutCreatingEditorsOrCustomComponents() {
        customCreated.set(0);
        var registrar = registrar(new FormRegistry(), Base.class, Short.class);
        registrar.afterPropertiesSet(); registrar.afterSingletonsInstantiated();
        assertThat(customCreated).hasValue(0);
        var shell = new Base();
        shell.describeLayout(metadata.resolve(Doc.class));
        assertThat(shell.getChildren()).isEmpty();
        assertThat(shell.getBindingRegistry().size()).isZero();
    }
    @Test void adjustsExpandedRemainingFieldsBeforeRenderingAndBuildsOnlyOnce() {
        customCreated.set(0);
        var form = new Short();
        assertThat(form.isDescriptionBuilt()).isFalse();
        form.buildDescription(metadata.resolve(Doc.class), fields);
        assertThat(form.getBindingRegistry().getAll()).extracting(binding -> binding.getFieldName()).containsExactly("name", "code");
        assertThat(form.getField("name").isVisible()).isFalse();
        assertThat(form.getBindingRegistry().getBinding("name").orElseThrow().getDescriptor().label()).isEqualTo("Short name");
        var code = form.getBindingRegistry().getBinding("code").orElseThrow();
        assertThat(code.isReadOnly()).isTrue();
        form.setReadOnly(true); form.setReadOnly(false);
        assertThat(code.isReadOnly()).isTrue();
        assertThat(form.getField("code").getElement().getStyle().get("width")).isEqualTo("9em");
        var first = form.getField("code");
        form.buildDescription(metadata.resolve(Doc.class), fields);
        assertThat(form.getField("code")).isSameAs(first);
        assertThat(customCreated).hasValue(1);
        var second = new Short(); second.buildDescription(metadata.resolve(Doc.class), fields);
        assertThat(second.getField("code")).isNotSameAs(first);
        assertThat(customCreated).hasValue(2);
    }
    @Test void invalidDescriptionsFailStartupWithFormAndElement() {
        for (var type : List.of(Typo.class, BadReference.class, Duplicate.class, Cycle.class, BadBehavior.class)) {
            var registrar = registrar(new FormRegistry(), type); registrar.afterPropertiesSet();
            assertThatThrownBy(registrar::afterSingletonsInstantiated).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(type.getName());
        }
    }
    @Test void duplicateRegistrationAndDeepInheritanceFailBeforeOpening() {
        var registry = new FormRegistry();
        registry.registerItemForm(Doc.class, (String)null, context -> new Base(), "legacy");
        assertThatThrownBy(() -> registrar(registry, Base.class).afterPropertiesSet())
            .hasMessageContaining("Дубликат");
        assertThatThrownBy(() -> registrar(new FormRegistry(), TooDeep.class).afterPropertiesSet())
            .hasMessageContaining("two levels");
    }
    @Test void explicitlyRemovedFieldIsNotRestoredByRemainingFields() {
        var form = new Removed(); form.buildDescription(metadata.resolve(Doc.class), fields);
        assertThat(form.getBindingRegistry().getAll()).extracting(binding -> binding.getFieldName()).containsExactly("code");
    }
    @Test void loadedRunsAfterTheWholeSavedSnapshotAndValueHandlersIgnorePopulation() {
        var form = new Events(); form.buildDescription(metadata.resolve(Doc.class), fields);
        form.initializeNewEntity(java.util.Map.of("name", "Initial"));
        assertThat(form.loads).isEqualTo(1); assertThat(form.changes).isZero();
        var saved = new Doc(); saved.setId(1L); saved.name = "Saved";
        form.applySavedState(() -> {
            form.applyPersistedEntity(saved);
            form.returnedRowsApplied = true;
            form.commitSnapshot();
        });
        assertThat(form.loads).isEqualTo(2); assertThat(form.changes).isZero();
        form.getEntityField("name", String.class).setValue("Input");
        assertThat(form.changes).isEqualTo(1);
        assertThatThrownBy(() -> form.applySavedState(() -> { throw new IllegalStateException("failed"); }))
            .hasMessage("failed");
        assertThat(form.isLoadingValues()).isFalse(); assertThat(form.loads).isEqualTo(2);
    }
    @Test void requiredOmissionWarnsWithFormAndFieldButExplicitRemovalAcknowledgesIt() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(Layout.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            new Omitted().describeLayout(metadata.resolve(Doc.class));
            assertThat(appender.list).anyMatch(event -> event.getFormattedMessage().contains(Omitted.class.getName())
                && event.getFormattedMessage().contains("name"));
            appender.list.clear(); new Removed().describeLayout(metadata.resolve(Doc.class));
            assertThat(appender.list).isEmpty();
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}

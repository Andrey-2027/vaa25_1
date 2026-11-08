package org.ipro.form.builtin;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.form.builder.FormFor;
import org.ipro.form.builder.ItemFormCustomizationRegistrar;
import org.ipro.form.builder.ListCommandLayout;
import org.ipro.form.builder.layout.*;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.CrudAction;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class EmbeddedListDescriptionTest {
    @Entity @EntityMetadata
    public static class Parent extends StandardCatalogEntity { }
    @Entity @EntityMetadata
    public static class Document extends BaseEntity {
        @ManyToOne @FieldMetadata Parent receiving;
        @FieldMetadata String number;
        @FieldMetadata String journal;
        public Parent getReceiving() { return receiving; }
        public String getNumber() { return number; }
        public String getJournal() { return journal; }
    }
    @Entity @EntityMetadata @CatalogOwner(property="parent")
    public static class Owned extends StandardCatalogEntity {
        @NotNull @ManyToOne(optional=false) @JoinColumn(nullable=false)
        @FieldMetadata(required=RequiredMode.REQUIRED) Parent parent;
        public Parent getParent() { return parent; }
    }
    public static class NoMetadata extends BaseEntity { }
    private final MetadataResolver metadata = new MetadataResolver();

    @FormFor(Parent.class)
    public static class WithList extends ItemForm<Parent> {
        @Override protected ItemFormLayout layout(Layout l) {
            return l.of(l.tabs("tabs").add(l.tab("main").add(l.remainingFields()),
                l.tab("documents").add(l.list("incoming", Document.class).setVariant("incoming")
                    .setFilter("receiving", l.self()).setReadOnly(true)
                    .commands(c -> c.exclude(CrudAction.DELETE.id())))));
        }
    }
    @FormFor(Parent.class)
    public static class BadCommand extends ItemForm<Parent> {
        @Override protected ItemFormLayout layout(Layout l) {
            return l.of(l.remainingFields(), l.list("unknown-command", Document.class)
                .commands(c -> c.commands(ActionId.of("absent.action"))));
        }
    }
    @FormFor(Parent.class)
    public static class DefaultList extends ItemForm<Parent> {
        @Override protected ItemFormLayout layout(Layout l) {
            return l.of(l.remainingFields(), l.list("default-list", Document.class));
        }
    }

    @Test void descriptionKeepsImmutableListConfigurationWithoutConstructingComponents() {
        var form = new WithList();
        var layout = form.describeLayout(metadata.resolve(Parent.class));
        var sheet = (TabSheetNode) ((ElementNode) layout.nodes().getFirst()).content();
        var wrapper = (ElementNode) sheet.tabs().get(1).children().getFirst();
        var list = (EmbeddedListNode) wrapper.content();
        assertThat(wrapper.id()).isEqualTo("incoming");
        assertThat(wrapper.readOnly()).isTrue();
        assertThat(list.variant()).isEqualTo("incoming");
        assertThat(list.filters()).containsEntry("receiving", new SelectionValue.Self());
        assertThat(list.commands().override(CrudAction.DELETE.id()).included()).isFalse();
        assertThatThrownBy(() -> list.filters().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(form.getChildren()).isEmpty();
        assertThat(form.getBindingRegistry().size()).isZero();
        assertThat(FormReadDependencies.readPaths(Parent.class, layout)).isEmpty();
    }

    @Test void startupAcceptsColumnOnlyVariantWithoutCallingItsFactoryOrReadingData() {
        var calls = new AtomicInteger();
        var registry = new FormRegistry();
        registry.registerListColumns(Document.class, "incoming", List.of("number"), "columns");
        registry.registerListForm(Document.class, "unused", context -> { calls.incrementAndGet(); throw new AssertionError(); });
        var registrar = registrar(registry, WithList.class);
        registrar.afterPropertiesSet(); registrar.afterSingletonsInstantiated();
        assertThat(calls).hasValue(0);
        var factoryRegistry = new FormRegistry();
        factoryRegistry.registerListForm(Document.class, "incoming", context -> {
            calls.incrementAndGet(); throw new AssertionError("Startup must never call the selected factory");
        });
        var factoryRegistrar = registrar(factoryRegistry, WithList.class);
        factoryRegistrar.afterPropertiesSet(); factoryRegistrar.afterSingletonsInstantiated();
        assertThat(calls).hasValue(0);
    }

    @Test void startupRejectsUnknownVariantWithFormAndElementAddress() {
        var registrar = registrar(new FormRegistry(), WithList.class); registrar.afterPropertiesSet();
        assertThatThrownBy(registrar::afterSingletonsInstantiated)
            .hasMessageContaining(WithList.class.getName()).hasMessageContaining("incoming")
            .hasMessageContaining("Unknown embedded list variant");
    }

    @Test void startupRejectsUnknownCommandWithFormAndElementAddress() {
        var registrar = registrar(new FormRegistry(), BadCommand.class); registrar.afterPropertiesSet();
        assertThatThrownBy(registrar::afterSingletonsInstantiated)
            .hasMessageContaining(BadCommand.class.getName()).hasMessageContaining("unknown-command")
            .hasMessageContaining("absent.action");
    }

    @Test void startupRejectsWholeViewEvenForDefaultList() {
        var registry = new FormRegistry();
        registry.registerListFormView(Document.class, null, com.vaadin.flow.component.html.Div.class);
        var registrar = registrar(registry, DefaultList.class); registrar.afterPropertiesSet();
        assertThatThrownBy(registrar::afterSingletonsInstantiated)
            .hasMessageContaining("default-list").hasMessageContaining("not a View");
    }

    @Test void declaredCommandsAreValidatedForTheTargetEntityAndSurfaceWithoutHandlers() {
        var command = org.ipro.form.action.ActionDefinition.forEntity(ActionId.of("absent.action"),
            org.ipro.form.action.ActionSurface.LIST_TOOLBAR, Document.class, "Declared", null, 1,
            org.ipro.form.action.ActionRequirement.none());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("actions", new org.ipro.form.action.ActionRegistry(List.of(command), List.of()));
        var registrar = new ItemFormCustomizationRegistrar(new FormRegistry(), List.of());
        registrar.setAnnotatedForms(List.of(BadCommand.class), metadata, beans);
        registrar.afterPropertiesSet(); registrar.afterSingletonsInstantiated();
        var wrongSurface = new DefaultListableBeanFactory();
        wrongSurface.registerSingleton("actions", new org.ipro.form.action.ActionRegistry(List.of(
            org.ipro.form.action.ActionDefinition.forEntity(ActionId.of("absent.action"),
                org.ipro.form.action.ActionSurface.ITEM_FOOTER, Document.class, "Wrong surface", null, 1,
                org.ipro.form.action.ActionRequirement.none())), List.of()));
        var invalid = new ItemFormCustomizationRegistrar(new FormRegistry(), List.of());
        invalid.setAnnotatedForms(List.of(BadCommand.class), metadata, wrongSurface);
        invalid.afterPropertiesSet();
        assertThatThrownBy(invalid::afterSingletonsInstantiated).hasMessageContaining("absent.action");
    }

    @Test void adjustCanAddAndConfigureAnIndependentList() {
        var l = new Layout(metadata.resolve(Parent.class)); l.of(l.remainingFields()); l.expandRemaining();
        new FormAdjust(l).addList("incoming", Document.class).setFilter("receiving", l.self()).setReadOnly(true);
        var result = l.compile();
        assertThat(result.nodes().getLast()).isInstanceOfSatisfying(ElementNode.class, element -> {
            assertThat(element.id()).isEqualTo("incoming");
            assertThat(element.readOnly()).isTrue();
            assertThat(((EmbeddedListNode) element.content()).filtersFor(Parent.class))
                .containsEntry("receiving", new SelectionValue.Self());
        });
    }

    @Test void catalogOwnerIsDerivedAndCannotPointAtAnotherSource() {
        var node = new EmbeddedListNode(Owned.class, null, Map.of(), ListCommandLayout.builder().build());
        assertThat(node.filtersFor(Parent.class)).containsExactlyEntriesOf(Map.of("parent", new SelectionValue.Self()));
        assertThatThrownBy(() -> node.filtersFor(Document.class)).hasMessageContaining("parent");
        var conflicting = new EmbeddedListNode(Owned.class, null,
            Map.of("parent", new SelectionValue.Constant(new Parent())), ListCommandLayout.builder().build());
        assertThatThrownBy(() -> conflicting.filtersFor(Parent.class)).hasMessageContaining("must refer to self");
    }

    @Test void checksTargetPathsAndSelfTypesDuringCompilation() {
        assertThatThrownBy(() -> compile(l -> l.list("bad-path", Document.class).setFilter("typo", l.self())))
            .hasMessageContaining("bad-path").hasMessageContaining("typo");
        assertThatThrownBy(() -> compile(l -> l.list("bad-type", Document.class).setFilter("number", l.self())))
            .hasMessageContaining("bad-type").hasMessageContaining("expects java.lang.String");
        assertThatThrownBy(() -> compile(l -> l.list("null-filter", Document.class).setFilter("number", null)))
            .hasMessageContaining("null-filter").hasMessageContaining("must not be null");
        assertThatThrownBy(() -> compile(l -> l.list(NoMetadata.class))).hasMessageContaining("concrete metadata entity");
    }

    @Test void explicitIdsAllowMultipleListsAndDefaultIdsRejectDuplicates() {
        var l = new Layout(metadata.resolve(Parent.class));
        l.of(l.list("first", Document.class), l.list("second", Document.class)); l.expandRemaining();
        assertThat(l.compile().nodes()).hasSize(2);
        var duplicate = new Layout(metadata.resolve(Parent.class));
        duplicate.of(duplicate.list(Document.class), duplicate.list(Document.class)); duplicate.expandRemaining();
        assertThatThrownBy(duplicate::compile).hasMessageContaining("Duplicate element id");
    }

    @Test void fieldValueFiltersRequireBoundParentInputAndDoNotReadChildColumns() {
        var l = new Layout(metadata.resolve(Parent.class));
        l.of(l.remainingFields(), l.list(Document.class).setFilter("number", l.value("code"))); l.expandRemaining();
        assertThat(FormReadDependencies.readPaths(Parent.class, l.compile())).isEmpty();
        assertThatThrownBy(() -> compile(d -> d.list(Document.class).setFilter("number", d.value("code"))))
            .hasMessageContaining("not bound");
    }

    @Test void snapshotDoesNotChangeWhenMutableDeclarationIsEditedOrRemoved() {
        var filters = new LinkedHashMap<String, SelectionValue>(); filters.put("number", new SelectionValue.Constant("A"));
        var node = new EmbeddedListNode(Document.class, null, filters, ListCommandLayout.builder().build());
        filters.clear(); assertThat(node.filters()).hasSize(1);
        var l = new Layout(metadata.resolve(Parent.class));
        l.of(l.remainingFields(), l.list("removed", Document.class).setFilter("receiving", l.self())); l.expandRemaining();
        l.element("removed").remove();
        assertThat(l.compile().nodes()).noneMatch(n -> n instanceof ElementNode e && e.content() instanceof EmbeddedListNode);
    }

    @Test void listOnlyPropertiesAreNotSilentlyAcceptedOnFields() {
        var l = new Layout(metadata.resolve(Parent.class));
        assertThatThrownBy(() -> l.field("code").setVariant("incoming")).hasMessageContaining("requires a list");
        assertThatThrownBy(() -> l.field("code").setFilter("number", "A")).hasMessageContaining("requires a list");
        assertThatThrownBy(() -> compile(d -> d.list(Document.class).setSelectionVariant("wrong")))
            .hasMessageContaining("Use setVariant");
    }

    @Test void descriptionAndAssemblyDoNotCreateListsWithoutAnAttachedVisibleSavedParent() {
        var form = new WithList();
        var factory = org.mockito.Mockito.mock(org.ipro.form.registry.EmbeddedListFactory.class);
        org.mockito.Mockito.when(factory.canRead(Document.class)).thenReturn(true);
        form.setEmbeddedListFactory(factory);
        var fields = new org.ipro.form.FieldFactory(org.mockito.Mockito.mock(org.ipro.crud.LookupService.class),
            org.mockito.Mockito.mock(org.ipro.form.SelectionFormAssembler.class));
        form.buildDescription(metadata.resolve(Parent.class), fields);
        org.mockito.Mockito.verify(factory).canRead(Document.class);
        org.mockito.Mockito.verifyNoMoreInteractions(factory);
    }

    private ItemFormLayout compile(java.util.function.Function<Layout, Layout.Element> declaration) {
        var layout = new Layout(metadata.resolve(Parent.class));
        layout.of(declaration.apply(layout)); layout.expandRemaining(); return layout.compile();
    }
    private ItemFormCustomizationRegistrar registrar(FormRegistry registry, Class<?> type) {
        var registrar = new ItemFormCustomizationRegistrar(registry, List.of());
        registrar.setAnnotatedForms(List.of(type), metadata, new DefaultListableBeanFactory()); return registrar;
    }
}

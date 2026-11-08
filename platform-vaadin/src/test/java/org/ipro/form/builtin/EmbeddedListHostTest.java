package org.ipro.form.builtin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.tabs.TabSheet;
import jakarta.persistence.ManyToOne;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.LookupService;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.builder.layout.*;
import org.ipro.form.registry.EmbeddedListFactory;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddedListHostTest {
    @EntityMetadata public static class Parent extends BaseEntity {
        @FieldMetadata String name;
        public String getName() { return name; }
        public void setName(String value) { name = value; }
    }
    @EntityMetadata public static class Child extends BaseEntity {
        @ManyToOne @FieldMetadata Parent parent;
        @FieldMetadata String number;
        public Parent getParent() { return parent; }
        public String getNumber() { return number; }
    }
    static class Card extends ItemForm<Parent> {
        @Override protected ItemFormLayout layout(Layout l) {
            return l.of(l.tabs("tabs").add(l.tab("main").add(l.remainingFields()),
                l.tab("children").add(l.list("incoming", Child.class).setFilter("parent", l.self()))));
        }
    }
    private final MetadataResolver metadata = new MetadataResolver();
    private final FieldFactory fields = new FieldFactory(mock(LookupService.class), mock(SelectionFormAssembler.class));
    private final List<Map<String,Object>> openings = new ArrayList<>();
    private final List<ListForm<Child,Long>> lists = new ArrayList<>();
    private boolean allowed = true;
    private int reads;
    private UI ui;
    private final EmbeddedListFactory factory = new EmbeddedListFactory() {
        @Override public boolean canRead(Class<?> type) { return allowed; }
        @Override public ListForm<?,?> create(Class<? extends IdentifiableEntity<Long>> type, String selectedVariant,
                                              Map<String,Object> filters) {
            openings.add(filters);
            var grid = new JpaFilterGrid<Child>(Child.class, (spec, page) -> { reads++; return Page.empty(page); });
            var list = new ListForm<Child,Long>(metadata.resolve(Child.class), grid);
            list.setOpeningContextFilters(filters);
            lists.add(list);
            return list;
        }
    };
    @BeforeEach void attachUi() {
        ui = new UI(); UI.setCurrent(ui);
        var session = mock(com.vaadin.flow.server.VaadinSession.class, RETURNS_DEEP_STUBS);
        when(session.getErrorHandler()).thenReturn(null);
        ui.getInternals().setSession(session);
    }
    @AfterEach void clearUi() { UI.setCurrent(null); }
    private <F extends ItemForm<Parent>> F build(F form) {
        form.setEmbeddedListFactory(factory); form.buildDescription(metadata.resolve(Parent.class), fields); return form;
    }
    private Card card() { return build(new Card()); }
    private Parent parent(long id) { var value = new Parent(); value.setId(id); value.name="Parent " + id; return value; }
    private TabSheet tabs(ItemForm<?> form) { return tree(form).filter(TabSheet.class::isInstance).map(TabSheet.class::cast).findFirst().orElseThrow(); }
    private Stream<Component> tree(Component component) {
        if (component instanceof TabSheet sheet) return Stream.concat(Stream.of(component),
            java.util.stream.IntStream.range(0,sheet.getTabCount())
                .mapToObj(index -> sheet.getComponent(sheet.getTabAt(index))).flatMap(this::tree));
        return Stream.concat(Stream.of(component), component.getChildren().flatMap(this::tree));
    }

    @Test void savedParentWaitsForAttachmentAndFirstTabVisitAndReusesTheList() {
        var form = card(); form.setEntity(parent(1));
        assertThat(openings).isEmpty(); assertThat(reads).isZero();
        ui.add(form);
        assertThat(openings).isEmpty(); assertThat(reads).isZero();
        tabs(form).setSelectedIndex(1);
        assertThat(openings).hasSize(1); assertThat(openings.getFirst().get("parent")).isSameAs(form.peekEntity());
        tabs(form).setSelectedIndex(0); tabs(form).setSelectedIndex(1);
        assertThat(openings).hasSize(1);
        assertThat(form.isDirty()).isFalse();
    }

    @Test void newParentShowsMessageAndFirstAuthoritativeSaveCreatesExactlyOneList() {
        var form=card(); form.initializeNewEntity(Map.of("name","New")); ui.add(form); tabs(form).setSelectedIndex(1);
        assertThat(openings).isEmpty(); assertThat(reads).isZero();
        assertThat(tree(form).filter(Span.class::isInstance).map(Span.class::cast).map(Span::getText))
            .anyMatch(text -> text.contains("Запишите объект"));
        var saved=parent(2);
        form.applySavedState(() -> { form.applyPersistedEntity(saved); form.commitSnapshot(); });
        assertThat(openings).hasSize(1); assertThat(openings.getFirst()).containsEntry("parent",saved);
        assertThat(form.isDirty()).isFalse();
        form.applySavedState(() -> form.applyPersistedEntity(saved));
        assertThat(openings).hasSize(1);
    }

    @Test void firstSaveWhileTabIsHiddenDoesNotCreateUntilItIsVisited() {
        var form=card(); form.initializeNewEntity(); ui.add(form);
        form.applyPersistedEntity(parent(3)); assertThat(openings).isEmpty();
        tabs(form).setSelectedIndex(1); assertThat(openings).hasSize(1);
    }

    @Test void changingParentDiscardsPreviousListAndKeepsOpeningsIndependent() {
        var form=card(); form.setEntity(parent(1)); ui.add(form); tabs(form).setSelectedIndex(1);
        var first=lists.getFirst();
        form.setEntity(parent(2));
        assertThat(openings).hasSize(2); assertThat(first.getParent()).isEmpty();
        assertThat(((Parent)openings.get(1).get("parent")).getId()).isEqualTo(2L);
        form.setEntity(null); assertThat(openings).hasSize(2); assertThat(lists.getLast().getParent()).isEmpty();
        var other=card(); other.setEntity(parent(4)); ui.add(other); tabs(other).setSelectedIndex(1);
        assertThat(openings).hasSize(3); assertThat(form.isDirty()).isFalse();
    }

    @Test void deniedReadHidesElementAndItsOtherwiseEmptyTabWithoutCreatingOrReading() {
        allowed=false; var form=card(); form.setEntity(parent(1)); ui.add(form);
        assertThat(tabs(form).getTabAt(1).isVisible()).isFalse();
        assertThat(openings).isEmpty(); assertThat(reads).isZero();
        assertThat(tree(form).filter(EmbeddedListHost.class::isInstance)).allMatch(host -> !host.isVisible());
    }

    @Test void denialDoesNotHideOtherContentOnTheSameTab() {
        allowed=false;
        var form=build(new ItemForm<Parent>() {
            @Override protected ItemFormLayout layout(Layout l) {
                return l.of(l.tabs("tabs").add(l.tab("mixed").add(l.remainingFields(),
                    l.list(Child.class).setFilter("parent", l.self()))));
            }
        });
        form.setEntity(parent(1)); ui.add(form);
        assertThat(tabs(form).getTabAt(0).isVisible()).isTrue(); assertThat(openings).isEmpty();
    }

    @Test void permissionIsCheckedAgainAtActivationAndParentReload() {
        var form=card(); form.setEntity(parent(1)); ui.add(form);
        allowed=false; tabs(form).setSelectedIndex(1);
        assertThat(openings).isEmpty(); assertThat(tabs(form).getTabAt(1).isVisible()).isFalse();
        allowed=true; form.setEntity(parent(2)); tabs(form).setSelectedIndex(1); assertThat(openings).hasSize(1);
        allowed=false; form.setEntity(parent(2));
        assertThat(lists.getFirst().getParent()).isEmpty(); assertThat(tabs(form).getTabAt(1).isVisible()).isFalse();
    }

    @Test void standaloneListIsAlsoLazyUntilTheCardIsShown() {
        var form=build(new ItemForm<Parent>() {
            @Override protected ItemFormLayout layout(Layout l) { return l.of(l.remainingFields(), l.list(Child.class).setFilter("parent", l.self()).setReadOnly(true)); }
        });
        form.setEntity(parent(1)); assertThat(openings).isEmpty();
        ui.add(form); assertThat(openings).hasSize(1); assertThat(lists.getFirst().isReadOnly()).isTrue();
    }

    @Test void nestedTabsDoNotActivateListsOutsideTheSelectedPath() {
        var form=build(new ItemForm<Parent>() {
            @Override protected ItemFormLayout layout(Layout l) {
                return l.of(l.tabs("outer").add(l.tab("main").add(l.remainingFields()),
                    l.tab("nested").add(l.tabs("inner").add(l.tab("intro").add(l.custom("hint",Span::new)),
                        l.tab("list").add(l.list(Child.class).setFilter("parent",l.self()))))));
            }
        });
        form.setEntity(parent(1)); ui.add(form);
        var outer=tree(form).filter(TabSheet.class::isInstance).map(TabSheet.class::cast).filter(t -> "outer".equals(t.getId().orElse(null))).findFirst().orElseThrow();
        var inner=tree(form).filter(TabSheet.class::isInstance).map(TabSheet.class::cast).filter(t -> "inner".equals(t.getId().orElse(null))).findFirst().orElseThrow();
        inner.setSelectedIndex(1); assertThat(openings).isEmpty();
        outer.setSelectedIndex(1); assertThat(openings).hasSize(1);
    }

    @Test void declaredHiddenTabSheetIsNotRevealedByPopulationOrSave() {
        var form=build(new ItemForm<Parent>() {
            @Override protected ItemFormLayout layout(Layout l) {
                return l.of(l.remainingFields(), l.tabs("hidden-tabs").setVisible(false).add(
                    l.tab("hidden-list").add(l.list(Child.class).setFilter("parent",l.self()))));
            }
        });
        form.initializeNewEntity(); ui.add(form); form.applyPersistedEntity(parent(5));
        assertThat(tabs(form).isVisible()).isFalse(); assertThat(openings).isEmpty();
    }

    @Test void detachedCardDoesNotCreateForTheNewParentUntilAttachedAgain() {
        var form=card(); form.setEntity(parent(1)); ui.add(form); tabs(form).setSelectedIndex(1);
        ui.remove(form); form.setEntity(parent(2)); assertThat(openings).hasSize(1);
        ui.add(form); assertThat(openings).hasSize(2);
    }

    @Test void commandLayerAppliesOnlyToThisEmbeddedInstance() {
        var form=build(new ItemForm<Parent>() {
            @Override protected ItemFormLayout layout(Layout l) {
                return l.of(l.remainingFields(),l.list(Child.class).setFilter("parent",l.self())
                    .commands(c -> {
                        c.exclude(org.ipro.form.action.CrudAction.DELETE.id());
                        c.command(org.ipro.form.action.CrudAction.REFRESH.id()).title("Перечитать");
                    }));
            }
        });
        form.setEntity(parent(1));
        ui.add(form);
        assertThat(openings).hasSize(1);
        var bar=org.ipro.form.CommandBar.forForm(lists.getFirst());
        assertThat(bar.command(org.ipro.form.action.CrudAction.REFRESH.id()).menuItem().getText()).isEqualTo("Перечитать");
        assertThat(bar.layout().override(org.ipro.form.action.CrudAction.DELETE.id()).included()).isFalse();
        var other=card(); other.setEntity(parent(2)); ui.add(other); tabs(other).setSelectedIndex(1);
        assertThat(org.ipro.form.CommandBar.forForm(lists.getLast()).layout()
            .override(org.ipro.form.action.CrudAction.DELETE.id()).included()).isNotEqualTo(false);
    }

    @Test void dependentContextReactsToCurrentEditorsWithoutApplyingParentBindings() {
        var form=build(new ItemForm<Parent>() {
            @Override protected ItemFormLayout layout(Layout l) {
                return l.of(l.tabs("tabs").add(l.tab("main").add(l.remainingFields()),l.tab("children").add(
                    l.list(Child.class).setFilter("parent",l.self()).setFilter("number",l.value("name")))));
            }
            @Override protected void behavior(org.ipro.form.builder.FormBehavior b) {
                b.onChange("name",change->{
                    if ("normalize".equals(change.value())) getEntityField("name",String.class).setValue("NORMALIZED");
                });
            }
        });
        var parent=parent(1); form.setEntity(parent); ui.add(form); tabs(form).setSelectedIndex(1);
        var list=lists.getFirst();
        form.getEntityField("name",String.class).setValue("normalize");
        assertThat(list.getOpeningContextFilters()).containsEntry("number","NORMALIZED");
        assertThat(parent.getName()).isEqualTo("Parent 1"); assertThat(form.isDirty()).isTrue();
        tabs(form).setSelectedIndex(0); form.getEntityField("name",String.class).setValue("Hidden update");
        assertThat(openings).hasSize(1);
        assertThat(list.getOpeningContextFilters()).containsEntry("number","Hidden update");
        form.getEntityField("name",String.class).clear();
        assertThat(list.getParent()).isEmpty(); assertThat(openings).hasSize(1);
        form.getEntityField("name",String.class).setValue("Restored"); assertThat(openings).hasSize(1);
        tabs(form).setSelectedIndex(1); assertThat(openings).hasSize(2);
        assertThat(lists.getLast().getOpeningContextFilters()).containsEntry("number","Restored");
    }

    @Test void savedViewAndResetKeepTheOpeningRelationAndInitialValues() {
        var form=card(); var parent=parent(1); form.setEntity(parent); ui.add(form); tabs(form).setSelectedIndex(1);
        var list=lists.getFirst();
        list.setContextFilter("parent",parent(2)); list.clearContextFilter();
        list.applyViewState(new org.ipro.metadata.GridViewState(List.of(new org.ipro.metadata.ColumnPath.Spec("number",null)),List.of()));
        assertThat(list.getContextSnapshot().effectiveFilters()).containsEntry("parent",parent);
        assertThat(list.getRequiredContextValues()).containsEntry("parent",parent);
        assertThat(list.getActiveColumns()).extracting(org.ipro.metadata.ColumnPath::getKey).containsExactly("number");
        assertThat(form.isDirty()).isFalse();
    }
}

package org.ipro.form.builtin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.LookupService;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.TableSectionFactory;
import org.ipro.form.builder.layout.*;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridViewStore;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemFormLayoutNodesTest {
    @EntityMetadata(itemFormTitle = "Document", listFormTitle = "Documents")
    @TableSections({Row.class, OtherRow.class, ThirdRow.class})
    public static class Document extends BaseEntity {
        @FieldMetadata(label = "Last", order = 30) String last;
        @FieldMetadata(label = "First", order = 10) String first;
        @FieldMetadata(label = "Middle", order = 20) String middle;
        @FieldMetadata(hidden = true) String hidden;
        String unpublished;
    }

    @TableSectionMetadata(parentEntity = Document.class, parentField = "document")
    public static class Row extends BaseEntity {
        Document document;
        @FieldMetadata String value;
    }
    @TableSectionMetadata(parentEntity = Document.class, parentField = "document")
    public static class OtherRow extends Row { }
    @TableSectionMetadata(parentEntity = Document.class, parentField = "document")
    public static class ThirdRow extends Row { }

    private final MetadataResolver metadata = new MetadataResolver();
    private final FieldFactory fields = new FieldFactory(mock(LookupService.class), mock(SelectionFormAssembler.class));
    private final GenericOwnedSectionService sectionService = mock(GenericOwnedSectionService.class);

    private ItemForm<Document> form(LayoutNode... nodes) {
        return new ItemForm<>(metadata.resolve(Document.class), fields, new ItemFormLayout(List.of(nodes)));
    }

    private static Stream<Component> tree(Component root) {
        if (root instanceof TabSheet sheet) {
            return Stream.concat(Stream.of(root), java.util.stream.IntStream.range(0, sheet.getTabCount())
                .mapToObj(index -> sheet.getComponent(sheet.getTabAt(index))).flatMap(ItemFormLayoutNodesTest::tree));
        }
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(ItemFormLayoutNodesTest::tree));
    }

    private static List<String> labels(Component root) {
        return tree(root).filter(FormLayout.FormItem.class::isInstance)
            .map(item -> item.getElement().getChildren()
                .filter(element -> "label".equals(element.getAttribute("slot")))
                .findFirst().orElseThrow().getText()).toList();
    }

    @Test
    void everyConstructorExposesItsActualMainAreaImmediately() {
        var info = metadata.resolve(Document.class);
        var automatic = new ItemForm<Document>(info, fields);
        var emptySelection = new ItemForm<Document>(info, fields, List.<String>of());
        var selected = new ItemForm<Document>(info, fields, List.of("last", "first", "hidden"));
        var custom = form(new FieldNode("first"));
        var row = new ItemForm<>(Document.class, info.getFormFields(), fields);
        for (var candidate : List.of(automatic, emptySelection, selected, custom, row)) {
            assertThat(candidate.getFormLayout()).isNotNull();
            assertThat(candidate.getFormLayout().getParent()).contains(candidate);
        }
        assertThat(labels(automatic)).containsExactly("First", "Middle", "Last");
        assertThat(labels(emptySelection)).isEqualTo(labels(automatic));
        // Legacy name filtering preserves metadata order and never reintroduces hidden fields.
        assertThat(labels(selected)).containsExactly("First", "Last");
        var extra = new Span("Added after construction");
        custom.getFormLayout().add(extra);
        assertThat(extra.getParent()).contains(custom.getFormLayout());
    }

    @Test
    void rowConstructorPreservesSuppliedOrderAndHiddenFieldsAndSupportsSubclassInitialization() {
        var info = metadata.resolve(Document.class);
        var selected = List.of(info.getFieldByName("last"), info.getFieldByName("first"), info.getFieldByName("hidden"));
        class LegacyRowForm extends ItemForm<Document> {
            LegacyRowForm() {
                super(Document.class, selected, fields);
                assertThat(getField("last")).isNotNull();
                assertThat(getField("hidden")).isNotNull();
                getEntityField("first", String.class).addValueChangeListener(event -> { });
                getFormLayout().add(new Span("Legacy extension"));
            }
        }
        var row = new LegacyRowForm();
        assertThat(row.getMetadata()).isNull();
        assertThat(row.getBindingRegistry().getAll()).extracting(binding -> binding.getFieldName())
            .containsExactly("last", "first", "hidden");
        assertThat(row.getFormLayout().getParent()).contains(row);
    }

    @SuppressWarnings("unchecked")
    private TableSectionFactory factory() {
        var context = mock(ApplicationContext.class);
        when(context.getBean(GridViewStore.class)).thenReturn(mock(GridViewStore.class));
        when(context.getBean(FormSettingsStore.class)).thenReturn(mock(FormSettingsStore.class));
        when(context.getBean(LookupService.class)).thenReturn(mock(LookupService.class));
        ObjectProvider<FormResolver> provider = mock(ObjectProvider.class);
        var resolver = mock(FormResolver.class);
        when(resolver.getFormRegistry()).thenReturn(new org.ipro.form.registry.FormRegistry());
        when(provider.getObject()).thenReturn(resolver);
        return new TableSectionFactory(metadata, fields, context, sectionService, provider, List.of());
    }

    @Test
    void remainingFieldsFollowExplicitFieldsInMetadataOrderAndRemainBound() {
        var form = form(new FieldNode("last"), new RemainingFieldsNode());
        assertThat(labels(form)).containsExactly("Last", "First", "Middle");
        var entity = form.initializeNewEntity(Map.of("first", "Initial"));
        var input = (TextField) form.getField("first");
        assertThat(input.getValue()).isEqualTo("Initial");
        assertThat(form.isDirty()).isFalse();
        input.setValue("Changed");
        assertThat(form.isDirty()).isTrue();
        assertThat(form.getEntity().first).isEqualTo("Changed");
        assertThat(entity).isSameAs(form.peekEntity());
    }

    @Test
    void fieldsExplicitlyPlacedOnLaterTabsDoNotAppearInRemainingFields() {
        var form = form(new RemainingFieldsNode().except("middle"),
            new TabSheetNode(List.of(new TabDefinition("Later", List.of(new FieldNode("last"))))));
        assertThat(labels(form)).containsExactly("First", "Last");
    }

    @Test
    void duplicateRemainingNodesAreRejectedAcrossNestedContainersBeforeFactoryRuns() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> form(new CustomNode(() -> { calls.incrementAndGet(); return new Span(); }),
            new RemainingFieldsNode(), new PanelNode(List.of(new RemainingFieldsNode()))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Only one RemainingFieldsNode");
        assertThat(calls).hasValue(0);
    }

    @Test
    void unknownExclusionsAreRejectedAndExceptDoesNotMutateOriginalNode() {
        var original = new RemainingFieldsNode();
        assertThat(original.except("middle").excludedFields()).containsExactly("middle");
        assertThat(original.excludedFields()).isEmpty();
        assertThatThrownBy(() -> form(original.except("missing")))
            .hasMessageContaining("missing").hasMessageContaining("Document");
    }

    @Test
    void sectionInsideTabIsAttachedThereAndOtherSectionsStayAtBottom() {
        var form = form(new TabSheetNode(List.of(new TabDefinition("rows", "Rows", List.of(new SectionNode(Row.class))))));
        form.setReadOnlySections(List.of(Row.class));
        factory().attachTableSections(form, Document.class);
        var row = form.tableSection(Row.class);
        Component tab = tree(form).filter(c -> c.getId().orElse("").equals("rows")).findFirst().orElseThrow();
        assertThat(tree(tab)).contains(row);
        assertThat(row.isReadOnly()).isTrue();
        assertThat(form.getTableSections()).hasSize(3);
        assertThat(tree(tab)).doesNotContain(form.tableSection(OtherRow.class), form.tableSection(ThirdRow.class));
        assertThat(tree(form).filter(TabSheet.class::isInstance)).hasSize(2);
        row.markDirty();
        assertThat(form.isDirty()).isTrue();
    }

    @Test
    void filteredOutSectionPositionStaysHiddenWithoutAttachingRows() {
        var form = form(new SectionNode(Row.class));
        Component position = tree(form).filter(c -> !c.isVisible()).filter(c -> c.getParent()
            .filter(FormLayout.class::isInstance).isPresent()).findFirst().orElseThrow();
        factory().attachTableSections(form, Document.class, List.of(OtherRow.class));
        assertThat(position.isVisible()).isFalse();
        assertThat(form.hasAttachedSection(Row.class)).isFalse();
        assertThat(form.getTableSections()).hasSize(1);
        assertThat(tree(form).filter(TabSheet.class::isInstance)).isEmpty();
    }

    @Test
    void placedSectionsReceiveCurrentParentAndParticipateInValidationAlongsideBottomSections() {
        var form = form(new SectionNode(Row.class));
        var parent = new Document();
        parent.setId(42L);
        form.setEntity(parent);
        factory().attachTableSections(form, Document.class);
        verify(sectionService, times(3)).findByParent(eq(parent), any(), anyCollection());
        assertThat(form.validateTableSections()).isEmpty();
        verify(sectionService, times(3)).validateRows(eq(parent), anyList(), any());
    }

    @Test
    void unknownAndDuplicateSectionPositionsAreRejected() {
        assertThatThrownBy(() -> form(new SectionNode(Row.class), new SectionNode(Row.class)))
            .hasMessageContaining("Duplicate section position");
        var form = form(new SectionNode(Document.class));
        assertThatThrownBy(() -> factory().attachTableSections(form, Document.class))
            .hasMessageContaining("not declared").hasMessageContaining("Document");
        assertThat(form.getTableSections()).isEmpty();
    }

    @Test
    void allSectionsPlacedInTreeLeaveNoEmptyBottomArea() {
        var form = form(new SectionNode(Row.class), new SectionNode(OtherRow.class), new SectionNode(ThirdRow.class));
        factory().attachTableSections(form, Document.class);
        assertThat(form.getTableSections()).hasSize(3);
        assertThat(tree(form).filter(TabSheet.class::isInstance)).isEmpty();
        var bottom = form.getChildren().filter(com.vaadin.flow.component.orderedlayout.VerticalLayout.class::isInstance)
            .findFirst().orElseThrow();
        assertThat(bottom.isVisible()).isFalse();
        assertThat(form.getTableSections()).allSatisfy(table -> assertThat(table.getParent().orElseThrow().isVisible()).isTrue());
    }

    @Test
    void panelAndTabIdentifiersAndPanelTitleAreRendered() {
        var form = form(new PanelNode("main", "Main fields", List.of(new FieldNode("first"))),
            new TabSheetNode(List.of(new TabDefinition("details", "Details", List.of(new FieldNode("last"))))));
        assertThat(tree(form).flatMap(c -> c.getId().stream())).containsExactly("main", "details");
        assertThat(tree(form).filter(H4.class::isInstance).map(c -> ((H4)c).getText())).containsExactly("Main fields");
        assertThatThrownBy(() -> form(new PanelNode("same", List.of()),
            new TabSheetNode(List.of(new TabDefinition("same", "Details", List.of())))))
            .hasMessageContaining("Duplicate layout id");
    }

    @Test
    void customFactoryCreatesIndependentComponentsForEachFormOpening() {
        var count = new AtomicInteger();
        var custom = new CustomNode(() -> new Span("Instance " + count.incrementAndGet()));
        assertThat(count).hasValue(0);
        var first = form(custom);
        var second = form(custom);
        assertThat(count).hasValue(2);
        assertThat(tree(first).filter(Span.class::isInstance).map(c -> ((Span)c).getText())).contains("Instance 1");
        assertThat(tree(second).filter(Span.class::isInstance).map(c -> ((Span)c).getText())).contains("Instance 2");
        var component = new Span("Legacy");
        assertThat(tree(form(new CustomNode(component)))).contains(component);
        assertThatThrownBy(() -> form(new CustomNode(() -> null))).hasMessageContaining("factory result");
    }

    @Test
    void legacyNodesAndAutomaticSectionPlacementKeepTheirShape() {
        var form = form(new PanelNode(List.of(new FieldNode("first"), new FieldNode("middle"))),
            new TabSheetNode(List.of(new TabDefinition("Details", List.of(new FieldNode("last"))))));
        assertThat(labels(form)).containsExactly("First", "Middle", "Last");
        assertThat(tree(form).flatMap(c -> c.getId().stream())).isEmpty();
        factory().attachTableSections(form, Document.class);
        assertThat(form.getTableSections()).hasSize(3);
        assertThat(tree(form).filter(TabSheet.class::isInstance)).hasSize(2);
    }
}

package org.ip.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.formlayout.FormLayout;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ipro.crud.LookupService;
import org.ipro.form.FieldFactory;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.layout.ItemFormLayout;
import org.ipro.form.builder.layout.RemainingFieldsNode;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityKind;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** AF5.3 captured parity before AF5.4; now guards legacy constructors against the explicit tree. */
@SpringBootTest(classes = Application.class)
class ItemFormRenderParityTest {
    @MockitoBean DataInitializer initializer;
    @MockitoBean LookupService lookups;
    @Autowired ManagedEntityCatalog entities;
    @Autowired MetadataResolver metadata;
    @Autowired FieldFactory fields;
    @Autowired List<ItemFormCustomization> customizations;

    @TestFactory
    Stream<DynamicTest> flatAndRemainingFieldsTreeRenderTheSameFields() {
        var candidates = new LinkedHashSet<Class<?>>(entities.managedEntityClasses());
        customizations.forEach(customization -> candidates.add(customization.entityClass()));
        for (Class<?> owner : List.copyOf(candidates)) {
            metadata.resolveTableSections(owner).forEach(section -> candidates.add(section.getRowClass()));
        }
        var types = candidates.stream().filter(IdentifiableEntity.class::isAssignableFrom)
            .filter(type -> !Modifier.isAbstract(type.getModifiers()))
            .sorted(Comparator.comparing(Class::getName)).toList();
        assertThat(types).as("catalog of item-form types").isNotEmpty();
        assertThat(types).as("parity must cover editable fields, not only empty forms")
            .anyMatch(type -> !formFields(type).isEmpty());
        return types.stream().map(type -> DynamicTest.dynamicTest(type.getName(), () -> compare(type)));
    }

    private List<FieldMetadataInfo> formFields(Class<?> type) {
        return MetadataResolver.isApplicable(type) ? metadata.resolve(type).getFormFields()
            : metadata.resolveRowMetadata(type).getFormFields();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void compare(Class<?> type) {
        UI previous = UI.getCurrent();
        UI current = new UI();
        UI.setCurrent(current);
        try {
            List<FieldMetadataInfo> expected = formFields(type);
            ItemForm<?> flat;
            EntityMetadataInfo treeMetadata;
            if (MetadataResolver.isApplicable(type)) {
                treeMetadata = metadata.resolve(type);
                flat = new ItemForm<>(treeMetadata, fields);
            } else {
                flat = new ItemForm((Class) type, expected, fields);
                // The current tree constructor accepts only EntityMetadataInfo. This test-only
                // bridge exposes the real row facts without adding entity intent to production.
                var row = metadata.resolveRowMetadata(type);
                treeMetadata = new EntityMetadataInfo(type, null, EntityKind.PLAIN,
                    metadata.resolveAllAnnotatedFields(type), row.getFormFields(), row.getGridFields(),
                    row.getColumnPaths(), row.getColumnPaths());
            }
            ItemForm<?> tree = new ItemForm<>(treeMetadata, fields,
                new ItemFormLayout(List.of(new RemainingFieldsNode())));

            var flatView = snapshot(flat, expected);
            var treeView = snapshot(tree, expected);
            assertThat(treeView).as("tree versus flat: " + type.getName()).isEqualTo(flatView);
            System.out.println("AF5.3 " + type.getName() + "; fields=" + expected.size()
                + "; required=" + expected.stream().filter(FieldMetadataInfo::isRequired).count()
                + "; input=" + (MetadataResolver.isApplicable(type) ? "entity" : "row"));
        } finally {
            UI.setCurrent(previous);
        }
    }

    /** Observe bindings and the rendered component tree independently, including label wrappers. */
    private List<FieldView> snapshot(ItemForm<?> form, List<FieldMetadataInfo> expected) {
        var bindings = form.getBindingRegistry().getAll();
        var expectedNames = expected.stream().map(FieldMetadataInfo::getName).toList();
        assertThat(bindings.stream().map(binding -> binding.getDescriptor().key()).toList())
            .as("binding order of " + form.getClass().getName()).containsExactlyElementsOf(expectedNames);

        var byComponent = new IdentityHashMap<Component, FieldView>();
        for (var binding : bindings) {
            var descriptor = binding.getDescriptor();
            var component = binding.getComponent();
            var info = expected.stream().filter(field -> field.getName().equals(descriptor.key())).findFirst().orElseThrow();
            assertThat(descriptor.label()).as("binding label of " + descriptor.key()).isEqualTo(info.getLabel());
            assertThat(descriptor.required()).as("binding required of " + descriptor.key()).isEqualTo(info.isRequired());
            String visibleLabel = renderedLabel(component);
            assertThat(visibleLabel).as("rendered label of " + descriptor.key()).isEqualTo(info.getLabel());
            byComponent.put(component, new FieldView(descriptor.key(), descriptor.label(), visibleLabel,
                descriptor.required(), component.getElement().getProperty("required", false), component.getClass().getName()));
        }
        var rendered = descendants(form).filter(byComponent::containsKey).map(byComponent::get).toList();
        assertThat(rendered.stream().map(FieldView::name).toList())
            .as("rendered field order").containsExactlyElementsOf(expectedNames);
        return rendered;
    }

    private static String renderedLabel(Component component) {
        var parent = component.getParent().orElseThrow();
        if (parent instanceof FormLayout.FormItem) {
            var labels = parent.getElement().getChildren()
                .filter(element -> "label".equals(element.getAttribute("slot"))).toList();
            assertThat(labels).as("one FormItem label per field").hasSize(1);
            if (component instanceof HasLabel labelled) {
                assertThat(labelled.getLabel()).as("no duplicate native label").isNullOrEmpty();
            }
            return labels.getFirst().getText();
        }
        return component instanceof HasLabel labelled ? labelled.getLabel() : null;
    }

    private static Stream<Component> descendants(Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(ItemFormRenderParityTest::descendants));
    }

    private record FieldView(String name, String bindingLabel, String renderedLabel, boolean required,
                             boolean requiredIndicator, String editorType) { }
}

package org.ip.form;

import com.vaadin.flow.component.UI;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ipro.crud.LookupService;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.registry.FormType;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** AF5.2: execute real factories and customizers, without opening or saving records. */
@SpringBootTest(classes = Application.class)
class ItemFormConstructionTest {
    @MockitoBean DataInitializer initializer;
    // Nomenclature and User eagerly load dictionaries in their factories. Construction
    // uses empty lookup results, as in ExplorerFormPreview; data access is tested separately.
    @MockitoBean LookupService lookups;
    // Construction has no authenticated UI; runtime permissions are covered by EmbeddedListRuntimeIT.
    @MockitoBean org.ipro.form.registry.EmbeddedListFactory embeddedLists;
    @Autowired ManagedEntityCatalog entities;
    @Autowired MetadataResolver metadata;
    @Autowired FormRegistry registry;
    @Autowired FormResolver resolver;
    @Autowired List<ItemFormCustomization> customizations;

    @TestFactory
    Stream<DynamicTest> everyRegisteredItemVariantAndAutomaticFormBuilds() {
        var candidates = new LinkedHashSet<Class<?>>(entities.managedEntityClasses());
        customizations.forEach(customization -> candidates.add(customization.entityClass()));
        // Also cover row forms if a future owned section is not a separately managed entity.
        for (Class<?> owner : List.copyOf(candidates)) {
            metadata.resolveTableSections(owner).forEach(section -> candidates.add(section.getRowClass()));
        }

        var openings = new ArrayList<Opening>();
        candidates.stream().filter(IdentifiableEntity.class::isAssignableFrom)
            .filter(type -> !Modifier.isAbstract(type.getModifiers()))
            .sorted(Comparator.comparing(Class::getName)).forEach(type -> {
                openings.add(new Opening(type, null));
                registry.registrationsOf(type).stream()
                    .filter(registration -> registration.formType() == FormType.ITEM)
                    .filter(registration -> registration.kind() == FormRegistry.RegistrationKind.FORM_FACTORY)
                    .map(FormRegistry.Registration::variant).filter(variant -> variant != null)
                    .distinct().sorted().forEach(variant -> openings.add(new Opening(type, variant)));
            });

        assertThat(openings).as("catalog of item-form openings").isNotEmpty();
        assertThat(openings).as("registered factories and automatic forms must both be exercised")
            .anyMatch(opening -> registry.has(opening.type(), FormType.ITEM, opening.variant()))
            .anyMatch(opening -> !registry.has(opening.type(), FormType.ITEM, opening.variant()));
        return openings.stream().map(opening -> DynamicTest.dynamicTest(opening.name(), () -> build(opening)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void build(Opening opening) {
        UI previous = UI.getCurrent();
        UI current = new UI(); // Keep a strong reference throughout each independent opening.
        UI.setCurrent(current);
        try {
            ItemForm<?> form = resolver.resolveItemForm((Class) opening.type(), opening.variant(), null, Map.of());
            assertThat(form).as(opening.name()).isNotNull();
            var registration = registry.registrationsOf(opening.type()).stream()
                .filter(value -> value.formType() == FormType.ITEM)
                .filter(value -> value.kind() == FormRegistry.RegistrationKind.FORM_FACTORY)
                .filter(value -> java.util.Objects.equals(value.variant(), opening.variant())).findFirst();
            registration.map(FormRegistry.Registration::declaredClass).ifPresent(declared ->
                assertThat(form).as("declared implementation of " + opening.name()).isInstanceOf(declared));

            var expectedSections = metadata.resolveTableSections(opening.type()).stream()
                .map(section -> section.getRowClass())
                .filter(row -> form.getSectionFilter() == null || form.getSectionFilter().contains(row)).toList();
            assertThat(form.attachedSectionClasses()).as("sections of " + opening.name())
                .containsExactlyInAnyOrderElementsOf(expectedSections);
            System.out.println("AF5.2 " + opening.name() + " -> " + form.getClass().getName()
                + "; sections=" + expectedSections.size());
        } finally {
            UI.setCurrent(previous);
        }
    }

    private record Opening(Class<?> type, String variant) {
        String name() {
            return type.getName() + " / " + (variant == null ? "default" : variant);
        }
    }
}

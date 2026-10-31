package com.example.generated.harness;

import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.data.EntityDataAccess;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.events.EventSource;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.metadata.annotation.TableSections;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N-file acceptance for one aggregate root and its declared owned rows. It runs in the same
 * isolated consumer as {@link GenerationScenarioTest}; expectations come from
 * {@link ScenarioSpec#SECTIONS}, never from the generated annotations.
 */
@SpringBootTest(classes = GenerationHarnessApplication.class)
class MultiFileScenarioTest {

    @Autowired
    EntityDataAccess data;
    @Autowired
    MetadataResolver metadata;
    @Autowired
    SectionMetadataRegistry sections;
    @Autowired
    GenericOwnedSectionService rows;
    @Autowired
    MetadataDrivenAggregateSaveService aggregates;
    @Autowired
    EntityDescriptorCatalog descriptors;
    @Autowired
    AccessGrantRepository grants;
    @Autowired
    MetadataConsistencyStartupCheck startupCheck;

    private Class<?> rootClass;

    @BeforeEach
    void setUp() throws Exception {
        rootClass = Class.forName(ScenarioSpec.CLASS_NAME);
        grants.deleteAll();
        grant();
        login();
    }

    @AfterEach
    void tearDown() {
        grants.deleteAll();
        SecurityContextHolder.clearContext();
    }

    @Test
    void everySectionIsLinkedAndPublishedAsOwnedRow() throws Exception {
        assertNotNull(startupCheck, "MetadataConsistencyStartupCheck must be part of the real startup");
        assertEquals(ScenarioSpec.TABLE_NAME, rootClass.getAnnotation(Table.class).name());
        assertEquals(EntityExposure.STANDARD_ROOT, descriptors.descriptorOf(rootClass).exposure());
        EntityMetadataInfo info = metadata.resolve(rootClass);
        assertEquals(ScenarioSpec.LIST_TITLE, info.getListFormTitle());
        assertEquals(ScenarioSpec.ITEM_TITLE, info.getItemFormTitle());

        Class<?>[] declared = rootClass.getAnnotation(TableSections.class).value();
        assertEquals(ScenarioSpec.SECTIONS.stream().map(section -> section.className()).toList(),
                java.util.Arrays.stream(declared).map(Class::getName).toList());

        for (ScenarioSpec.SectionSpec expected : ScenarioSpec.SECTIONS) {
            Class<?> rowClass = Class.forName(expected.className());
            TableSectionMetadataInfo section = sections.findByRow(rowClass).orElseThrow();
            assertEquals(rootClass, section.getOwnerClass(), expected.className());
            assertEquals(expected.parentField(), section.getParentFieldName(), expected.className());
            assertEquals(expected.lineNumberField(), section.getLineNumberFieldName(),
                    expected.className());
            assertEquals(expected.title(), section.getTitle(), expected.className());
            assertEquals(expected.order(), section.getOrder(), expected.className());
            assertEquals(expected.tableName(), rowClass.getAnnotation(Table.class).name(),
                    expected.className());
            assertEquals(EntityExposure.OWNED_ROW, descriptors.descriptorOf(rowClass).exposure(),
                    expected.className());
            assertTrue(section.getFormFields().stream()
                            .anyMatch(field -> field.getName().equals(expected.fieldName())),
                    expected.className() + " must publish " + expected.fieldName() + " for its row form");
            assertTrue(section.getGridFields().stream()
                            .anyMatch(field -> field.getName().equals(expected.fieldName())),
                    expected.className() + " must publish " + expected.fieldName() + " for its grid");
        }
    }

    @Test
    void completeFileSetSavesAndReopensEverySection() throws Exception {
        Object root = rootClass.getDeclaredConstructor().newInstance();
        setField(root, "date", ScenarioSpec.DATE_SAMPLE);
        List<MetadataDrivenAggregateSaveService.SectionInput> inputs = new ArrayList<>();
        for (ScenarioSpec.SectionSpec expected : ScenarioSpec.SECTIONS) {
            Class<?> rowClass = Class.forName(expected.className());
            Object row = rowClass.getDeclaredConstructor().newInstance();
            setField(row, expected.fieldName(), expected.sample());
            inputs.add(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    (Class<? extends IdentifiableEntity<Long>>) rowClass, List.of(row)));
        }

        var result = aggregates.save((IdentifiableEntity<Long>) root, inputs, EventSource.UI);
        assertEquals(ScenarioSpec.SECTIONS.size(), result.sections().size());
        Object id = getField(result.aggregate(), "id");
        assertNotNull(id);
        Object reopened = data.detail(rootClass, id).orElseThrow();

        for (ScenarioSpec.SectionSpec expected : ScenarioSpec.SECTIONS) {
            Class<?> rowClass = Class.forName(expected.className());
            TableSectionMetadataInfo section = sections.findByRow(rowClass).orElseThrow();
            List<?> savedRows = rowsFor(reopened, section);
            assertEquals(1, savedRows.size(), expected.className());
            assertEquals(expected.sample(), getField(savedRows.getFirst(), expected.fieldName()),
                    expected.className());
            assertEquals(1, getField(savedRows.getFirst(), expected.lineNumberField()),
                    expected.className() + " line number");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<?> rowsFor(Object parent, TableSectionMetadataInfo section) {
        return ((GenericOwnedSectionService) rows).findByParent(
                (IdentifiableEntity<Long>) parent, section);
    }

    private void grant() {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey("writer");
        grant.setDimension(C5PermissionKeys.entity(rootClass));
        grant.setDimensionValueId(null);
        grant.setCanRead(true);
        grant.setCanUpdate(true);
        grant.setCanDelete(true);
        grants.saveAndFlush(grant);
    }

    private static void login() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("writer", null, List.of()));
    }

    private static Object getField(Object entity, String name) {
        try {
            Field field = fieldOf(entity.getClass(), name);
            field.setAccessible(true);
            return field.get(entity);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Cannot read field " + name, ex);
        }
    }

    private static void setField(Object entity, String name, Object value) {
        try {
            Field field = fieldOf(entity.getClass(), name);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Cannot write field " + name, ex);
        }
    }

    private static Field fieldOf(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // continue with the superclass
            }
        }
        throw new AssertionError("No field " + name + " on " + type.getName());
    }
}

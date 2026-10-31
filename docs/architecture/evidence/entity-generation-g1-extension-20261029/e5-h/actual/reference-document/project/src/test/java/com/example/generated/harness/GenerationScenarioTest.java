package com.example.generated.harness;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.crud.ValidationException;
import org.ipro.metadata.HasDisplayName;
import org.ipro.numbering.annotation.Numbered;
import org.ipro.numbering.annotation.NumberingRole;
import org.ipro.data.EntityDataAccess;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.MetadataDiagnostic;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario-independent acceptance checks. The expectations live in {@link ScenarioSpec}; this
 * class only applies them to the class named by the spec. It never derives expected labels or
 * publications from the generated annotations.
 */
@SpringBootTest(classes = GenerationHarnessApplication.class)
class GenerationScenarioTest {

    @Autowired
    EntityDataAccess data;
    @Autowired
    MetadataResolver metadata;
    @Autowired
    EntityDescriptorCatalog descriptors;
    @Autowired
    AccessGrantRepository grants;
    @Autowired
    MetadataConsistencyStartupCheck startupCheck;

    private Class<?> entityClass;
    private EntityMetadataInfo info;

    @BeforeEach
    void setUp() throws Exception {
        entityClass = Class.forName(ScenarioSpec.CLASS_NAME);
        info = metadata.resolve(entityClass);
        grants.deleteAll();
        grant("writer", true);
        login("writer");
    }

    @AfterEach
    void tearDown() {
        grants.deleteAll();
        SecurityContextHolder.clearContext();
    }

    @Test
    void metadataMatchesIndependentScenarioSpec() {
        assertEquals(ScenarioSpec.CLASS_NAME, entityClass.getName());
        assertEquals(ScenarioSpec.TABLE_NAME, entityClass.getAnnotation(Table.class).name());
        assertNotNull(startupCheck, "MetadataConsistencyStartupCheck must be part of the real startup");
        assertEquals(EntityExposure.STANDARD_ROOT, descriptors.descriptorOf(entityClass).exposure());

        assertEquals(EntityKind.AUTO, info.getAnnotation().kind());
        assertEquals(ScenarioSpec.BASE.name(), info.getEntityKind().name());
        assertEquals(ScenarioSpec.LIST_TITLE, info.getListFormTitle());
        assertEquals(ScenarioSpec.ITEM_TITLE, info.getItemFormTitle());
        assertEquals(ScenarioSpec.SELECTION_TITLE, info.getSelectionFormTitle());
        assertEquals(ScenarioSpec.ICON, info.getIcon());

        assertEquals(ScenarioSpec.LIST_COLUMNS,
                info.getListColumnPaths().stream().map(ColumnPath::getKey).toList());
        assertEquals(ScenarioSpec.SELECT_COLUMNS,
                info.getSelectColumnPaths().stream().map(ColumnPath::getKey).toList());
        assertArrayEquals(ScenarioSpec.LIST_COLUMNS.toArray(String[]::new),
                info.getAnnotation().listColumns());
        assertArrayEquals(ScenarioSpec.SELECT_COLUMNS.toArray(String[]::new),
                info.getAnnotation().selectColumns());

        List<String> specNames = ScenarioSpec.FIELDS.stream().map(ScenarioSpec.FieldSpec::name).toList();
        assertEquals(specNames, info.getAllAnnotatedFields().stream()
                .map(FieldMetadataInfo::getName).filter(specNames::contains).toList());
        assertEquals(ScenarioSpec.FIELDS.stream().filter(ScenarioSpec.FieldSpec::form)
                        .map(ScenarioSpec.FieldSpec::name).toList(),
                info.getFormFields().stream().map(FieldMetadataInfo::getName)
                        .filter(specNames::contains).toList());
        assertTrue(info.getGridFields().stream().map(FieldMetadataInfo::getName)
                .noneMatch(specNames::contains), "own fields are published through explicit columns, not grid");

        for (ScenarioSpec.BaseFieldSpec spec : ScenarioSpec.BASE_FIELDS) {
            assertBaseField(spec);
        }

        for (ScenarioSpec.FieldSpec spec : ScenarioSpec.FIELDS) {
            FieldMetadataInfo field = info.getFieldByName(spec.name());
            assertNotNull(field, spec.name());
            assertEquals(spec.label(), field.getLabel(), spec.name());
            assertEquals(spec.order(), field.getOrder(), spec.name());
            assertEquals(spec.requiredMode(), field.getRequiredMode(), spec.name());
            assertEquals(spec.serverRequired(), field.isServerRequired(), spec.name());
            assertEquals(spec.serverRequired() || spec.requiredMode() == RequiredMode.REQUIRED,
                    field.isRequired(), spec.name());
            assertEquals(!spec.form(), field.isHidden(), spec.name());
            assertFalse(field.isGridVisible(), spec.name());
            assertEquals(spec.type(), field.getResolvedType(), spec.name());
            assertTrue(field.getDiagnostics().stream()
                    .noneMatch(diagnostic -> diagnostic.severity() == MetadataDiagnostic.Severity.ERROR),
                    () -> spec.name() + " has metadata errors: " + field.getDiagnostics());

            Field reflected = fieldOf(entityClass, spec.name());
            assertEquals(spec.javaType(), reflected.getType(), spec.name());
            Column column = reflected.getAnnotation(Column.class);
            assertNotNull(column, spec.name());
            assertEquals(spec.column(), column.name(), spec.name());
            assertEquals(spec.serverRequired(), !column.nullable(), spec.name());
            if (spec.type() == FieldType.TEXT) {
                Size size = reflected.getAnnotation(Size.class);
                assertNotNull(size, spec.name() + " must keep the declared length");
                assertEquals(spec.length(), size.max(), spec.name());
            } else {
                assertNull(reflected.getAnnotation(Size.class), spec.name());
            }
            if (spec.type() == FieldType.DECIMAL) {
                Digits digits = reflected.getAnnotation(Digits.class);
                assertNotNull(digits, spec.name());
                assertEquals(spec.precision() - spec.scale(), digits.integer(), spec.name());
                assertEquals(spec.scale(), digits.fraction(), spec.name());
            } else {
                assertNull(reflected.getAnnotation(Digits.class), spec.name());
            }
            if (spec.serverRequired()) {
                assertNotNull(reflected.getAnnotation(NotNull.class), spec.name());
            } else {
                assertNull(reflected.getAnnotation(NotNull.class), spec.name());
            }
            assertNull(reflected.getAnnotation(NotBlank.class),
                    spec.name() + " must not acquire NotBlank beyond the input contract");

            assertDoesNotThrow(() -> entityClass.getMethod("get" + capitalized(spec.name())));
            assertDoesNotThrow(() -> entityClass.getMethod("set" + capitalized(spec.name()),
                    spec.javaType()));
            if (spec.javaType() == Boolean.class) {
                assertThrows(NoSuchMethodException.class,
                        () -> entityClass.getMethod("is" + capitalized(spec.name())),
                        spec.name() + " must use Boolean.getX(), not isX()");
            }
        }
    }

    @Test
    void canonicalCreateReadUpdatePreservesEveryValue() throws Exception {
        boolean document = ScenarioSpec.BASE == ScenarioSpec.Base.DOCUMENT;
        Object created = create(newEntity(Map.of()));
        Object id = getField(created, "id");
        assertNotNull(id);
        String identifierName = document ? "number" : "code";
        String identifier = (String) getField(created, identifierName);
        assertNotNull(identifier, identifierName + " must be assigned by the standard numbering service");
        assertFalse(identifier.isBlank(), identifierName);

        Object read = detail(id).orElseThrow();
        if (document) {
            assertEquals(ScenarioSpec.DATE_SAMPLE, getField(read, "date"));
            assertEquals(identifier + " от " + ScenarioSpec.DATE_SAMPLE,
                    ((HasDisplayName) read).getDisplayName());
        } else {
            assertEquals(ScenarioSpec.NAME_SAMPLE, getField(read, "name"));
        }
        for (ScenarioSpec.FieldSpec spec : ScenarioSpec.FIELDS) {
            assertEquals(spec.sample(), getField(read, spec.name()), spec.name());
        }

        ScenarioSpec.FieldSpec updatedField = ScenarioSpec.FIELDS.stream()
                .filter(spec -> spec.type() == FieldType.TEXT).findFirst().orElse(null);
        String updatedValue = "upd-" + ScenarioSpec.SCENARIO;
        if (document) {
            setField(read, "date", ScenarioSpec.DATE_UPDATED);
        } else {
            setField(read, "name", "Обновлено " + ScenarioSpec.SCENARIO);
        }
        if (updatedField != null) {
            setField(read, updatedField.name(), updatedValue);
        }
        update(read);

        Object after = detail(id).orElseThrow();
        if (document) {
            assertEquals(identifier, getField(after, "number"),
                    "document number must survive an update");
            assertEquals(ScenarioSpec.DATE_UPDATED, getField(after, "date"));
            assertEquals(identifier + " от " + ScenarioSpec.DATE_UPDATED,
                    ((HasDisplayName) after).getDisplayName());
        } else {
            assertEquals("Обновлено " + ScenarioSpec.SCENARIO, getField(after, "name"));
            assertEquals(identifier, getField(after, "code"));
        }
        for (ScenarioSpec.FieldSpec spec : ScenarioSpec.FIELDS) {
            if (updatedField != null && spec.name().equals(updatedField.name())) {
                assertEquals(updatedValue, getField(after, spec.name()), spec.name());
            } else {
                assertEquals(spec.sample(), getField(after, spec.name()), spec.name());
            }
        }
    }

    @Test
    void validationRejectsMissingAndMalformedValuesWithoutInventingNotBlank() throws Exception {
        boolean document = ScenarioSpec.BASE == ScenarioSpec.Base.DOCUMENT;
        if (document) {
            RuntimeException missingDate = assertThrows(RuntimeException.class,
                    () -> create(newEntity(with("date", null))),
                    "the inherited document date is mandatory");
            assertNotNull(missingDate.getMessage(), "missing-date refusal must be explained");
            assertTrue(missingDate.getMessage().toLowerCase(java.util.Locale.ROOT).contains("date")
                            || missingDate.getMessage().contains("дат"),
                    "missing-date refusal must identify the date field: " + missingDate.getMessage());
        } else {
            assertThrows(ValidationException.class, () -> create(newEntity(with("name", null))));
        }

        Optional<ScenarioSpec.FieldSpec> requiredString = ScenarioSpec.FIELDS.stream()
                .filter(spec -> spec.serverRequired() && spec.type() == FieldType.TEXT)
                .findFirst();
        if (requiredString.isPresent()) {
            ScenarioSpec.FieldSpec spec = requiredString.get();
            assertThrows(ValidationException.class, () -> create(newEntity(with(spec.name(), null))));

            Object empty = create(newEntity(with(spec.name(), "")));
            Object emptyRead = detail(getField(empty, "id")).orElseThrow();
            assertEquals("", getField(emptyRead, spec.name()),
                    "an own required String keeps its contract without an added NotBlank");
        }

        Optional<ScenarioSpec.FieldSpec> lengthField = ScenarioSpec.FIELDS.stream()
                .filter(spec -> spec.type() == FieldType.TEXT).findFirst();
        if (lengthField.isPresent()) {
            ScenarioSpec.FieldSpec spec = lengthField.get();
            assertThrows(ValidationException.class,
                    () -> create(newEntity(with(spec.name(), "x".repeat(spec.length() + 1)))));
        } else if (!document) {
            assertThrows(ValidationException.class,
                    () -> create(newEntity(with("name", "x".repeat(201)))));
        }

        Optional<ScenarioSpec.FieldSpec> decimal = ScenarioSpec.FIELDS.stream()
                .filter(spec -> spec.type() == FieldType.DECIMAL).findFirst();
        if (decimal.isPresent()) {
            ScenarioSpec.FieldSpec spec = decimal.get();
            assertThrows(ValidationException.class, () -> create(newEntity(with(spec.name(),
                    new BigDecimal("1." + "1".repeat(spec.scale() + 1))))));
        }

        Optional<ScenarioSpec.FieldSpec> uiOnlyRequired = ScenarioSpec.FIELDS.stream()
                .filter(spec -> !spec.serverRequired() && spec.requiredMode() == RequiredMode.REQUIRED)
                .findFirst();
        if (uiOnlyRequired.isPresent()) {
            ScenarioSpec.FieldSpec spec = uiOnlyRequired.get();
            Object created = create(newEntity(with(spec.name(), null)));
            Object read = detail(getField(created, "id")).orElseThrow();
            assertNull(getField(read, spec.name()),
                    "UI-only REQUIRED must not become a server-required value");
        }
    }

    @Test
    void accessIsDeniedWithoutGrantAndAllowedWithOne() throws Exception {
        grants.deleteAll();
        assertThrows(AccessDeniedException.class, () -> create(newEntity(Map.of())));

        grant("writer", true);
        Object created = create(newEntity(Map.of()));
        Object id = getField(created, "id");

        grants.deleteAll();
        assertThrows(AccessDeniedException.class, () -> detail(id),
                "read without a C5 entity-read grant is denied");

        grant("writer", false);
        Object readOnly = detail(id).orElseThrow();
        assertThrows(AccessDeniedException.class, () -> update(readOnly),
                "read grant must not authorize update");

        grant("writer", true);
        if (ScenarioSpec.BASE == ScenarioSpec.Base.DOCUMENT) {
            setField(readOnly, "date", ScenarioSpec.DATE_UPDATED);
            update(readOnly);
            assertEquals(ScenarioSpec.DATE_UPDATED,
                    getField(detail(id).orElseThrow(), "date"));
        } else {
            setField(readOnly, "name", "Доступ восстановлен");
            update(readOnly);
            assertEquals("Доступ восстановлен",
                    getField(detail(id).orElseThrow(), "name"));
        }
    }

    @Test
    void platformTypesComeFromJarsAndGeneratedTypeFromTheIsolatedBuild() {
        for (Class<?> platformType : List.of(StandardCatalogEntity.class, StandardDocumentEntity.class,
                EntityDataAccess.class, MetadataResolver.class, FieldMetadata.class)) {
            String location = platformType.getProtectionDomain().getCodeSource().getLocation().toString();
            assertTrue(location.endsWith(".jar"),
                    platformType.getName() + " must come from a platform JAR, not target/classes: " + location);
        }
        String generatedLocation = entityClass.getProtectionDomain().getCodeSource()
                .getLocation().toString();
        assertTrue(generatedLocation.replace('\\', '/').contains("/target/classes"),
                "generated type must be built by the isolated consumer: " + generatedLocation);
        assertFalse(generatedLocation.endsWith(".jar"));
    }

    private void assertBaseField(ScenarioSpec.BaseFieldSpec spec) {
        String name = spec.name();
        FieldMetadataInfo field = info.getFieldByName(name);
        assertNotNull(field, name);
        assertEquals(spec.label(), field.getLabel(), name);
        assertEquals(spec.order(), field.getOrder(), name);
        assertEquals(spec.requiredMode(), field.getRequiredMode(), name);
        assertTrue(field.isServerRequired(), name);
        assertTrue(field.isRequired(), name);
        assertFalse(field.isHidden(), name);
        assertTrue(field.isGridVisible(), name);
        assertEquals(spec.type(), field.getResolvedType(), name);
        Field reflected = fieldOf(entityClass, name);
        assertEquals(spec.javaType(), reflected.getType(), name);
        Column column = reflected.getAnnotation(Column.class);
        assertNotNull(column, name);
        assertEquals(spec.column(), column.name(), name);
        assertFalse(column.nullable(), name);
        if (spec.length() != null) {
            Size size = reflected.getAnnotation(Size.class);
            assertNotNull(size, name);
            assertEquals(spec.length(), size.max(), name);
            assertEquals(spec.length(), column.length(), name);
        } else {
            assertNull(reflected.getAnnotation(Size.class), name);
        }
        if (spec.notBlank()) {
            assertNotNull(reflected.getAnnotation(NotBlank.class), name);
        } else {
            assertNull(reflected.getAnnotation(NotBlank.class), name);
        }
        if (spec.type() == FieldType.DATE) {
            assertNotNull(reflected.getAnnotation(NotNull.class), name);
        }
        if (spec.numberingRole() != null) {
            Numbered numbered = reflected.getAnnotation(Numbered.class);
            assertNotNull(numbered, name);
            assertEquals(NumberingRole.valueOf(spec.numberingRole()), numbered.role(), name);
            if (spec.dateField() != null) {
                assertEquals(spec.dateField(), numbered.dateField(), name);
            }
        }
    }

    private void grant(String user, boolean write) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(user);
        grant.setDimension(C5PermissionKeys.entity(entityClass));
        grant.setDimensionValueId(null);
        grant.setCanRead(true);
        grant.setCanUpdate(write);
        grant.setCanDelete(write);
        grants.saveAndFlush(grant);
    }

    private static void login(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }

    private Object newEntity(Map<String, Object> overrides) throws ReflectiveOperationException {
        Object entity = entityClass.getDeclaredConstructor().newInstance();
        for (ScenarioSpec.BaseFieldSpec spec : ScenarioSpec.BASE_FIELDS) {
            setField(entity, spec.name(), overrides.containsKey(spec.name())
                    ? overrides.get(spec.name()) : spec.sample());
        }
        for (ScenarioSpec.FieldSpec spec : ScenarioSpec.FIELDS) {
            setField(entity, spec.name(), overrides.containsKey(spec.name())
                    ? overrides.get(spec.name()) : spec.sample());
        }
        return entity;
    }

    private static Map<String, Object> with(String name, Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(name, value);
        return values;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object create(Object entity) {
        return data.create((Class) entityClass, (IdentifiableEntity<Long>) entity);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<Object> detail(Object id) {
        return (Optional) data.detail((Class) entityClass, id);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object update(Object entity) {
        return data.update((Class) entityClass, (IdentifiableEntity<Long>) entity);
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

    private static String capitalized(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}

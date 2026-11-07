package com.example.generated.harness;

import com.example.Item;
import org.ipro.crud.StandardCatalogEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import org.ipro.crud.BaseEntity;
import org.ipro.data.EntityDataAccess;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.register.*;
import org.ipro.rls.AccessGrantRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

/** Real starter, installed platform JARs and an independently specified generated target. */
@SpringBootTest(classes = GenerationHarnessApplication.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class RegisterScenarioTest {
    @Autowired RegisterRegistry registry;
    @Autowired RegisterNamedOperations operations;
    @Autowired RegisterKeyWrites writes;
    @Autowired RegisterReaderFactory readers;
    @Autowired EntityDescriptorCatalog catalog;
    @Autowired EntityDataAccess data;
    @Autowired AccessGrantRepository grants;
    @Autowired EntityManagerFactory emf;
    @Autowired MetadataConsistencyStartupCheck startup;
    Class type;

    @BeforeEach void setup() throws Exception {
        type = Class.forName(ScenarioSpec.CLASS_NAME);
        grants.deleteAll();
        ConsumerGrants.login("writer");
        ConsumerGrants.entityWrite(grants, "writer", type);
        ConsumerGrants.entityWrite(grants, "writer", Item.class);
        ConsumerGrants.entityWrite(grants, "writer", com.example.Region.class);
        for (var policy : type.getAnnotationsByType(org.ipro.rls.RlsDimension.class))
            ConsumerGrants.rowScope(grants,"writer",((org.ipro.rls.RlsDimension)policy).value(),null);
        ConsumerGrants.action(grants, "writer", RegisterKeyWritePermissions.setPermissionId(type));
        ConsumerGrants.action(grants, "writer", RegisterKeyWritePermissions.deletePermissionId(type));
    }
    @AfterEach void cleanup() { ConsumerGrants.logout(); }

    @Test void startupAndDescriptorMatchIndependentSpec() {
        assertThat(startup).isNotNull();
        assertThat(type.getSuperclass().getName()).isEqualTo(ScenarioSpec.BASE);
        assertThat(registry.descriptors()).hasSize(1);
        var descriptor = registry.require(type);
        assertThat(catalog.classificationOf(type).kind()).isEqualTo(EntityKind.INFORMATION_REGISTER);
        assertThat(catalog.classificationOf(type).recorderOwned()).isFalse();
        assertThat(data.descriptor(type).exposure()).isEqualTo(EntityExposure.STANDARD_ROOT);
        assertThat(descriptor.dimensions()).extracting(f -> f.name()).containsExactly(ScenarioSpec.DIMENSION);
        assertThat(descriptor.resources()).extracting(f -> f.name()).containsExactly(ScenarioSpec.RESOURCES);
        assertThat(descriptor.rowKeyColumns()).containsExactly(ScenarioSpec.KEYS);
        assertThat(descriptor.dimensionGroupColumns()).containsExactly(ScenarioSpec.DIMENSION_COLUMN);
        assertThat(descriptor.periodic()).isEqualTo(ScenarioSpec.PERIODIC);
        assertThat(descriptor.periodicity().name()).isEqualTo(ScenarioSpec.PERIODICITY);
        assertThat(descriptor.displayName()).isEqualTo(ScenarioSpec.DISPLAY_NAME);
        assertThat(descriptor.keyWriteAllowed()).isEqualTo(ScenarioSpec.KEY_WRITE);
        assertThat(descriptor.keyChangeAllowed()).isEqualTo(ScenarioSpec.KEY_CHANGE);
        assertThat(descriptor.genericWritesAllowed()).isEqualTo(ScenarioSpec.GENERIC_WRITES);
    }

    @Test void mappingsMatchExactKeyColumnsAndRoles() throws Exception {
        Table table = (Table) type.getAnnotation(Table.class);
        assertThat(table.name()).isEqualTo(ScenarioSpec.TABLE);
        assertThat(table.uniqueConstraints()).hasSize(1);
        assertThat(table.uniqueConstraints()[0].columnNames()).containsExactly(ScenarioSpec.KEYS);
        Field dimension = type.getDeclaredField(ScenarioSpec.DIMENSION);
        assertThat(dimension.getType().getName()).isEqualTo(ScenarioSpec.DIMENSION_TYPE);
        assertThat(dimension.getAnnotation(NotNull.class)).isNotNull();
        if (referenceDimension()) {
            JoinColumn column = dimension.getAnnotation(JoinColumn.class);
            assertThat(column.name()).isEqualTo(ScenarioSpec.DIMENSION_COLUMN);
            assertThat(column.nullable()).isFalse();
            assertThat(column.updatable()).isFalse();
            assertThat(dimension.getAnnotation(ManyToOne.class).optional()).isFalse();
            assertThat(dimension.getAnnotation(ManyToOne.class).fetch()).isEqualTo(FetchType.LAZY);
            Field period = PeriodicRegisterEntryBase.class.getDeclaredField("period");
            assertThat(period.getType()).isEqualTo(LocalDateTime.class);
            assertThat(period.getAnnotation(Column.class).name()).isEqualTo("period_date");
            assertThat(period.getAnnotation(Column.class).updatable()).isFalse();
            assertThat(Arrays.stream(type.getDeclaredFields()).map(Field::getName)).doesNotContain("period");
        } else {
            Column column = dimension.getAnnotation(Column.class);
            assertThat(column.name()).isEqualTo(ScenarioSpec.DIMENSION_COLUMN);
            assertThat(column.nullable()).isFalse();
            assertThat(column.updatable()).isFalse();
            assertThat(column.length()).isEqualTo(20);
            assertThat(registry.require(type).period()).isEmpty();
        }
        Field resource = type.getDeclaredField("rate");
        assertThat(resource.getType().getName()).isEqualTo(ScenarioSpec.RESOURCE_TYPE);
        Column column = resource.getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo(ScenarioSpec.RESOURCE_COLUMN);
        assertThat(column.nullable()).isFalse();
        assertThat(column.updatable()).isTrue();
        assertThat(column.precision()).isEqualTo(19);
        assertThat(column.scale()).isEqualTo(2);
    }

    @Test void namedOperationsContainOnlySetAndDeleteForTheTarget() {
        assertThat(operations.operations()).extracting(RegisterNamedOperations.Operation::permissionId)
            .containsExactlyInAnyOrder(RegisterKeyWritePermissions.setPermissionId(type),
                RegisterKeyWritePermissions.deletePermissionId(type));
    }

    @Test void generatedRowSupportsNamedWritesAndCanonicalReadThroughFreshContext() throws Exception {
        Map<String, Object> key;
        if (referenceDimension()) {
            var item = reference("ITEM-");
            key = Map.of(ScenarioSpec.DIMENSION, item.getId());
        } else { key = Map.of(ScenarioSpec.DIMENSION, "R-" + UUID.randomUUID().toString().substring(0, 8)); }
        LocalDateTime period = ScenarioSpec.PERIODIC ? LocalDateTime.of(2026, 11, 1, 17, 45) : null;
        var created = writes.set(type, key, Map.of("rate", new BigDecimal("10.25")), period);
        assertThat(created.verdict()).isEqualTo(RegisterKeyWrites.Verdict.CREATED);
        BaseEntity row = (BaseEntity) created.row();
        Long id = row.getId();
        try (EntityManager fresh = emf.createEntityManager()) {
            Object persisted = fresh.find(type, id);
            assertThat(persisted).isNotNull();
            assertThat((BigDecimal) type.getMethod("getRate").invoke(persisted)).isEqualByComparingTo("10.25");
            if (ScenarioSpec.PERIODIC) assertThat(((PeriodicRegisterEntryBase) persisted).getPeriod())
                .isEqualTo(period.toLocalDate().atStartOfDay());
        }
        assertThat(data.detail(type, id)).isPresent();
        var updated = writes.set(type, key, Map.of("rate", new BigDecimal("12.50")), period, row.getVersion());
        assertThat(updated.verdict()).isEqualTo(RegisterKeyWrites.Verdict.UPDATED);
        BaseEntity current = (BaseEntity) updated.row();
        assertThat(writes.set(type, key, Map.of("rate", new BigDecimal("12.50")), period, current.getVersion()).verdict())
            .isEqualTo(RegisterKeyWrites.Verdict.UNCHANGED);
        assertThat(writes.delete(type, key, period, current.getVersion()).verdict()).isEqualTo(RegisterKeyWrites.Verdict.DELETED);
        assertThat(data.detail(type, id)).isEmpty();
    }

    @Test void platformIsLoadedFromBackendJarsAndTargetFromConsumerClasses() throws Exception {
        for (Class<?> platform : List.of(RegisterRegistry.class, RegisterMetadata.class, EntityDataAccess.class)) {
            assertThat(platform.getProtectionDomain().getCodeSource().getLocation().toString()).endsWith(".jar");
        }
        assertThat(type.getProtectionDomain().getCodeSource().getLocation().toString()).endsWith("/target/classes/");
        assertThat(Item.class.getProtectionDomain().getCodeSource().getLocation().toString()).endsWith("/target/classes/");
        for (String absent : List.of("org.ip.Application", "org.ip.model.Nomenclature", "com.vaadin.flow.component.UI")) {
            assertThatThrownBy(() -> Class.forName(absent)).isInstanceOf(ClassNotFoundException.class);
        }
        String tree = Files.readString(Path.of("target/consumer-runtime-dependency-tree.json"));
        assertThat(tree).contains("platform-register", "platform-backend-starter")
            .doesNotContain("platform-vaadin", "platform-rest", "platform-report", "tomcat-embed-core");
    }

    @Test void canonicalCrudIsAllowedAndKeyChangesAreRejected() throws Exception {
        BaseEntity row = (BaseEntity) type.getDeclaredConstructor().newInstance();
        Object dimension;
        if (referenceDimension()) {
            dimension = reference("C-");
        } else { dimension = "C-" + UUID.randomUUID().toString().substring(0, 8); }
        if (ScenarioSpec.PERIODIC) ((PeriodicRegisterEntryBase) row).setPeriod(LocalDateTime.of(2026, 11, 1, 15, 30));
        Field key = type.getDeclaredField(ScenarioSpec.DIMENSION);
        key.setAccessible(true);
        key.set(row, dimension);
        type.getMethod("setRate", BigDecimal.class).invoke(row, BigDecimal.ONE);
        row = (BaseEntity) data.create(type, row);
        Long id = row.getId();
        BaseEntity loaded = (BaseEntity) data.detail(type, id).orElseThrow();
        type.getMethod("setRate", BigDecimal.class).invoke(loaded, BigDecimal.TEN);
        data.update(type, loaded);
        Object persisted = data.detail(type, id).orElseThrow();
        assertThat((BigDecimal) type.getMethod("getRate").invoke(persisted)).isEqualByComparingTo("10");
        if (ScenarioSpec.PERIODIC) {
            ((PeriodicRegisterEntryBase) persisted).setPeriod(LocalDateTime.of(2026, 11, 2, 0, 0));
        } else { key.set(persisted, referenceDimension() ? reference("OTHER-") : "CHANGED"); }
        type.getMethod("setRate", BigDecimal.class).invoke(persisted, new BigDecimal("11"));
        assertThatThrownBy(() -> data.update(type, (BaseEntity) persisted)).hasMessageContaining("REGISTER_KEY_CHANGE_REQUIRED");
        data.delete(type, id);
        assertThat(data.detail(type, id)).isEmpty();
    }
    private boolean referenceDimension() { return !ScenarioSpec.DIMENSION_TYPE.equals("java.lang.String"); }
    private StandardCatalogEntity reference(String prefix) throws Exception {
        var item=(StandardCatalogEntity)Class.forName(ScenarioSpec.DIMENSION_TYPE).getDeclaredConstructor().newInstance();
        item.setCode(prefix+UUID.randomUUID().toString().substring(0,8));item.setName("Generated reference "+prefix);
        return (StandardCatalogEntity)data.create((Class)item.getClass(),item);
    }
}

package org.ipro.crud;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.ipro.identity.IdentifiableEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BaseEntityPersistenceTest {
    private static SessionFactory factory;

    @BeforeAll
    static void openFactory() {
        factory = new Configuration()
                .addAnnotatedClass(Record.class)
                .addAnnotatedClass(Child.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:identity-" + UUID.randomUUID())
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    @AfterAll
    static void closeFactory() {
        if (factory != null) factory.close();
    }

    @Test
    void inheritsLongJpaIdAndVersionWithIdentityGeneration() throws Exception {
        var type = factory.getMetamodel().entity(Record.class);
        assertThat(type.getIdType().getJavaType()).isEqualTo(Long.class);
        assertThat(type.getId(Long.class).getJavaMember().getDeclaringClass()).isEqualTo(BaseEntity.class);
        assertThat(type.getVersion(Long.class).getJavaType()).isEqualTo(Long.class);
        assertThat(factory.getMetamodel().entity(Child.class).getIdType().getJavaType()).isEqualTo(Long.class);
        assertThat(BaseEntity.class.getDeclaredField("id").getAnnotation(GeneratedValue.class).strategy())
                .isEqualTo(GenerationType.IDENTITY);
    }

    @Test
    void generatesLongIdAndRetainsItAcrossReloadAndVersionedUpdate() {
        Record record = new Record();
        record.label = "initial";
        assertThat(record.getId()).isNull();
        assertThat(record.getVersion()).isNull();
        int transientHash = record.hashCode();
        try (var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            session.persist(record);
            Child child = new Child();
            child.parent = record;
            session.persist(child);
            transaction.commit();
            assertThat(child.getId()).isNotNull();
        }
        Long id = record.getId();
        Long version = record.getVersion();
        assertThat(id).isNotNull().isPositive();
        assertThat(version).isNotNull();
        assertThat(record.hashCode()).isEqualTo(transientHash);
        try (var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            Record loaded = session.find(Record.class, id);
            assertThat(loaded).isEqualTo(record);
            assertThat(loaded.getVersion()).isEqualTo(version);
            loaded.label = "updated";
            transaction.commit();
            assertThat(loaded.getId()).isEqualTo(id);
            assertThat(loaded.getVersion()).isEqualTo(version + 1L);
        }
        try (var session = factory.openSession()) {
            Record loaded = session.find(Record.class, id);
            assertThat(loaded.label).isEqualTo("updated");
            assertThat(loaded.getId()).isEqualTo(id);
            assertThat(loaded.getVersion()).isEqualTo(version + 1L);
        }
    }

    @Test
    void realHibernateProxyAndDetachedEntityKeepSymmetricEqualityAndStableHash() {
        Record detached = new Record();
        detached.label = "proxy";
        try (var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            session.persist(detached);
            transaction.commit();
        }
        Record proxy;
        try (var session = factory.openSession()) {
            proxy = session.getReference(Record.class, detached.getId());
            assertThat(Hibernate.isInitialized(proxy)).isFalse();
            assertThat(detached.equals(proxy)).isTrue();
            assertThat(proxy.equals(detached)).isTrue();
            assertThat(proxy.hashCode()).isEqualTo(detached.hashCode());
            Hibernate.initialize(proxy);
        }
        assertThat(proxy.equals(detached)).isTrue();
        assertThat(detached.equals(proxy)).isTrue();
        assertThat(proxy.hashCode()).isEqualTo(detached.hashCode());
    }

    @Test
    void concreteLongMethodsAndCompilerBridgesServeTheSameIdentity() throws Exception {
        assertThat(BaseEntity.class.getTypeParameters()).isEmpty();
        var identity = (ParameterizedType) BaseEntity.class.getGenericInterfaces()[0];
        assertThat(identity.getRawType()).isEqualTo(IdentifiableEntity.class);
        assertThat(identity.getActualTypeArguments()).containsExactly(Long.class);
        assertThat(BaseEntity.class.getMethod("getId").getReturnType()).isEqualTo(Long.class);
        assertThat(BaseEntity.class.getMethod("setId", Long.class).isBridge()).isFalse();
        assertThat(BaseEntity.class.getMethod("setId", Object.class).isBridge()).isTrue();
        assertThat(Arrays.stream(BaseEntity.class.getDeclaredMethods()).anyMatch(method ->
                method.getName().equals("getId") && method.getReturnType() == Object.class
                        && method.isBridge() && method.isSynthetic())).isTrue();
        Record record = new Record();
        IdentifiableEntity<Long> identityView = record;
        identityView.setId(42L);
        assertThat(record.getId()).isEqualTo(42L);
        identityView.setId(null);
        assertThat(record.getId()).isNull();
    }

    /** Compare JDBC schema metadata using either baseline or migrated owner JARs. */
    public static void main(String[] args) throws Exception {
        openFactory();
        try (var session = factory.openSession()) {
            var lines = new ArrayList<String>();
            session.doWork(connection -> {
                var metadata = connection.getMetaData();
                for (String table : new String[]{"IDENTITY_RECORD", "IDENTITY_CHILD"}) {
                    try (var columns = metadata.getColumns(null, "PUBLIC", table, null)) {
                        while (columns.next()) lines.add("COLUMN\t" + table + "\t"
                                + columns.getString("COLUMN_NAME") + "\t" + columns.getString("TYPE_NAME")
                                + "\t" + columns.getInt("COLUMN_SIZE") + "\t" + columns.getInt("NULLABLE")
                                + "\t" + columns.getString("IS_AUTOINCREMENT"));
                    }
                    try (var keys = metadata.getPrimaryKeys(null, "PUBLIC", table)) {
                        while (keys.next()) lines.add("PK\t" + table + "\t" + keys.getString("COLUMN_NAME"));
                    }
                    try (var keys = metadata.getImportedKeys(null, "PUBLIC", table)) {
                        while (keys.next()) lines.add("FK\t" + table + "\t" + keys.getString("FKCOLUMN_NAME")
                                + "\t" + keys.getString("PKTABLE_NAME") + "\t" + keys.getString("PKCOLUMN_NAME"));
                    }
                }
            });
            lines.sort(String::compareTo);
            if (lines.isEmpty()) throw new AssertionError("Schema snapshot is empty");
            Files.write(Path.of(args[0]), lines);
        } finally {
            closeFactory();
        }
    }

    @Entity(name = "IdentityRecord")
    @Table(name = "identity_record")
    public static class Record extends BaseEntity {
        @Column(nullable = false)
        private String label;
    }

    @Entity(name = "IdentityChild")
    @Table(name = "identity_child")
    public static class Child extends BaseEntity {
        @ManyToOne(optional = false)
        @JoinColumn(name = "parent_id", nullable = false)
        private Record parent;
    }
}

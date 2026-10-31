package org.ipro.metadata;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class ManagedEntityTypesSnapshotTest {

    @Test
    void snapshotsOnePersistenceUnitInStableClassOrderAndPreservesJpaNames() {
        EntityType<Second.Same> second = entityType(Second.Same.class, "SecondJpaName");
        EntityType<First.Same> first = entityType(First.Same.class, "FirstJpaName");
        ManagedEntityTypes types = snapshot(second, first);

        assertThat(types.all()).containsExactly(First.Same.class, Second.Same.class);
        assertThat(types.entityName(First.Same.class)).isEqualTo("FirstJpaName");
        assertThat(types.entityName(Second.Same.class)).isEqualTo("SecondJpaName");
        assertThat(types.contains(First.Same.class)).isTrue();
        assertThat(types.contains(String.class)).isFalse();
    }

    @Test
    void independentPersistenceUnitsDoNotShareEntityTypes() {
        ManagedEntityTypes firstUnit = snapshot(entityType(First.Same.class, "FirstJpaName"));
        ManagedEntityTypes secondUnit = snapshot(entityType(Second.Same.class, "SecondJpaName"));

        assertThat(firstUnit.all()).containsExactly(First.Same.class);
        assertThat(secondUnit.all()).containsExactly(Second.Same.class);
        assertThat(firstUnit.asSet()).containsExactly(First.Same.class);
        assertThat(secondUnit.asSet()).containsExactly(Second.Same.class);
    }

    @Test
    void entityNameRejectsClassOutsideThePersistenceUnit() {
        ManagedEntityTypes types = snapshot(entityType(First.Same.class, "FirstJpaName"));

        assertThatThrownBy(() -> types.entityName(Second.Same.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is not managed by this persistence unit");
    }

    @Test
    void snapshotIncludesAnnotationlessStandardWithoutInventingARawAnnotation() {
        ManagedEntityTypes types = snapshot(
            entityType(AnnotationlessCatalog.class, "AnnotationlessCatalog"));

        assertThat(types.all()).containsExactly(AnnotationlessCatalog.class);
        assertThat(types.annotatedWith(EntityMetadata.class)).isEmpty();
    }

    private static ManagedEntityTypes snapshot(EntityType<?>... entityTypes) {
        Set<EntityType<?>> entities = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(entities, entityTypes);
        Metamodel metamodel = proxy(Metamodel.class, (ignored, method, args) -> {
            if (method.getName().equals("getEntities")) return entities;
            throw new UnsupportedOperationException(method.toString());
        });
        EntityManagerFactory factory = proxy(EntityManagerFactory.class, (ignored, method, args) -> {
            if (method.getName().equals("getMetamodel")) return metamodel;
            throw new UnsupportedOperationException(method.toString());
        });
        return new ManagedEntityTypes(factory);
    }

    @SuppressWarnings("unchecked")
    private static <T> EntityType<T> entityType(Class<T> javaType, String entityName) {
        return (EntityType<T>) Proxy.newProxyInstance(EntityType.class.getClassLoader(),
            new Class<?>[] {EntityType.class}, (ignored, method, args) -> switch (method.getName()) {
            case "getJavaType" -> javaType;
            case "getName" -> entityName;
            default -> throw new UnsupportedOperationException(method.toString());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> contract, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] {contract}, handler);
    }

    static final class First {
        @jakarta.persistence.Entity
        static class Same { }
    }

    static final class Second {
        @jakarta.persistence.Entity
        static class Same { }
    }

    @jakarta.persistence.Entity
    static class AnnotationlessCatalog extends StandardCatalogEntity { }
}

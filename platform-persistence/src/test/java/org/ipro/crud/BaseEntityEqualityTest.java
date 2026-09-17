package org.ipro.crud;

import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class BaseEntityEqualityTest {

    @Test
    void differentEntityTypesWithSameIdAreNotEqual() {
        FirstEntity first = entity(new FirstEntity(), 42L);
        SecondEntity second = entity(new SecondEntity(), 42L);

        assertThat(first).isNotEqualTo(second);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void sameEntityTypeAndIdHaveEqualStableHashCodes() {
        FirstEntity first = entity(new FirstEntity(), 42L);
        FirstEntity same = entity(new FirstEntity(), 42L);

        assertThat(first).isEqualTo(same);
        assertThat(first.hashCode()).isEqualTo(same.hashCode());
    }

    @Test
    void transientEntitiesAreEqualOnlyByIdentity() {
        FirstEntity first = new FirstEntity();
        FirstEntity other = new FirstEntity();

        assertThat(first).isEqualTo(first);
        assertThat(first).isNotEqualTo(other);
    }

    @Test
    void hibernateProxyAndEntityRemainSymmetric() {
        FirstEntity entity = entity(new FirstEntity(), 42L);
        FirstEntityProxy proxy = entity(new FirstEntityProxy(FirstEntity.class), 42L);

        assertThat(entity).isEqualTo(proxy);
        assertThat(proxy).isEqualTo(entity);
        assertThat(entity.hashCode()).isEqualTo(proxy.hashCode());
    }

    private static <T extends BaseEntity> T entity(T entity, long id) {
        entity.setId(id);
        return entity;
    }

    private static class FirstEntity extends BaseEntity {
    }

    private static final class SecondEntity extends BaseEntity {
    }

    private static final class FirstEntityProxy extends FirstEntity implements HibernateProxy {

        private final LazyInitializer initializer;

        private FirstEntityProxy(Class<?> persistentClass) {
            initializer = mock(LazyInitializer.class);
            doReturn(persistentClass).when(initializer).getPersistentClass();
        }

        @Override
        public Object writeReplace() {
            return this;
        }

        @Override
        public LazyInitializer getHibernateLazyInitializer() {
            return initializer;
        }
    }
}

package org.ipro.crud;

import org.ipro.identity.IdentifiableEntity;

import org.ipro.data.EntityDataAccessResolver;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C4.3/C4.7: выбор data handle — type-directed (ADR-0007 §1).
 *
 * <p>{@code STANDARD_ROOT} без application service получает default canonical handle вместо
 * отказа, но только там, где canonical path действительно разрешён. Типизированный сервис
 * находится по entity type (а не по выведенному из имени класса bean-name), две регистрации
 * на один тип останавливают контекст, а для типа без canonical handle сохраняется
 * диагностика с реальными вариантами — generic-путь не прячет настоящую причину.</p>
 */
class ServiceLocatorCanonicalFallbackTest {

    @EntityMetadata(listFormTitle = "canonical fixture")
    static class CanonicalFixture implements IdentifiableEntity {
        private Long id;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    /** Регистрация важна типом, а не поведением, поэтому тела намеренно пустые. */
    abstract static class FixtureService<T extends IdentifiableEntity>
            implements BaseService<T, Long> {

        @Override
        public T save(T entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public T create(T entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public T update(T entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<T> findById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<T> findAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<T> findAll(Pageable pageable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<T> search(String term) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<T> search(String term, Pageable pageable) {
            throw new UnsupportedOperationException();
        }
    }

    static class TypedFixtureService extends FixtureService<CanonicalFixture> {
    }

    static class SecondTypedFixtureService extends FixtureService<CanonicalFixture> {
    }

    @Test
    void typedServiceIsResolvedByEntityTypeNotByBeanName() {
        ServiceLocator locator = locator(Map.of("anythingAtAll", new TypedFixtureService()));

        assertThat(locator.findService(CanonicalFixture.class))
            .as("имя бина больше не выводится из имени класса — регистрация типизирована")
            .isInstanceOf(TypedFixtureService.class);
    }

    @Test
    void twoTypedServicesForOneTypeStopTheContext() {
        Map<String, BaseService> beans = new LinkedHashMap<>();
        beans.put("firstTypedService", new TypedFixtureService());
        beans.put("secondTypedService", new SecondTypedFixtureService());

        assertThatThrownBy(() -> locator(beans))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("More than one BaseService")
            .hasMessageContaining("CanonicalFixture");
    }

    @Test
    void fallsBackToCanonicalServiceWhenNoTypedServiceExists() {
        ServiceLocator locator = locator(Map.of());
        BaseService<CanonicalFixture, Long> canonical = canonicalService();
        EntityDataAccessResolver resolver = mock(EntityDataAccessResolver.class);
        when(resolver.<CanonicalFixture, Long>findService(CanonicalFixture.class))
            .thenReturn(Optional.of(canonical));
        locator.setDataAccessResolver(resolver);

        assertThat(locator.findService(CanonicalFixture.class)).isSameAs(canonical);
    }

    @Test
    void missingHandleDiagnosticNamesTheSupportedOptions() {
        ServiceLocator locator = locator(Map.of());
        EntityDataAccessResolver resolver = mock(EntityDataAccessResolver.class);
        when(resolver.<CanonicalFixture, Long>findService(CanonicalFixture.class))
            .thenReturn(Optional.empty());
        locator.setDataAccessResolver(resolver);

        assertThatThrownBy(() -> locator.findService(CanonicalFixture.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No BaseService found")
            .hasMessageContaining("Register a typed service bean")
            .hasMessageContaining("EntityCapabilityOverride");
    }

    @Test
    void withoutCanonicalPathTheConfiguredDiagnosticIsKept() {
        assertThatThrownBy(() -> locator(Map.of()).findService(CanonicalFixture.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No BaseService found");
    }

    @SuppressWarnings("unchecked")
    private static BaseService<CanonicalFixture, Long> canonicalService() {
        return mock(BaseService.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ServiceLocator locator(Map<String, ? extends BaseService> beans) {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(BaseService.class))
            .thenReturn((Map) new LinkedHashMap<>(beans));
        MetadataResolver metadataResolver = new MetadataResolver();
        SectionMetadataRegistry sections =
            new SectionMetadataRegistry("org.ipro.crud", metadataResolver);
        sections.afterPropertiesSet();
        ServiceLocator locator = new ServiceLocator(context, metadataResolver, sections);
        locator.afterSingletonsInstantiated();
        return locator;
    }
}

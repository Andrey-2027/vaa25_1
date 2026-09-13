package org.ipro.crud;

import org.ipro.data.EntityDataAccessResolver;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C4.3: {@code STANDARD_ROOT} без application service получает default canonical handle
 * вместо отказа, но только там, где canonical path действительно разрешён (ADR-0007 §1).
 * Для типа без canonical handle сохраняется прежняя диагностика «создайте service» —
 * generic-путь не прячет настоящую причину.
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

    @Test
    void fallsBackToCanonicalServiceWhenNoTypedBeanExists() {
        ServiceLocator locator = locator();
        BaseService<CanonicalFixture, Long> canonical = canonicalService();
        EntityDataAccessResolver resolver = mock(EntityDataAccessResolver.class);
        when(resolver.<CanonicalFixture, Long>findService(CanonicalFixture.class))
            .thenReturn(Optional.of(canonical));
        locator.setDataAccessResolver(resolver);

        assertThat(locator.findService(CanonicalFixture.class)).isSameAs(canonical);
    }

    @Test
    void keepsTheMissingServiceDiagnosticWhenTypeHasNoCanonicalHandle() {
        ServiceLocator locator = locator();
        EntityDataAccessResolver resolver = mock(EntityDataAccessResolver.class);
        when(resolver.<CanonicalFixture, Long>findService(CanonicalFixture.class))
            .thenReturn(Optional.empty());
        locator.setDataAccessResolver(resolver);

        assertThatThrownBy(() -> locator.findService(CanonicalFixture.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No service found")
            .hasMessageContaining("canonicalFixtureService");
    }

    @Test
    void withoutCanonicalPathTheOriginalDiagnosticIsKept() {
        assertThatThrownBy(() -> locator().findService(CanonicalFixture.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No service found");
    }

    @SuppressWarnings("unchecked")
    private static BaseService<CanonicalFixture, Long> canonicalService() {
        return mock(BaseService.class);
    }

    private static ServiceLocator locator() {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(anyString()))
            .thenThrow(new NoSuchBeanDefinitionException("c4-fixture has no typed bean"));
        MetadataResolver metadataResolver = new MetadataResolver();
        SectionMetadataRegistry sections =
            new SectionMetadataRegistry("org.ipro.crud", metadataResolver);
        sections.afterPropertiesSet();
        return new ServiceLocator(context, metadataResolver, sections);
    }
}

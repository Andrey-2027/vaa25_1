package org.ipro.crud.jpa;

import org.ipro.crud.IdentifiableEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-0007 §3: {@code ValidatedJpaCrudService} — internal-store adapter. Metadata-driven
 * root обязан идти через canonical path, поэтому подмена является ошибкой конфигурации
 * при старте, а не тихо потерянными RLS, fetch-планом и events.
 */
class ValidatedJpaCrudServiceGuardTest {

    @EntityMetadata(listFormTitle = "metadata root")
    static class MetadataDrivenFixture implements IdentifiableEntity {
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

    static class InternalStoreFixture implements IdentifiableEntity {
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

    static class MetadataDrivenService extends ValidatedJpaCrudService<MetadataDrivenFixture> {
        MetadataDrivenService() {
            super(null, null, null);
        }
    }

    static class InternalStoreService extends ValidatedJpaCrudService<InternalStoreFixture> {
        InternalStoreService() {
            super(null, null, null);
        }
    }

    @Test
    void metadataDrivenRootIsRejectedAtConstruction() {
        assertThatThrownBy(MetadataDrivenService::new)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MetadataDrivenFixture")
            .hasMessageContaining("canonical data path");
    }

    @Test
    void nonMetadataInternalStoreIsAccepted() {
        assertThat(new InternalStoreService()).isNotNull();
    }
}

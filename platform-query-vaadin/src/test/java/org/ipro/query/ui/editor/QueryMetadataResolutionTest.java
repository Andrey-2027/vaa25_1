package org.ipro.query.ui.editor;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class QueryMetadataResolutionTest {
    @EntityMetadata(displaySortFields = "missing")
    static class InvalidEntity { }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void invalidDeclaredMetadataCannotBecomeAnEmptyNodeOrOption() {
        var emf = mock(EntityManagerFactory.class);
        var metamodel = mock(Metamodel.class);
        EntityType entity = mock(EntityType.class);
        when(emf.getMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntities()).thenReturn(Set.of(entity));
        when(entity.getJavaType()).thenReturn(InvalidEntity.class);
        when(entity.getName()).thenReturn("InvalidEntity");
        var gate = mock(RlsReadGate.class);
        var user = mock(RlsCurrentUser.class);
        when(user.requireAuthenticatedUsername()).thenReturn("reader");
        when(gate.canRead(InvalidEntity.class, "reader")).thenReturn(true);
        var catalog = new QueryMetadataCatalogService(emf, new MetadataResolver(), gate, user);
        assertThatThrownBy(() -> catalog.roots(""))
            .hasMessageContaining(InvalidEntity.class.getName()).hasMessageContaining("displaySortFields");
        assertThatThrownBy(catalog::entityOptions)
            .hasMessageContaining(InvalidEntity.class.getName()).hasMessageContaining("missing");
    }
}

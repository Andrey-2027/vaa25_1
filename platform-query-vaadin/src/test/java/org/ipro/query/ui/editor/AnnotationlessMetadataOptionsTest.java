package org.ipro.query.ui.editor;

import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.ManagedEntityTypes;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.RlsCurrentUser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnnotationlessMetadataOptionsTest {
    @Entity static class BareCatalog extends StandardCatalogEntity { }
    @Entity static class Internal extends BaseEntity { }

    @Test void legacyEditorAndParameterOptionsUseTheSameEffectiveIntentAndReadGate() {
        var gate = mock(RlsReadGate.class);
        var user = mock(RlsCurrentUser.class);
        when(user.requireAuthenticatedUsername()).thenReturn("reader");
        when(gate.canRead(BareCatalog.class, "reader")).thenReturn(true);
        var catalog = new QueryMetadataCatalogService(
            new ManagedEntityTypes(List.of(BareCatalog.class, Internal.class)), new MetadataResolver(), gate, user);
        assertThat(catalog.entityOptions()).extracting(QueryMetadataCatalogService.EntityOption::className)
            .containsExactly(BareCatalog.class.getName());
        assertThat(catalog.roots("")).hasSize(1);
        when(gate.canRead(BareCatalog.class, "reader")).thenReturn(false);
        assertThat(catalog.entityOptions()).isEmpty();
        assertThat(catalog.roots("")).isEmpty();
    }
}

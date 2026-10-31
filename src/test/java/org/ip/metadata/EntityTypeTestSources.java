package org.ip.metadata;

import org.ipro.data.EntityClassificationContributor;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.register.RecorderTypeRegistry;
import org.ipro.register.RegisterRegistry;
import org.ipro.register.RegisterClassificationContributor;
import org.ipro.register.RecorderType;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;

/** Same validated register source as production for manually assembled full-PU fixtures. */
public final class EntityTypeTestSources {
    private EntityTypeTestSources() { }
    public static List<EntityClassificationContributor> registers(ManagedEntityCatalog managed, MetadataResolver resolver) {
        // UI-only fixture has no EntityManagerFactory. Use declared recorder codes;
        // real PU membership/identifier mapping is exercised by the backend and H2/PG suites.
        var recorders=mock(RecorderTypeRegistry.class);
        for (Class<?> type : managed.managedEntityClasses()) {
            var declaration=type.getAnnotation(RecorderType.class);
            if (declaration!=null) when(recorders.findCode(type)).thenReturn(Optional.of(declaration.value()));
        }
        return List.of(new RegisterClassificationContributor(new RegisterRegistry(managed,recorders)));
    }
    public static List<EntityClassificationContributor> registers(ManagedEntityCatalog managed,
            jakarta.persistence.EntityManagerFactory emf, MetadataResolver resolver) {
        return List.of(new RegisterClassificationContributor(new RegisterRegistry(managed,
            new RecorderTypeRegistry(managed,emf,resolver))));
    }
}

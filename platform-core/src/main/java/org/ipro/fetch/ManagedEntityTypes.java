package org.ipro.fetch;

import org.ipro.metadata.ManagedEntityCatalog;

import java.util.Objects;
import java.util.Comparator;
import java.util.List;

/**
 * Platform-internal read-only источник набора управляемых сущностей, общий для
 * FetchPlan и InstanceName registry (C3, см. ADR-0006).
 *
 * <p>Намеренно тонкий: единственная задача — один раз зафиксировать набор сущностей из
 * {@link ManagedEntityCatalog}, чтобы оба registry C3 не выполняли собственный classpath
 * scan и не расходились в составе. Это не публичный прикладной API: прикладной код
 * по-прежнему описывает сущность моделью и metadata.</p>
 */
public final class ManagedEntityTypes {

    private final List<Class<?>> classes;

    public ManagedEntityTypes(ManagedEntityCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        this.classes = catalog.managedEntityClasses().stream()
            .sorted(Comparator.comparing(Class::getName))
            .toList();
    }

    /** Все управляемые entity-классы текущего persistence unit. */
    public List<Class<?>> all() {
        return classes;
    }
}

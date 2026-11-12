package org.ipro.vaadin.explorer.ui;

import org.ipro.metadata.annotation.EntityKind;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Host decisions and user-action callbacks. No routes, roles, source roots or recipe versions. */
public record EntityExplorerRegistryActions(
        Function<Class<?>, ExplorerActionAvailability> cardAvailability, Consumer<Class<?>> openCard,
        Function<Class<?>, ExplorerActionAvailability> modifyAvailability, Consumer<Class<?>> modify,
        Function<EntityKind, ExplorerActionAvailability> createAvailability, Consumer<EntityKind> create,
        ExplorerActionAvailability diagnosticsAvailability, Runnable diagnostics) {
    public EntityExplorerRegistryActions {
        Objects.requireNonNull(cardAvailability); Objects.requireNonNull(openCard); Objects.requireNonNull(modifyAvailability);
        Objects.requireNonNull(modify); Objects.requireNonNull(createAvailability); Objects.requireNonNull(create);
        Objects.requireNonNull(diagnosticsAvailability); Objects.requireNonNull(diagnostics);
    }
    public static EntityExplorerRegistryActions none() {
        var absent = ExplorerActionAvailability.unavailable("Действие не подключено приложением");
        return new EntityExplorerRegistryActions(type -> absent, type -> {}, type -> absent, type -> {},
            kind -> ExplorerActionAvailability.unavailable(kind == EntityKind.PLAIN
                ? "Создание простых сущностей пока не поддерживается" : absent.reason()), kind -> {}, absent, () -> {});
    }
}

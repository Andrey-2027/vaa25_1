package org.ipro.form.action;

import java.util.Objects;

public record ActionResolvers(ActionRegistry registry, ActionContextProvider contexts,
                              ActionHandlerRegistry handlers) {
    public ActionResolvers {
        Objects.requireNonNull(registry);
        Objects.requireNonNull(contexts);
        Objects.requireNonNull(handlers);
    }

    public ActionResolver list(Class<?> type, String variant) {
        return new ActionResolver(registry, contexts, handlers, ActionSurface.LIST_TOOLBAR, type, variant);
    }

    public ActionResolver item(Class<?> type, String variant) {
        return new ActionResolver(registry, contexts, handlers, ActionSurface.ITEM_FOOTER, type, variant);
    }

    public ActionResolver navigation(Class<?> type) {
        return new ActionResolver(registry, contexts, handlers, ActionSurface.NAVIGATION, type, null);
    }
}

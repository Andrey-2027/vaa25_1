package org.ipro.form.action;

import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormType;
import java.util.function.Predicate;

public final class NavigationCommandAvailability {
    private final ActionResolvers actions;
    private final FormRegistry forms;
    private final Predicate<Class<?>> canRead;

    public NavigationCommandAvailability(ActionResolvers actions, FormRegistry forms, Predicate<Class<?>> canRead) {
        this.actions = actions;
        this.forms = forms;
        this.canRead = canRead;
    }

    public boolean hasListVariant(Class<?> type, String variant) {
        return forms.registrationsOf(type).stream()
            .anyMatch(form -> form.formType() == FormType.LIST && variant.equals(form.variant()));
    }

    public boolean hasRequiredContext(Class<?> type, String variant) {
        return forms.resolveListContextFilters(type, variant).stream().anyMatch(ContextFilterField::required);
    }

    public ActionDecision decide(Class<?> type, String action, String variant) {
        if (action.isEmpty()) {
            return canRead.test(type)
                ? ActionDecision.allowed()
                : ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE, "Нет права на чтение");
        }
        if (CrudAction.CREATE.id().value().equals(action)) {
            return actions.list(type, null).decide(CrudAction.CREATE, null, !hasRequiredContext(type, null));
        }
        var resolver = actions.navigation(type);
        return resolver.definitions().stream()
            .filter(definition -> definition.id().value().equals(action) && resolver.handler(definition).isPresent())
            .findFirst().map(definition -> resolver.decide(definition, null, true))
            .orElseGet(() -> ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE, "Команда недоступна"));
    }
}

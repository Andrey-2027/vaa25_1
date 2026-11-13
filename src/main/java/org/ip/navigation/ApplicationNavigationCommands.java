package org.ip.navigation;

import org.ipro.data.DataOperation;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.ActionInvocation;
import org.ipro.form.action.ActionResolvers;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.action.NavigationCommandAvailability;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.navigation.NavigationFunction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ApplicationNavigationCommands {
    private final NavigationCommandAvailability availability;
    private final ActionResolvers actions;
    private final EntityDescriptorCatalog descriptors;

    public ApplicationNavigationCommands(ActionResolvers actions, NavigationCommandAvailability availability,
                                         EntityDescriptorCatalog descriptors) {
        this.availability = availability;
        this.actions = actions;
        this.descriptors = descriptors;
    }

    boolean supportsCreate(Class<?> type) {
        return descriptors.descriptorOf(type).capabilities().allows(DataOperation.CREATE)
            && actions.list(type, null).definition(CrudAction.CREATE).filter(ActionDefinition::visible).isPresent()
            && !availability.hasRequiredContext(type, null);
    }

    List<ActionDefinition> navigationActions(Class<?> type) {
        var resolver = actions.navigation(type);
        return resolver.definitions().stream()
            .filter(ActionDefinition::visible)
            .filter(action -> resolver.handler(action).isPresent())
            .toList();
    }

    public ActionDecision decide(NavigationFunction function) {
        return switch (function.kind()) {
            case LIST, LIST_VARIANT -> availability.decide(function.entityClass(), "", function.variant());
            case CREATE -> availability.decide(function.entityClass(), CrudAction.CREATE.id().value(), null);
            case ACTION -> availability.decide(function.entityClass(), function.actionId(), null);
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public ActionDecision execute(NavigationFunction function, FormNavigator navigator) {
        ActionDecision decision = decide(function);
        if (!decision.actionable()) return decision;

        Class type = function.entityClass();
        switch (function.kind()) {
            case LIST, LIST_VARIANT -> navigator.openListForm(type, function.variant(), null);
            case CREATE -> navigator.openItemForm(type, null, null, null, null);
            case ACTION -> actions.handlers()
                .handlerOf(ActionSurface.NAVIGATION, type, null, ActionId.of(function.actionId()))
                .orElseThrow().execute(new ActionInvocation(type, null, null, navigator, null, Map.of(), Map.of()));
        }
        return decision;
    }
}

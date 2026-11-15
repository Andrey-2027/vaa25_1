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
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.navigation.NavigationCatalogProvider;
import org.ipro.navigation.NavigationCommand;
import org.ipro.navigation.NavigationNode;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ApplicationNavigationCommands {
    private final NavigationCommandAvailability availability;
    private final ActionResolvers actions;
    private final NavigationCatalogProvider catalogs;
    private final FormRouteCatalog routes;
    private final EntityDescriptorCatalog descriptors;

    public ApplicationNavigationCommands(ActionResolvers actions, NavigationCommandAvailability availability,
                                         NavigationCatalogProvider catalogs,
                                         FormRouteCatalog routes, EntityDescriptorCatalog descriptors) {
        this.availability = availability;
        this.actions = actions;
        this.catalogs = catalogs;
        this.routes = routes;
        this.descriptors = descriptors;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        catalogs.get().roots().forEach(this::validate);
    }

    private void validate(NavigationNode node) {
        node.commands().forEach(this::validate);
        node.children().forEach(this::validate);
    }

    void validate(NavigationCommand command) {
        Class<?> type = command.entityClass();
        var descriptor = descriptors.descriptorOf(type);
        require(command, routes.find(type).isPresent() && descriptor.jpaManaged(),
            "entity must be managed and published");
        if (command.action().isEmpty()) {
            require(command, availability.hasListVariant(type, command.variant()),
                "list variant is not registered");
            require(command, !availability.hasRequiredContext(type, command.variant()), "list variant requires context");
        } else if (isCreate(command)) {
            require(command, descriptor.capabilities().allows(DataOperation.CREATE), "entity does not support CREATE");
            require(command, actions.list(type, null).definition(CrudAction.CREATE)
                .filter(ActionDefinition::visible).isPresent(),
                "list CREATE is absent or suppressed");
            require(command, !availability.hasRequiredContext(type, null), "list CREATE requires context");
        } else {
            var resolver = actions.navigation(type);
            require(command, resolver.definitions().stream().anyMatch(definition -> definition.id().value()
                .equals(command.action()) && resolver.handler(definition).isPresent()), "NAVIGATION action is not registered");
        }
    }

    private static void require(NavigationCommand command, boolean condition, String reason) {
        if (!condition) {
            throw new IllegalStateException("Invalid navigation command " + command.key() + ": " + reason);
        }
    }

    public ActionDecision decide(NavigationCommand command) {
        return availability.decide(command.entityClass(), command.action(), command.variant());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public ActionDecision execute(NavigationCommand command, FormNavigator navigator) {
        ActionDecision decision = decide(command);
        if (!decision.actionable()) {
            return decision;
        }
        Class type = command.entityClass();
        if (command.action().isEmpty()) {
            navigator.openListForm(type, command.variant(), null);
        } else if (isCreate(command)) {
            navigator.openItemForm(type, null, null, null, null);
        } else {
            actions.handlers()
                .handlerOf(ActionSurface.NAVIGATION, type, null, ActionId.of(command.action()))
                .orElseThrow().execute(new ActionInvocation(type, null, null, navigator, null, Map.of(), Map.of()));
        }
        return decision;
    }

    private static boolean isCreate(NavigationCommand command) {
        return CrudAction.CREATE.id().value().equals(command.action());
    }
}

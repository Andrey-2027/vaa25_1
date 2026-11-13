package org.ip.navigation;

import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.FormRouteKind;
import org.ipro.form.link.PublishedFormRoute;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.Subsystem;
import org.ipro.navigation.NavigationEntityKeys;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationFunctions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ApplicationNavigationFunctions implements NavigationFunctions {
    private final FormRouteCatalog routes;
    private final EntityDescriptorCatalog descriptors;
    private final MetadataResolver metadata;
    private final ApplicationNavigationCommands commands;

    public ApplicationNavigationFunctions(FormRouteCatalog routes, EntityDescriptorCatalog descriptors,
                                          MetadataResolver metadata,
                                          ApplicationNavigationCommands commands) {
        this.routes = routes;
        this.descriptors = descriptors;
        this.metadata = metadata;
        this.commands = commands;
    }

    @Override
    public List<NavigationFunction> all() {
        return routes.all().stream()
            .filter(route -> canList(route.entityClass()))
            .flatMap(route -> functions(route).stream())
            .toList();
    }

    @Override
    public Optional<NavigationFunction> find(String key) {
        return all().stream().filter(function -> function.key().equals(key)).findFirst();
    }

    private boolean canList(Class<?> type) {
        return descriptors.descriptorOf(type).capabilities().readScenarios().contains(FetchScenario.LIST);
    }

    private List<NavigationFunction> functions(PublishedFormRoute route) {
        String entityKey = route.entityKey();
        Class<?> type = route.entityClass();
        var info = metadata.resolve(type);
        Class<?> area = info.getSubsystem();
        String areaKey = area == Subsystem.NoSubsystem.class ? null : NavigationEntityKeys.derive(area);
        var result = new ArrayList<NavigationFunction>();
        result.add(new NavigationFunction("list:" + entityKey, type, NavigationFunction.Kind.LIST,
            info.getListFormTitle(), info.getIcon(), info.getOrder(), areaKey));
        route.variants(FormRouteKind.LIST).stream()
            .filter(variant -> !variant.equals("default"))
            .sorted()
            .forEach(variant -> result.add(new NavigationFunction("list:" + entityKey + ":" + variant,
                type, NavigationFunction.Kind.LIST_VARIANT, info.getListFormTitle() + " — " + variant,
                info.getIcon(), info.getOrder(), areaKey, variant, null)));
        if (commands.supportsCreate(type)) {
            result.add(new NavigationFunction("create:" + entityKey, type, NavigationFunction.Kind.CREATE,
                "Создать: " + info.getItemFormTitle(), info.getIcon(), info.getOrder(), areaKey));
        }
        for (var action : commands.navigationActions(type)) {
            String id = action.id().value();
            result.add(new NavigationFunction("action:" + entityKey + ":" + id, type, NavigationFunction.Kind.ACTION,
                action.title() == null ? id : action.title(), action.iconName() == null ? "" : action.iconName(),
                action.order(), areaKey, null, id));
        }
        return result;
    }
}

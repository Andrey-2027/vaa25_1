package org.ip.navigation;

import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.PublishedFormRoute;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.Subsystem;
import org.ipro.navigation.NavigationEntityKeys;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationFunctions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ApplicationNavigationFunctions implements NavigationFunctions {
    private final FormRouteCatalog routes;
    private final EntityDescriptorCatalog descriptors;
    private final MetadataResolver metadata;

    public ApplicationNavigationFunctions(FormRouteCatalog routes, EntityDescriptorCatalog descriptors,
                                          MetadataResolver metadata) {
        this.routes = routes;
        this.descriptors = descriptors;
        this.metadata = metadata;
    }

    @Override
    public List<NavigationFunction> all() {
        return routes.all().stream()
            .filter(route -> canList(route.entityClass()))
            .map(this::baseList)
            .toList();
    }

    @Override
    public Optional<NavigationFunction> find(String key) {
        return all().stream().filter(function -> function.key().equals(key)).findFirst();
    }

    private boolean canList(Class<?> type) {
        return descriptors.descriptorOf(type).capabilities().readScenarios().contains(FetchScenario.LIST);
    }

    private NavigationFunction baseList(PublishedFormRoute route) {
        String entityKey = route.entityKey();
        Class<?> type = route.entityClass();
        var info = metadata.resolve(type);
        Class<?> area = info.getSubsystem();
        String areaKey = area == Subsystem.NoSubsystem.class ? null : NavigationEntityKeys.derive(area);
        return new NavigationFunction("list:" + entityKey, type, NavigationFunction.Kind.LIST,
            info.getListFormTitle(), info.getIcon(), info.getOrder(), areaKey);
    }
}

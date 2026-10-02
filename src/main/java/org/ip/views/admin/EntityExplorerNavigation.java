package org.ip.views.admin;

import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.ipro.form.link.EntityExplorerAddress;
import org.ipro.form.link.EntityStructureNavigation;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.PublishedFormRoute;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Общий UI-local adapter структуры: правило доступа, снимок Explorer и текущий route host. */
@SpringComponent
@UIScope
public class EntityExplorerNavigation implements EntityStructureNavigation {

    private record Destination(Class<?> type, String anchor, String detail) {}
    private record Resolution(Destination destination, String refusal) {}

    private final EntityExplorerAccess access;
    private final FormRouteCatalog routes;
    private final Supplier<ExplorerTreeModel.CatalogView> catalog;
    private final java.util.function.Consumer<String> detail;
    private Object hostOwner;
    private BiFunction<Class<?>, String, Boolean> host;

    @org.springframework.beans.factory.annotation.Autowired
    public EntityExplorerNavigation(EntityExplorerAccess access, FormRouteCatalog routes,
                                     ObjectProvider<EntityExplorerView> views) {
        this(access, routes, () -> views.getObject().structureCatalog(),
            text -> views.getObject().showStructureDetail(text));
    }

    EntityExplorerNavigation(EntityExplorerAccess access, FormRouteCatalog routes,
                               Supplier<ExplorerTreeModel.CatalogView> catalog,
                               java.util.function.Consumer<String> detail) {
        this.access = Objects.requireNonNull(access);
        this.routes = Objects.requireNonNull(routes);
        this.catalog = Objects.requireNonNull(catalog);
        this.detail = Objects.requireNonNull(detail);
    }

    /** Host принадлежит этому UI; поздний detach прежнего layout не снимает новую привязку. */
    public void bindHost(Object owner, BiFunction<Class<?>, String, Boolean> open) {
        hostOwner = Objects.requireNonNull(owner);
        host = Objects.requireNonNull(open);
    }

    public void unbindHost(Object owner) {
        if (hostOwner == owner) {
            hostOwner = null;
            host = null;
        }
    }

    @Override
    public Availability availability(Class<?> type) {
        if (!access.allows()) {
            return Availability.unavailable(EntityExplorerAccess.REFUSAL_TEXT);
        }
        Resolution resolved = resolve(type, null);
        return resolved.destination() == null ? Availability.unavailable(resolved.refusal())
            : Availability.allowed();
    }

    @Override
    public Optional<String> link(Class<?> type) {
        if (!access.allows()) {
            return Optional.empty();
        }
        Resolution resolved = resolve(type, null);
        return resolved.destination() == null ? Optional.empty() : address(resolved.destination());
    }

    @Override
    public OpenResult open(Class<?> type, String anchor) {
        if (!access.allows()) {
            return new OpenResult.Unavailable(EntityExplorerAccess.REFUSAL_TEXT);
        }
        if (host == null) {
            return new OpenResult.Unavailable("В текущем UI нет рабочей области для Entity Explorer");
        }
        Resolution resolved = resolve(type, anchor);
        if (resolved.destination() == null) {
            return new OpenResult.Unavailable(resolved.refusal());
        }
        Destination target = resolved.destination();
        if (!access.allows() || !Boolean.TRUE.equals(host.apply(target.type(), target.anchor()))) {
            return new OpenResult.Unavailable(EntityExplorerAccess.REFUSAL_TEXT);
        }
        if (!target.detail().isEmpty()) {
            detail.accept(target.detail());
        }
        return new OpenResult.Opened(target.type(), address(target));
    }

    private Optional<String> address(Destination target) {
        return routes.find(target.type()).map(PublishedFormRoute::entityKey)
            .map(key -> EntityExplorerAddress.format(key, target.anchor()));
    }

    private Resolution resolve(Class<?> type, String anchor) {
        if (type == null || anchor != null && CardAnchor.of(anchor).isEmpty()) {
            return refused(EntityExplorerAccess.REFUSAL_TEXT);
        }
        ExplorerTreeModel.CatalogView facts;
        try {
            facts = catalog.get();
        } catch (RuntimeException unavailable) {
            return refused("Сведения о структуре недоступны: " + unavailable.getClass().getSimpleName());
        }
        Optional<ExplorerSnapshot.Entry> root = facts.roots().stream()
            .filter(entry -> entry.type().equals(type)).findFirst();
        if (root.isPresent()) {
            return new Resolution(new Destination(type, anchor, ""), "");
        }
        List<ExplorerSnapshot.OwnedSection> owners = facts.roots().stream()
            .flatMap(entry -> facts.sectionsOf(entry.type()).stream())
            .filter(section -> section.rowType().equals(type)).toList();
        long count = owners.stream().map(ExplorerSnapshot.OwnedSection::ownerType).distinct().count();
        if (count == 0) {
            return refused("Тип отсутствует в каталоге или подтверждённая секция-владелец не найдена");
        }
        if (count != 1) {
            return refused("Неоднозначный владелец табличной части; выберите подтверждённую секцию");
        }
        String names = owners.stream().map(section -> section.label().value() + " (" + type.getSimpleName() + ")")
            .distinct().collect(java.util.stream.Collectors.joining(", "));
        return new Resolution(new Destination(owners.get(0).ownerType(), "fields/table-sections", names), "");
    }

    private static Resolution refused(String reason) {
        return new Resolution(null, reason);
    }
}

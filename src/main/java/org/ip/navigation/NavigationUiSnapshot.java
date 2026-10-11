package org.ip.navigation;

import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.ipro.navigation.NavigationCatalog;
import org.ipro.navigation.NavigationProfile;
import org.ipro.navigation.NavigationProfileReader;
import org.ipro.navigation.NavigationResolver;
import org.ipro.navigation.NavigationTree;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One immutable projection (or load failure) per UI, shared by the shell and its tiles. */
@SpringComponent
@UIScope
public class NavigationUiSnapshot {
    public static final String LOAD_ERROR = "Не удалось загрузить меню. Обновите страницу или обратитесь к администратору.";
    private static final Logger log = LoggerFactory.getLogger(NavigationUiSnapshot.class);
    private final boolean enabled;
    private final Supplier<NavigationTree> loader;
    private boolean loaded;
    private NavigationTree tree;

    @Autowired
    public NavigationUiSnapshot(@Value("${platform.navigation.enabled:false}") boolean enabled,
                                ObjectProvider<NavigationCatalog> catalogs,
                                ObjectProvider<NavigationResolver> resolvers,
                                ObjectProvider<NavigationProfileReader> profiles,
                                RlsReadGate readGate, RlsCurrentUser currentUser) {
        this.enabled = enabled;
        // Missing composition is a wiring error, never a legacy-mode fallback.
        if (enabled) {
            var catalog = catalogs.getObject();
            var resolver = resolvers.getObject();
            var reader = profiles.getObject();
            loader = () -> {
                var profile = reader.currentProfile();
                String username = currentUser.requireAuthenticatedUsername();
                return resolver.resolve(catalog, profile.map(NavigationProfile::overrides).orElseGet(Map::of),
                    type -> readGate.canRead(type, username));
            };
        } else {
            loader = null;
        }
    }

    NavigationUiSnapshot(Supplier<NavigationTree> loader) {
        this.enabled = true;
        this.loader = loader;
    }

    public boolean enabled() { return enabled; }

    public void appendMenu(SideNav menu, Consumer<NavigationTree.Node> open) {
        if (!enabled) throw new IllegalStateException("Navigation profiles are disabled");
        if (!loaded) {
            try {
                tree = loader.get();
            } catch (RuntimeException failure) {
                log.error("Cannot load navigation for the current UI", failure);
            }
            loaded = true;
        }
        if (tree == null) {
            var error = new SideNavItem(LOAD_ERROR);
            error.setEnabled(false);
            menu.addItem(error);
        } else {
            tree.roots().forEach(node -> menu.addItem(menuItem(node, open)));
        }
    }

    private SideNavItem menuItem(NavigationTree.Node node, Consumer<NavigationTree.Node> open) {
        var item = new SideNavItem(node.title());
        if (!node.icon().isEmpty()) {
            try { item.setPrefixComponent(VaadinIcon.valueOf(node.icon()).create()); }
            catch (IllegalArgumentException ignored) { /* Same fallback as the legacy menu. */ }
        }
        item.getElement().addEventListener("click", event -> open.accept(node));
        node.children().forEach(child -> item.addItem(menuItem(child, open)));
        return item;
    }
}

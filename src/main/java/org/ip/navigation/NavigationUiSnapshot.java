package org.ip.navigation;

import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.ipro.navigation.NavigationCatalogProvider;
import org.ipro.navigation.NavigationProfile;
import org.ipro.navigation.NavigationProfileReader;
import org.ipro.navigation.NavigationResolver;
import org.ipro.navigation.NavigationTree;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One immutable projection (or load failure) per UI, shared by the shell and its tiles. */
@SpringComponent
@UIScope
public class NavigationUiSnapshot {
    public static final String LOAD_ERROR = "Не удалось загрузить меню. Обновите страницу или обратитесь к администратору.";
    private static final Logger log = LoggerFactory.getLogger(NavigationUiSnapshot.class);
    private final Supplier<NavigationTree> loader;
    private boolean loaded;
    private NavigationTree tree;

    @Autowired
    public NavigationUiSnapshot(NavigationCatalogProvider catalogs,
                                NavigationResolver resolver,
                                NavigationProfileReader profiles,
                                RlsReadGate readGate, RlsCurrentUser currentUser) {
        this.loader = () -> {
            var profile = profiles.currentProfile();
            String username = currentUser.requireAuthenticatedUsername();
            return resolver.resolve(catalogs.get(), profile.map(NavigationProfile::overrides).orElseGet(Map::of),
                type -> readGate.canRead(type, username));
        };
    }

    NavigationUiSnapshot(Supplier<NavigationTree> loader) {
        this.loader = loader;
    }

    /**
     * Adds the top-level sections to the menu, without nested items: the section page lists what is
     * inside. A load failure is a disabled line in place of the sections.
     */
    public void appendMenu(SideNav menu, Consumer<NavigationTree.Node> open) {
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
            catch (IllegalArgumentException ignored) { /* An unknown icon name shows no icon. */ }
        }
        item.getElement().addEventListener("click", event -> open.accept(node));
        return item;
    }
}

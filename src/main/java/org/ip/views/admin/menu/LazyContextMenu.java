package org.ip.views.admin.menu;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import tools.jackson.databind.node.ObjectNode;

import java.util.function.Predicate;

/**
 * A menu that opens on click and fills itself just before it opens, so a row of a grid does not carry the
 * items of its menu until the administrator asks for them. The filler returns {@code false} to keep the menu closed.
 */
public final class LazyContextMenu extends ContextMenu {
    private final Predicate<ContextMenu> filler;

    public LazyContextMenu(Component target, Predicate<ContextMenu> filler) {
        super(target);
        this.filler = filler;
        setOpenOnClick(true);
    }

    @Override
    protected boolean onBeforeOpenMenu(ObjectNode eventDetail) {
        removeAll();
        return filler.test(this);
    }
}

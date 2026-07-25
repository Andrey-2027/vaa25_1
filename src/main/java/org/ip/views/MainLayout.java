package org.ip.views;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinServletRequest;
import jakarta.annotation.security.PermitAll;
import org.ip.form.coordinator.FormCoordinator;
import org.ip.metadata.SubsystemNode;
import org.ip.metadata.SubsystemRegistry;
import org.ip.views.directory.WorkshopListView;
import org.ip.views.forms.WorkshopForm;
import org.ip.views.workspace.SubsystemHomeView;
import org.ip.views.workspace.Workspace;
import org.ip.views.workspace.WorkspaceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

@Route("")
@PageTitle("Vaa25_1")
@PermitAll
public class MainLayout extends AppLayout {

    private final Workspace workspace;
    private final SubsystemRegistry subsystemRegistry;
    private final FormCoordinator coordinator;

    @Autowired
    public MainLayout(WorkspaceManager workspaceManager,
                      SubsystemRegistry subsystemRegistry,
                      FormCoordinator coordinator) {
        this.subsystemRegistry = subsystemRegistry;
        this.coordinator = coordinator;
        workspace = new Workspace(workspaceManager);
        setContent(workspace);
        createHeader();
        createDrawer();
        openHome();
    }

    private void openHome() {
        workspace.open(MainView.class, "home", "Главная", v -> {});
    }

    private void createHeader() {
        H1 logo = new H1("Vaa25_1");
        logo.addClassNames("text-l", "m-m");

        Button logoutButton = new Button("Logout", new Icon(VaadinIcon.SIGN_OUT), e -> {
            VaadinServletRequest request = (VaadinServletRequest) com.vaadin.flow.server.VaadinService.getCurrentRequest();
            new SecurityContextLogoutHandler().logout(request, null, null);
        });

        HorizontalLayout header = new HorizontalLayout(
                new DrawerToggle(),
                logo,
                logoutButton
        );
        header.setWidth("100%");
        header.expand(logo);
        header.addClassNames("py-xs", "px-m");
        header.getStyle().set("align-items", "center");

        addToNavbar(header);
    }

    private void createDrawer() {
        SideNav nav = new SideNav();

        SideNavItem homeItem = new SideNavItem("Главная");
        homeItem.setPrefixComponent(new Icon(VaadinIcon.HOME));
        homeItem.getElement().addEventListener("click", e -> workspace.open(MainView.class, "home", "Главная", v -> {}));
        nav.addItem(homeItem);

        for (SubsystemNode root : subsystemRegistry.getRoots()) {
            nav.addItem(buildNavItem(root));
        }

        SideNavItem legacyItem = new SideNavItem("Справочники (legacy)");
        legacyItem.setPrefixComponent(new Icon(VaadinIcon.BOOK));
        SideNavItem workshopsItem = new SideNavItem("Цеха");
        workshopsItem.getElement().addEventListener("click", e ->
                workspace.open(WorkshopListView.class, "workshops", "Цеха", v -> {
                    v.setOnEdit(id -> {
                        String entryId = id != null ? "workshop-" + id : "workshop-new";
                        workspace.open(WorkshopForm.class, entryId,
                                id != null ? "Цех #" + id : "Новый цех", f -> {
                                    f.editEntity(id);
                                    f.setOnClose(() -> workspace.close(entryId));
                                    f.setAfterSave(v::refreshGrid);
                                });
                    });
                }));
        legacyItem.addItem(workshopsItem);
        nav.addItem(legacyItem);

        addToDrawer(nav);
    }

    private SideNavItem buildNavItem(SubsystemNode node) {
        SideNavItem item = new SideNavItem(node.getTitle());
        if (!node.getIcon().isEmpty()) {
            try {
                item.setPrefixComponent(new Icon(VaadinIcon.valueOf(node.getIcon())));
            } catch (IllegalArgumentException ignored) {
            }
        }
        item.getElement().addEventListener("click", e -> openSubsystem(node));

        for (SubsystemNode child : node.getChildren()) {
            item.addItem(buildNavItem(child));
        }
        return item;
    }

    private void openSubsystem(SubsystemNode node) {
        String tabId = "subsystem-" + node.getMarkerClass().getSimpleName();
        workspace.open(SubsystemHomeView.class, tabId, node.getTitle(),
            (SubsystemHomeView v) -> v.init(node, workspace));
    }
}

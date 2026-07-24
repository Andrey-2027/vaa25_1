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
import org.ip.views.directory.NomenclatureView;
import org.ip.views.directory.UnitView;
import org.ip.views.directory.WorkshopListView;
import org.ip.views.forms.WorkshopForm;
import org.ip.views.document.ReceivingDocumentView;
import org.ip.views.workspace.Workspace;
import org.ip.views.workspace.WorkspaceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

@Route("")
@PageTitle("Vaa25_1")
@PermitAll
public class MainLayout extends AppLayout {

    private final Workspace workspace;

    @Autowired
    public MainLayout(WorkspaceManager workspaceManager) {
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

        SideNavItem directoryItem = new SideNavItem("Справочники");
        directoryItem.setPrefixComponent(new Icon(VaadinIcon.BOOK));

        SideNavItem unitsItem = new SideNavItem("Единицы Измерения");
        unitsItem.getElement().addEventListener("click", e -> workspace.open(UnitView.class, "units", "Единицы Измерения", v -> {}));
        directoryItem.addItem(unitsItem);

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
        directoryItem.addItem(workshopsItem);

        SideNavItem nomenItem = new SideNavItem("Номенклатура");
        nomenItem.getElement().addEventListener("click", e -> workspace.open(NomenclatureView.class, "nomenclature", "Номенклатура", v -> {}));
        directoryItem.addItem(nomenItem);

        nav.addItem(directoryItem);

        SideNavItem documentItem = new SideNavItem("Документы");
        documentItem.setPrefixComponent(new Icon(VaadinIcon.FILE_TEXT));

        SideNavItem docsItem = new SideNavItem("Приемно-сдаточные накладные");
        docsItem.getElement().addEventListener("click", e -> workspace.open(ReceivingDocumentView.class, "receiving-docs", "Приемно-сдаточные накладные", v -> {}));
        documentItem.addItem(docsItem);

        nav.addItem(documentItem);

        addToDrawer(nav);
    }
}

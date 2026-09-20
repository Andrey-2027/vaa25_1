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
import com.vaadin.flow.spring.annotation.SpringComponent;
import jakarta.annotation.security.PermitAll;
import org.ipro.vaadin.search.GlobalSearchHeader;
import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.ip.views.admin.AdminView;
import org.ip.views.admin.DiagnosticsView;
import org.ip.views.admin.EntityExplorerView;
import org.ip.views.admin.SubsystemStructureView;
import org.ip.views.directory.WorkshopListView;
import org.ip.views.preferences.DensityToggle;
import org.ip.views.preferences.UserPreferencesStore;
import org.ip.views.forms.WorkshopForm;
import org.ip.views.workspace.SubsystemHomeView;
import org.ip.views.workspace.Workspace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

/**
 * Корневой layout приложения: шапка, боковое меню и рабочая область с вкладками.
 *
 * <p>D3.5.3-fix: scope объявлен явно — {@code prototype}, а не «как получится». Раньше класса не
 * было среди бинов, и Vaadin создавал его через {@code AutowireCapableBeanFactory.createBean};
 * «пер-навигация» была следствием фолбэка инстанциатора, а не контрактом. Достаточно было
 * добавить {@code @SpringComponent} (как у остальных вьюх), чтобы layout молча стал синглтоном и
 * захватил UI-scoped {@code WorkspaceManager} первого UI. Забор
 * {@code FormCoordinatorScopeGuardTest} видит этот класс как бин и падает на такой правке.</p>
 *
 * <p>Навигационный контракт здесь больше не внедряется: он был не нужен — все переходы идут
 * через {@code Workspace}, а навигацию открывает тот, кто владеет своим UI. Сама
 * {@code Workspace} — UI-scoped бин (D3.5.3-fix), а не объект, созданный вручную: вкладки
 * принадлежат UI, и передавать их куда-либо через сеттеры больше не нужно.</p>
 */
@Route("")
@PageTitle("Vaa25_1")
@PermitAll
@SpringComponent
@Scope("prototype")
public class MainLayout extends AppLayout {

    private final Workspace workspace;
    private final SubsystemRegistry subsystemRegistry;
    private final UserPreferencesStore preferencesStore;
    private final GlobalSearchHeader globalSearchHeader;

    @Autowired
    public MainLayout(Workspace workspace,
                      SubsystemRegistry subsystemRegistry,
                      UserPreferencesStore preferencesStore,
                      GlobalSearchHeader globalSearchHeader) {
        this.subsystemRegistry = subsystemRegistry;
        this.preferencesStore = preferencesStore;
        this.globalSearchHeader = globalSearchHeader;
        this.workspace = workspace;
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
                globalSearchHeader,
                new DensityToggle(preferencesStore),
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

        if (isAdmin()) {
            SideNavItem diagnosticsItem = new SideNavItem("Диагностика");
            diagnosticsItem.setPrefixComponent(new Icon(VaadinIcon.STETHOSCOPE));
            diagnosticsItem.getElement().addEventListener("click", e ->
                    workspace.open(DiagnosticsView.class, "diagnostics",
                            "Диагностика", v -> {}));
            nav.addItem(diagnosticsItem);

            SideNavItem adminItem = new SideNavItem("Администрирование");
            adminItem.setPrefixComponent(new Icon(VaadinIcon.SHIELD));
            adminItem.getElement().addEventListener("click", e ->
                    workspace.open(AdminView.class, "admin",
                            "Администрирование", v -> {}));
            nav.addItem(adminItem);

            SideNavItem explorerItem = new SideNavItem("Структура сущностей");
            explorerItem.setPrefixComponent(new Icon(VaadinIcon.SITEMAP));
            explorerItem.getElement().addEventListener("click", e ->
                    workspace.open(EntityExplorerView.class, "entity-explorer",
                            "Структура сущностей", v -> v.init()));
            nav.addItem(explorerItem);

            SideNavItem subsystemItem = new SideNavItem("Структура подсистем");
            subsystemItem.setPrefixComponent(new Icon(VaadinIcon.FILE_TREE));
            subsystemItem.getElement().addEventListener("click", e ->
                    workspace.open(SubsystemStructureView.class, "subsystem-structure",
                            "Структура подсистем", v -> v.init()));
            nav.addItem(subsystemItem);
        }

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
            (SubsystemHomeView v) -> v.init(node));
    }

    private static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }
}

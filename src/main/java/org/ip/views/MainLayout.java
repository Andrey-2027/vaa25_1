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
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.router.BeforeLeaveObserver;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.spring.annotation.SpringComponent;
import jakarta.annotation.security.PermitAll;
import org.ipro.form.host.FormRouteUrlBridge;
import org.ipro.form.host.RouteStatePage;
import org.ipro.form.link.EntityExplorerAddress;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.FormRouteOpener;
import org.ipro.form.link.OpenResult;
import org.ipro.form.link.PublishedFormRoute;
import org.ipro.vaadin.search.GlobalSearchHeader;
import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.ip.views.admin.AdminView;
import org.ip.views.admin.DiagnosticsView;
import org.ip.views.admin.EntityExplorerAccess;
import org.ip.views.admin.EntityExplorerView;
import org.ip.views.admin.SubsystemStructureView;
import org.ip.views.directory.WorkshopListView;
import org.ip.views.preferences.DensityToggle;
import org.ip.views.preferences.UserPreferencesStore;
import org.ip.views.forms.WorkshopForm;
import org.ip.views.workspace.SubsystemHomeView;
import org.ip.views.workspace.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

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
 *
 * <p><b>E2.3: этот класс — route host.</b> Алиасы ниже делают его точкой входа по адресу формы:
 * {@code /records/{entityKey}/{id}} и {@code /lists/{entityKey}}. Стратегия выбрана измерением
 * (E2.0, {@code e2-deep-link-host-spike.md}): шаблоны работают на том же компоненте, что и
 * корневой маршрут, при клиентской навигации экземпляр и рабочая область сохраняются, а
 * cold-загрузка даёт порядок {@code ctor → openHome → beforeEnter}. Два {@code route target} и
 * отдельный shell не понадобились.</p>
 *
 * <p><b>Что здесь host, а что — платформа.</b> Здесь — только «когда и что показать»:
 * разобрать адрес, открыть форму, при отказе показать страницу состояния. Порядок проверок,
 * чтение до вкладки, решение 403 против 404 и соответствие адреса активной вкладке живут на
 * платформе ({@code FormRouteOpener}, {@code FormRouteUrlBridge}) — иначе каждый host повторял бы
 * их сам, а контракт адреса перестал бы быть одним.</p>
 *
 * <p><b>Адрес не меняется вручную.</b> Смена адреса при переключении вкладок — не забота host'а:
 * он лишь сообщает мосту текущий адрес и свою точку входа. Причина в том же измерении:
 * {@code UI.navigate} запускает {@code BeforeEnter} на каждое изменение адреса, поэтому
 * переключение вкладок стало бы новым входом с повторным чтением данных.</p>
 */
@Route("")
@RouteAlias("/entity-explorer/:entityKey")
@RouteAlias("/records/:entityKey/:id")
@RouteAlias("/lists/:entityKey")
@PageTitle("Vaa25_1")
@PermitAll
@SpringComponent
@Scope("prototype")
public class MainLayout extends AppLayout implements BeforeEnterObserver, BeforeLeaveObserver {

    private static final Logger log = LoggerFactory.getLogger(MainLayout.class);

    /** Вкладка Explorer: ключ вкладки один и для меню, и для адреса (E3.0). */
    private static final String EXPLORER_ENTRY_ID = "entity-explorer";

    /** Заголовок вкладки Explorer: один и тот же на обоих входах. */
    private static final String EXPLORER_TAB_TITLE = "Структура сущностей";

    private final Workspace workspace;
    private final SubsystemRegistry subsystemRegistry;
    private final UserPreferencesStore preferencesStore;
    private final GlobalSearchHeader globalSearchHeader;
    private final FormRouteOpener routeOpener;
    private final FormRouteUrlBridge routeUrlBridge;
    private final EntityExplorerAccess explorerAccess;
    private final FormRouteCatalog routeCatalog;
    private final ObjectProvider<EntityExplorerView> explorerViews;

    @Autowired
    public MainLayout(Workspace workspace,
                      SubsystemRegistry subsystemRegistry,
                      UserPreferencesStore preferencesStore,
                      GlobalSearchHeader globalSearchHeader,
                      FormRouteOpener routeOpener,
                      FormRouteUrlBridge routeUrlBridge,
                      EntityExplorerAccess explorerAccess,
                      FormRouteCatalog routeCatalog,
                      ObjectProvider<EntityExplorerView> explorerViews) {
        this.subsystemRegistry = subsystemRegistry;
        this.preferencesStore = preferencesStore;
        this.globalSearchHeader = globalSearchHeader;
        this.workspace = workspace;
        this.routeOpener = routeOpener;
        this.routeUrlBridge = routeUrlBridge;
        this.explorerAccess = explorerAccess;
        this.routeCatalog = routeCatalog;
        this.explorerViews = explorerViews;
        setContent(workspace);
        createHeader();
        createDrawer();
        openHome();
    }

    private void openHome() {
        workspace.open(MainView.class, "home", "Главная", v -> {});
    }

    /**
     * Вход по адресу формы.
     *
     * <p>Вызывается и на обычном {@code "/"}: там параметров маршрута нет, поэтому host'у нечего
     * открывать, но подключить мост к текущему адресу нужно — иначе переключение вкладок после
     * загрузки главной не меняло бы адрес. Проверка «это алиас или корень» идёт по параметрам
     * маршрута, а не по разбору пути: грамматика адреса остаётся в одном месте — в кодеке.</p>
     *
     * <p>Адрес Explorer — отдельная ветка (E3.0): у карточки типа нет формы, поэтому она входит
     * своим обработчиком. Ветка выбирается <b>до</b> параметров маршрута: адрес раздела обязан
     * получить ответ или отказ, а не тихую главную.</p>
     */
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String address = addressOf(event.getLocation());
        routeUrlBridge.install(address, this::enterAddress);
        routeUrlBridge.installExplorerEntryPoint(this::enterExplorerAddress);
        if (EntityExplorerAddress.claims(address)) {
            enterExplorerAddress(address);
            return;
        }
        if (event.getRouteParameters().getParameterNames().isEmpty()) {
            return;
        }
        enterAddress(address);
    }

    /**
     * Уход из host'а: единственное место, где вкладки рабочей области <b>уничтожаются</b> вместе
     * с оболочкой, и поэтому единственное, где защита несохранённого нужна на переходе (E2.4).
     *
     * <p>Вход по адресу сюда не попадает: {@code /records/**}, {@code /lists/**} и {@code /} — тот
     * же route target, экземпляр и рабочая область сохраняются (измерено в E2.0), поэтому вкладки
     * никуда не деваются и второе подтверждение на одно действие было бы ложным. Это же правило
     * снимает двойной диалог: закрытие вкладки — путь {@code Workspace.close}, а он навигации не
     * делает и об этом gate не знает.</p>
     *
     * <p>Отмена ничего не восстанавливает: адрес, активная вкладка и форма остаются как были,
     * потому что до ответа пользователя не сделано ни одного шага.</p>
     */
    @Override
    public void beforeLeave(BeforeLeaveEvent event) {
        if (!leavesTheHost(event.getNavigationTarget()) || !workspace.hasUnsavedChanges()) {
            return;
        }
        BeforeLeaveEvent.ContinueNavigationAction action = event.postpone();
        confirmUnsavedChanges(action);
    }

    /**
     * Уничтожается ли оболочка на этом переходе. Отдельно от показа диалога: решение проверяется
     * тестом, а рендер — наблюдением, и смешивать их значит не проверять ни то, ни другое.
     */
    static boolean leavesTheHost(Class<? extends Component> navigationTarget) {
        return navigationTarget != MainLayout.class;
    }

    /**
     * Решение о входе по адресу Explorer (E3.0): распознаваемая чистая функция, отделённая от роли
     * и от каталога.
     *
     * <p><b>Порядок проверок — часть контракта.</b> Без роли отказ выдаётся <b>до</b> разбора ключа и
     * <b>до</b> обращения к каталогу, поэтому ответ не зависит от того, существует ли тип: иначе
     * адрес Explorer стал бы инструментом проверки существования типов. По той же причине
     * неразбираемый адрес и неизвестный ключ дают ровно один отказ — тот же, что и роль.</p>
     *
     * <p><b>Успех не выражается {@code OpenResult}.</b> У карточки типа нет {@code FormRoute}, и
     * изображать её формой значило бы завести фиктивный маршрут; host открывает вкладку своим
     * путём. Результат называет тип, ключ и <b>запрошенный</b> адрес: legacy-ключ остаётся тем, по
     * которому пришли (ADR-0010).</p>
     */
    static ExplorerEntry decideExplorerEntry(boolean admin, String address,
                                             Function<String, Optional<Class<?>>> resolver) {
        if (!admin) {
            return ExplorerEntry.refused();
        }
        Optional<String> key = EntityExplorerAddress.keyOf(address);
        if (key.isEmpty()) {
            return ExplorerEntry.refused();
        }
        Optional<Class<?>> type = resolver.apply(key.get());
        return type.isPresent()
            ? new ExplorerEntry.Resolved(type.get(), key.get(), address)
            : ExplorerEntry.refused();
    }

    /** Результат решения: успех с типом либо единый отказ, не различающий роль и неизвестный ключ. */
    sealed interface ExplorerEntry {

        /** Отказ: то же значение для не-ADMIN, неизвестного ключа и неразбираемого адреса. */
        record Denied(OpenResult refusal) implements ExplorerEntry {
        }

        /** Тип найден: ключ канонический, адрес — запрошенный (может быть legacy). */
        record Resolved(Class<?> type, String key, String address) implements ExplorerEntry {
        }

        static ExplorerEntry refused() {
            return new Denied(EntityExplorerAccess.refusal());
        }
    }

    /**
     * Подтверждение ухода — той же формой, что у {@code ReportEditorView}, чтобы вопрос «сохранить
     * перед уходом?» задавался в приложении одинаково.
     *
     * <p>Ветка «Сохранить и продолжить» продолжает навигацию только при успешном сохранении всех
     * грязных вкладок: неудачный save оставляет и адрес, и формы на месте, а не уводит с
     * потерянными правками. В режиме просмотра (E1.5) эта кнопка не предлагается — сохранять
     * нечего.</p>
     */
    /**
     * «Сохранить и продолжить»: переход продолжается только после успешного сохранения.
     *
     * <p>Отделено ради проверки: диалог требует живого UI, а правило — «неудачный save не
     * продолжает навигацию» — обязано быть проверено, а не выведено из чтения кода. Провал
     * сохранения оставляет адрес, вкладку и введённое на месте: продолжать переход после него
     * значило бы ровно то, от чего E2.4 уходит — потерю правок без единого вопроса.</p>
     *
     * <p>Успешное сохранение закрывает вкладки: экран, который их показывал, уходит вместе с
     * переходом, а рабочая область живёт в UI scope. Сохранение при этом не отменяется — вкладки
     * закрываются уже чистые.</p>
     */
    static void saveThenContinue(Workspace workspace,
                                 BeforeLeaveEvent.ContinueNavigationAction action) {
        if (workspace.saveUnsavedChanges()) {
            workspace.closeAll();
            action.proceed();
        }
    }

    /**
     * «Продолжить без сохранения»: пользователь выбрал уход, и этот выбор выполняется целиком.
     *
     * <p>Отделено ради проверки по той же причине, что и сохранение: без закрытия грязные вкладки
     * пережили бы экран. Тогда после ухода при перезагрузке страницы появлялось бы предупреждение
     * о несохранённом, которого не видно, а следующий вход в host вернул бы вкладки с правками,
     * от которых пользователь только что отказался.</p>
     */
    static void leaveWithoutSaving(Workspace workspace,
                                   BeforeLeaveEvent.ContinueNavigationAction action) {
        workspace.closeAll();
        action.proceed();
    }

    private void confirmUnsavedChanges(BeforeLeaveEvent.ContinueNavigationAction action) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Несохранённые изменения");
        dialog.setText(workspace.unsavedChangesMessage());
        if (workspace.canSaveUnsavedChanges()) {
            dialog.setConfirmButton("Сохранить и продолжить",
                confirmed -> saveThenContinue(workspace, action));
            dialog.setCancelButton("Продолжить без сохранения",
                cancelled -> leaveWithoutSaving(workspace, action));
            dialog.setRejectButton("Остаться", rejected -> { });
        } else {
            dialog.setConfirmButton("Продолжить без сохранения",
                confirmed -> leaveWithoutSaving(workspace, action));
            dialog.setCancelButton("Остаться", rejected -> { });
        }
        dialog.open();
    }

    /**
     * Адрес окна в канонической форме — с ведущим слэшем.
     *
     * <p>Ведущий слэш добавляет host, а не кодек, и это измеренный факт, а не удобство:
     * {@code Location} из Vaadin отдаёт путь <b>без</b> него ({@code records/nomenclature/1}),
     * а адрес формы — относительный путь, начинающийся с {@code /}. Замерено на стенде: без
     * этого дополнения cold-загрузка любой ссылки показывала «Адрес не найден», оставаясь при
     * этом безопасной — адрес не менялся и вкладка не создавалась.</p>
     *
     * <p>Больше ничего в адресе не нормализуется: регистр ключа, ведущие нули id и лишние
     * query-параметры остаются причиной отказа (ADR §3).</p>
     */
    private static String addressOf(Location location) {
        String path = location.getPathWithQueryParameters();
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Единственное место, где адрес превращается в форму или в страницу состояния: тем же путём
     * идёт cold-загрузка, перезагрузка страницы и возврат браузером (переходы Back/Forward мост
     * проводит сюда же, поэтому право доступа и существование строки перепроверяются на каждом
     * входе).
     *
     * <p>Отказ не меняет адрес и не заводит вкладку: показывается страница состояния, а сообщение
     * в журнал идёт меткой исхода ({@code outcome()}) — она не различает скрытую и отсутствующую
     * строку и не содержит идентификатора (ADR §8).</p>
     */
    private OpenResult enterAddress(String address) {
        OpenResult result = routeOpener.openAddress(address);
        if (!result.opened()) {
            workspace.showTransientContent(new RouteStatePage(result));
            log.info("Форма по адресу не открыта: outcome={}, address={}", result.outcome(), address);
        }
        return result;
    }

    /**
     * Ветка host'а для адреса Explorer (E3.0). Отказ и успех — одна последовательность:
     * решение (роль и ключ спрашиваются в {@link #decideExplorerEntry}) и применение.
     *
     * <p>В журнал идёт только метка исхода: ни ключ, ни адрес в неё не попадают — иначе отказ по
     * роли отличался бы от отказа по неизвестному ключу строкой в логе (ADR-0010).</p>
     */
    private void enterExplorerAddress(String address) {
        applyExplorerEntry(
            decideExplorerEntry(explorerAccess.allows(), address, this::explorerTypeOfKey),
            recorded -> routeUrlBridge.tabAddressed(EXPLORER_ENTRY_ID, recorded),
            this::openExplorerTab,
            refusal -> {
                workspace.showTransientContent(new RouteStatePage(refusal));
                log.info("Адрес Explorer не открыт: outcome={}", refusal.outcome());
            });
    }

    /**
     * Последовательность host'а по решению об адресе Explorer — отделена ради проверки: отказ не
     * должен ни создавать вкладку, ни менять адрес, а успех обязан записать адрес <b>до</b>
     * открытия вкладки: мост в момент активации ещё не знает адреса и записал бы «/».
     */
    static void applyExplorerEntry(ExplorerEntry entry,
                                   Consumer<String> recordAddress,
                                   Consumer<Class<?>> openType,
                                   Consumer<OpenResult> showRefusal) {
        if (entry instanceof ExplorerEntry.Denied denied) {
            showRefusal.accept(denied.refusal());
            return;
        }
        ExplorerEntry.Resolved resolved = (ExplorerEntry.Resolved) entry;
        recordAddress.accept(resolved.address());
        openType.accept(resolved.type());
    }

    private Optional<Class<?>> explorerTypeOfKey(String key) {
        return routeCatalog.find(key).map(PublishedFormRoute::entityClass);
    }

    /** Открыть вкладку Explorer и применить к ней ключ: повторный вход — тот же вид, не второй. */
    private void openExplorerTab(Class<?> type) {
        if (type == null) {
            // Меню открывает пустой Explorer. Сбросить адрес до активации: UI-scoped вид и
            // запись моста переживают повторное открытие этой вкладки.
            routeUrlBridge.tabAddressChanged(EXPLORER_ENTRY_ID, null);
        }
        EntityExplorerView view = explorerViews.getObject();
        view.setTypeSelectionListener(this::explorerTypeSelected);
        view.init(type);
        workspace.openComponent(view, EXPLORER_ENTRY_ID, EXPLORER_TAB_TITLE);
    }

    /**
     * Пользователь выбрал тип в дереве: адрес вкладки меняет host, а не вид. У типа без
     * опубликованного ключа адреса нет — «/» вместо выдуманной ссылки.
     */
    private void explorerTypeSelected(Class<?> type) {
        routeUrlBridge.tabAddressChanged(EXPLORER_ENTRY_ID,
            type == null ? null
                : explorerAddressOf(routeCatalog.find(type).map(PublishedFormRoute::entityKey)));
    }

    /**
     * Адрес типа для выбора в дереве: канонический адрес по опубликованному ключу либо
     * {@code null} — «у выбора адреса нет» (owned-строка или непубликуемый тип).
     */
    static String explorerAddressOf(Optional<String> publishedKey) {
        return publishedKey.map(EntityExplorerAddress::format).orElse(null);
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
        }

        // E3.0: доступ к диагностической структуре спрашивается у единственного места правила —
        // тот же ответ, что у route-ветки и самого вида.
        if (explorerAccess.allows()) {
            SideNavItem explorerItem = new SideNavItem(EXPLORER_TAB_TITLE);
            explorerItem.setPrefixComponent(new Icon(VaadinIcon.SITEMAP));
            explorerItem.getElement().addEventListener("click", e -> openExplorerTab(null));
            nav.addItem(explorerItem);
        }

        if (isAdmin()) {
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

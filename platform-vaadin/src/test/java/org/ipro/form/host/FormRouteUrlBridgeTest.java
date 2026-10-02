package org.ipro.form.host;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.QueryParameters;
import org.ipro.form.link.EntityExplorerAddress;
import org.ipro.form.link.FormRoute;
import org.ipro.form.link.FormRouteCodec;
import org.ipro.form.link.FormRouteKind;
import org.ipro.form.link.OpenResult;
import org.ipro.form.spi.WorkspaceGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E2.3: мост адреса и активной вкладки. Тест фиксирует решения, которые иначе проверялись бы
 * только руками в браузере: когда адрес меняется, когда он <b>не</b> меняется, чем отличается
 * шаг истории от замены и что происходит на переходе браузера.
 *
 * <p>История браузера и рабочая область подменены: {@code History} из Vaadin требует живого UI и
 * сеанса, поэтому без этой границы ни одно из решений моста нельзя было бы проверить в модуле.</p>
 */
class FormRouteUrlBridgeTest {

    private final FormRouteCodec codec = new FormRouteCodec();
    private final FakeWorkspace workspace = new FakeWorkspace();
    private final FakeHistory history = new FakeHistory();

    /** Адреса, по которым вошёл host: видно, вызывался ли вход вообще. */
    private final List<String> entries = new ArrayList<>();

    /** Адреса Explorer, по которым вошёл его обработчик (E3.0): отдельный вход, а не форма. */
    private final List<String> explorerEntries = new ArrayList<>();

    /** Что возвращает точка входа host'а; в тестах без отказа значение не проверяется. */
    private OpenResult entryResult = OpenResult.invalidRoute("в этом тесте вход не проверяется");

    private FormRouteUrlBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = bridgeUnderContextPath("");
    }

    /** Мост с названным базовым путём развёртывания: в корне он пуст, и решения те же. */
    private FormRouteUrlBridge bridgeUnderContextPath(String basePath) {
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkspaceGateway> workspaces = mock(ObjectProvider.class);
        when(workspaces.getIfAvailable()).thenReturn(workspace);
        FormRouteUrlBridge created = new FormRouteUrlBridge(codec, workspaces, history, () -> basePath);
        created.installExplorerEntryPoint(explorerEntries::add);
        return created;
    }

    @Test
    void coldOpenOfAnAddressKeepsTheAddressItWasOpenedWith() {
        workspace.active = "home";
        bridge.install("/records/nomenclature/42", this::enter);

        bridge.routedTabOpened("item-nomenclature-42", route(FormRouteKind.ITEM, "nomenclature", 42L));
        workspace.becomeActive("item-nomenclature-42");

        assertThat(history.pushed)
            .as("адрес уже равен открытому: писать его снова значило бы добавить запись в историю"
                + " на одну загрузку страницы")
            .isEmpty();
        assertThat(history.replaced).isEmpty();
    }

    @Test
    void switchingToATabWithoutAnAddressReplacesTheCurrentEntry() {
        workspace.active = "item-nomenclature-42";
        bridge.install("/records/nomenclature/42", this::enter);
        bridge.routedTabOpened("item-nomenclature-42", route(FormRouteKind.ITEM, "nomenclature", 42L));

        workspace.becomeActive("home");

        assertThat(history.replaced)
            .as("у вкладки без адреса адреса не существует: шаг истории вёл бы Back в то же"
                + " место, из которого пришли")
            .containsExactly("/");
        assertThat(history.pushed).isEmpty();
    }

    @Test
    void switchingBetweenTwoRoutedTabsPushesEachAddress() {
        workspace.active = "item-nomenclature-42";
        bridge.install("/records/nomenclature/42", this::enter);
        bridge.routedTabOpened("item-nomenclature-42", route(FormRouteKind.ITEM, "nomenclature", 42L));
        bridge.routedTabOpened("item-workshop-7", route(FormRouteKind.ITEM, "workshop", 7L));

        workspace.becomeActive("item-workshop-7");

        assertThat(history.pushed)
            .as("переключение между адресуемыми вкладками — шаг истории: Back обязан вернуть"
                + " предыдущую запись")
            .containsExactly("/records/workshop/7");
    }

    @Test
    void closingTheActiveTabReplacesTheAddressInsteadOfAddingAStep() {
        workspace.active = "home";
        bridge.install("/", this::enter);
        bridge.routedTabOpened("item-nomenclature-42", route(FormRouteKind.ITEM, "nomenclature", 42L));
        workspace.becomeActive("item-nomenclature-42");
        history.forgetWhatHappenedWhilePreparingTheCase();

        workspace.close("item-nomenclature-42");

        assertThat(history.replaced)
            .as("закрытая вкладка не должна предлагаться кнопкой Back: пользователь вышел из неё"
                + " осознанно")
            .containsExactly("/");
        assertThat(history.pushed).isEmpty();
    }

    @Test
    void closingABackgroundTabDoesNotTouchTheAddress() {
        workspace.active = "home";
        bridge.install("/", this::enter);
        bridge.routedTabOpened("item-nomenclature-42", route(FormRouteKind.ITEM, "nomenclature", 42L));
        bridge.routedTabOpened("item-workshop-7", route(FormRouteKind.ITEM, "workshop", 7L));
        workspace.becomeActive("item-workshop-7");
        history.forgetWhatHappenedWhilePreparingTheCase();

        workspace.close("item-nomenclature-42");

        assertThat(history.pushed).isEmpty();
        assertThat(history.replaced)
            .as("адрес описывает видимое: фоновая вкладка ни на что не влияла и не влияет")
            .isEmpty();
        assertThat(workspace.active).isEqualTo("item-workshop-7");
    }

    @Test
    void browserBackToAnAddressReentersThroughTheHostEntryPoint() {
        workspace.active = "home";
        bridge.install("/", this::enter);

        history.goBackOrForward("/records/nomenclature/42");

        assertThat(entries)
            .as("возврат браузером — это вход по адресу, а не активация вкладки: право доступа и"
                + " существование строки обязаны перепроверяться")
            .containsExactly("/records/nomenclature/42");
        assertThat(history.pushed)
            .as("историю не трогаем: адрес уже изменён браузером")
            .isEmpty();
    }

    @Test
    void browserBackToTheRootActivatesTheTabWithoutAnAddress() {
        workspace.active = "home";
        bridge.install("/records/nomenclature/42", this::enter);
        bridge.routedTabOpened("item-nomenclature-42", route(FormRouteKind.ITEM, "nomenclature", 42L));

        history.goBackOrForward("/");

        assertThat(workspace.activated).containsExactly("home");
        assertThat(entries)
            .as("корень формой не является: открывать по нему нечего")
            .isEmpty();
        assertThat(history.pushed).isEmpty();
        assertThat(history.replaced).isEmpty();
    }

    @Test
    void closingTheActiveTabAfterABrowserBackReplacesTheAddressInsteadOfAddingAStep() {
        workspace.active = "item-nomenclature-42";
        bridge.install("/records/nomenclature/42", this::enter);
        bridge.routedTabOpened("item-nomenclature-42", route(FormRouteKind.ITEM, "nomenclature", 42L));
        bridge.routedTabOpened("item-workshop-7", route(FormRouteKind.ITEM, "workshop", 7L));
        workspace.becomeActive("item-workshop-7");
        history.forgetWhatHappenedWhilePreparingTheCase();

        // Back на адрес первой карточки: адрес сменил браузер, host вошёл по нему и активировал уже
        // открытую вкладку — всё это внутри применения истории, где адрес не пишется.
        history.goBackOrForward("/records/nomenclature/42");
        workspace.becomeActive("item-nomenclature-42");
        assertThat(history.pushed).as("переход браузера адреса не пишет").isEmpty();
        assertThat(history.replaced).isEmpty();

        workspace.close("item-nomenclature-42");

        assertThat(history.replaced)
            .as("после Back активна именно эта вкладка: её закрытие обязано заменить запись,"
                + " иначе Back предложил бы форму, из которой пользователь вышел")
            .containsExactly("/records/workshop/7");
        assertThat(history.pushed).isEmpty();
    }

    @Test
    void anAddressFromTheWindowWithoutTheLeadingSlashStillOpensTheForm() {
        workspace.active = "home";
        bridge.install("/", this::enter);

        // Так адрес отдаёт Location из Vaadin: без ведущего слэша (измерено на стенде).
        history.goBackOrForward("records/nomenclature/42");

        assertThat(entries)
            .as("адрес окна и адрес формы — разные адреса: без канонизации каждая корректная ссылка"
                + " на переходе браузера читалась бы как ошибка грамматики")
            .containsExactly("/records/nomenclature/42");
        assertThat(workspace.activated).isEmpty();
    }

    @Test
    void browserBackToAMalformedFormAddressShowsTheStatePageInsteadOfHome() {
        workspace.active = "home";
        bridge.install("/", this::enter);

        history.goBackOrForward("/records/nomenclature/not-a-number");

        assertThat(entries)
            .as("ошибочный адрес формы — всё ещё адрес формы: пользователь обязан увидеть отказ,"
                + " а не главную, по которой не понять, открылась ли ссылка")
            .containsExactly("/records/nomenclature/not-a-number");
        assertThat(workspace.activated)
            .as("на главную ведёт адрес без формы, а не любой неразобранный")
            .isEmpty();
    }

    @Test
    void browserBackToAnApplicationsAddressThatIsNotAFormGoesHome() {
        workspace.active = "home";
        bridge.install("/", this::enter);

        history.goBackOrForward("/report-catalog");

        assertThat(workspace.activated)
            .as("чужой адрес приложения — не повод для страницы состояния: формой он не заявлялся")
            .containsExactly("home");
        assertThat(entries).isEmpty();
    }

    @Test
    void underAContextPathTheWrittenAddressCarriesTheBasePath() {
        FormRouteUrlBridge underBasePath = bridgeUnderContextPath("/app");
        workspace.active = "home";
        underBasePath.install("/", this::enter);
        underBasePath.routedTabOpened("item-nomenclature-42",
            route(FormRouteKind.ITEM, "nomenclature", 42L));

        workspace.becomeActive("item-nomenclature-42");

        assertThat(history.pushed)
            .as("под развёртыванием /app адрес формы читается как /app/records/...: без базового"
                + " пути браузер разрешил бы его от корня origin, то есть вне приложения")
            .containsExactly("/app/records/nomenclature/42");
    }

    @Test
    void underAContextPathTheAddressFromTheWindowIsReadWithoutTheBasePath() {
        FormRouteUrlBridge underBasePath = bridgeUnderContextPath("/app");
        workspace.active = "home";
        underBasePath.install("/", this::enter);

        history.goBackOrForward("/app/records/nomenclature/42");

        assertThat(entries)
            .as("базовый путь — часть адреса окна, а не адреса формы: о развёртывании кодек не знает")
            .containsExactly("/records/nomenclature/42");
    }

    @Test
    void anEntryThatFailedDoesNotChangeTheAddress() {
        workspace.active = "home";
        bridge.install("/", this::enter);
        entryResult = OpenResult.invalidRoute("так открывать нечем");

        history.goBackOrForward("/records/nope/1");

        assertThat(history.pushed).isEmpty();
        assertThat(history.replaced)
            .as("отказ показывается страницей состояния, а адрес остаётся тем, по которому пришли")
            .isEmpty();
    }

    @Test
    void repeatedInstallDoesNotDuplicateSubscriptions() {
        workspace.active = "home";
        bridge.install("/", this::enter);
        bridge.install("/", this::enter);

        assertThat(workspace.listeners)
            .as("host входит при каждой навигации: подписка на смену вкладки оформляется один раз,"
                + " иначе одно переключение писало бы адрес дважды")
            .hasSize(1);
        assertThat(workspace.closedListeners)
            .as("подписка на закрытие — тот же контракт: одна на мост")
            .hasSize(1);
        assertThat(history.subscriptions)
            .as("переустановка обработчика истории — тоже подписка: второй обработчик вытеснил бы"
                + " первый молча")
            .isEqualTo(1);
    }

    @Test
    void addressIsRecordedBeforeActivationOtherwiseTheTabLooksAddressless() {
        workspace.active = "home";
        bridge.install("/records/nomenclature/42", this::enter);

        workspace.becomeActive("item-nomenclature-42");
        bridge.routedTabOpened("item-nomenclature-42", route(FormRouteKind.ITEM, "nomenclature", 42L));

        assertThat(history.replaced)
            .as("порядок «записать адрес, затем активировать» — часть контракта с координатором,"
                + " а не деталь реализации: обратный порядок не находит адреса и пишет «/»")
            .containsExactly("/");
    }

    @Test
    void theActiveEntryAtInstallTimeIsRememberedAsTheAddresslessOne() {
        workspace.active = "home";
        bridge.install("/", this::enter);

        history.goBackOrForward("/");

        assertThat(workspace.activated)
            .as("без этого вкладка главной, открытая до подключения моста, не нашлась бы"
                + " при возврате на «/»")
            .containsExactly("home");
    }

    @Test
    void anExplorerAddressFromTheWindowOpensTheExplorerInsteadOfHome() {
        workspace.active = "home";
        bridge.install("/", this::enter);

        history.goBackOrForward("/entity-explorer/nomenclature");

        assertThat(explorerEntries)
            .as("адрес Explorer — заявленный раздел, а не чужой адрес приложения: иначе карточка"
                + " типа не открывалась бы по ссылке вовсе")
            .containsExactly("/entity-explorer/nomenclature");
        assertThat(entries).as("у Explorer свой типизированный вход — формный обработчик не для него").isEmpty();
        assertThat(workspace.activated)
            .as("на главную ведёт адрес, который ничего не просит")
            .isEmpty();
        assertThat(history.pushed).isEmpty();
        assertThat(history.replaced).isEmpty();
    }

    @Test
    void anAnchoredExplorerAddressFromTheWindowReachesTheExplorerEntryPoint() {
        workspace.active = "home";
        bridge.install("/", this::enter);

        history.goBackOrForward("/entity-explorer/nomenclature?view=access/rules");

        assertThat(explorerEntries)
            .as("якорь — часть адреса Explorer: мост передаёт его входу как есть, а не срезает")
            .containsExactly("/entity-explorer/nomenclature?view=access/rules");
        assertThat(entries).as("у Explorer свой вход — якорь не переключает его на формный").isEmpty();
        assertThat(workspace.activated)
            .as("на главную ведёт адрес, который ничего не просит")
            .isEmpty();
        assertThat(history.pushed).isEmpty();
        assertThat(history.replaced).isEmpty();
    }

    /**
     * Измерено на стенде (E3.2.1 §8.3): {@code Location#getPathWithQueryParameters()} кодирует
     * {@code /} в значении, и разделовый якорь не доходил до host'а. Адрес собирается из
     * разобранных частей, поэтому {@code /} остаётся собой, параметр без значения — параметром без
     * значения, а повтор параметра — повтором (на нём стоит отказ грамматики, а не слияние).
     */
    @Test
    void theWindowAddressIsAssembledFromTheParsedLocation() {
        assertThat(FormRouteUrlBridge.addressOf(new Location("entity-explorer/nomenclature",
                QueryParameters.fromString("view=reading/paths"))))
            .as("разделовый якорь: '/' в значении не кодируется, иначе грамматика отвергнет адрес")
            .isEqualTo("entity-explorer/nomenclature?view=reading/paths");
        assertThat(FormRouteUrlBridge.addressOf(
                new Location("entity-explorer/nomenclature?view=reading")))
            .isEqualTo("entity-explorer/nomenclature?view=reading");
        assertThat(FormRouteUrlBridge.addressOf(
                new Location("entity-explorer/nomenclature?view=reading&continue")))
            .as("переносимый вход остаётся параметром без значения")
            .isEqualTo("entity-explorer/nomenclature?view=reading&continue");
        assertThat(FormRouteUrlBridge.addressOf(
                new Location("entity-explorer/nomenclature?view=a&view=b")))
            .as("повтор параметра сохраняется — грамматика обязана отвергнуть его, а не выбрать один")
            .isEqualTo("entity-explorer/nomenclature?view=a&view=b");
    }

    @Test
    void aSectionAnchorFromTheWindowReachesTheExplorerGrammar() {
        String address = FormRouteUrlBridge.addressOf(new Location("entity-explorer/nomenclature",
            QueryParameters.fromString("view=access/rules")));

        assertThat(EntityExplorerAddress.keyOf(address)).contains("nomenclature");
        assertThat(EntityExplorerAddress.anchorOf(address)).contains("access/rules");
    }

    @Test
    void changingThePlaceOfTheExplorerCardKeepsTheAnchorInTheHistoryStep() {
        workspace.active = "home";
        bridge.install("/entity-explorer/nomenclature?view=access", this::enter);
        bridge.tabAddressed("entity-explorer", "/entity-explorer/nomenclature?view=access");
        workspace.becomeActive("entity-explorer");
        assertThat(history.pushed)
            .as("адрес с якорем уже стоит в окне: вход его не перезаписывает")
            .isEmpty();
        history.forgetWhatHappenedWhilePreparingTheCase();

        bridge.tabAddressChanged("entity-explorer", "/entity-explorer/nomenclature?view=links");

        assertThat(history.pushed)
            .as("смена места карточки — шаг истории с якорем: Back обязан вернуть прежнее место")
            .containsExactly("/entity-explorer/nomenclature?view=links");
        history.forgetWhatHappenedWhilePreparingTheCase();

        history.goBackOrForward("/entity-explorer/nomenclature?view=access");

        assertThat(explorerEntries)
            .as("возврат браузером входит тем же путём и с тем же якорем")
            .containsExactly("/entity-explorer/nomenclature?view=access");
        assertThat(history.pushed).as("переход браузера адреса не пишет").isEmpty();
    }

    @Test
    void theExplorerTabWritesItsTypeAddressOnce() {
        workspace.active = "home";
        bridge.install("/entity-explorer/nomenclature", this::enter);
        bridge.tabAddressed("entity-explorer", "/entity-explorer/nomenclature");

        workspace.becomeActive("entity-explorer");

        assertThat(history.pushed)
            .as("адрес уже стоит в окне: повторная запись добавила бы шаг истории на одну загрузку"
                + " страницы")
            .isEmpty();
        assertThat(history.replaced).isEmpty();
    }

    @Test
    void changingTheTypeAddsAHistoryStepAndBackReturnsToThePreviousType() {
        workspace.active = "home";
        bridge.install("/entity-explorer/nomenclature", this::enter);
        bridge.tabAddressed("entity-explorer", "/entity-explorer/nomenclature");
        workspace.becomeActive("entity-explorer");
        history.forgetWhatHappenedWhilePreparingTheCase();

        bridge.tabAddressChanged("entity-explorer", "/entity-explorer/receiving-document");

        assertThat(history.pushed)
            .as("выбор другого типа — шаг истории: Back обязан вернуть предыдущий тип")
            .containsExactly("/entity-explorer/receiving-document");
        history.forgetWhatHappenedWhilePreparingTheCase();

        history.goBackOrForward("/entity-explorer/nomenclature");

        assertThat(explorerEntries)
            .as("возврат браузером — это вход по адресу Explorer, а не активация вкладки")
            .containsExactly("/entity-explorer/nomenclature");
        assertThat(workspace.activated).isEmpty();
        assertThat(history.pushed).as("переход браузера адреса не пишет").isEmpty();
    }

    @Test
    void theAddresslessChoiceReplacesTheCurrentHistoryEntry() {
        workspace.active = "home";
        bridge.install("/entity-explorer/nomenclature", this::enter);
        bridge.tabAddressed("entity-explorer", "/entity-explorer/nomenclature");
        workspace.becomeActive("entity-explorer");
        history.forgetWhatHappenedWhilePreparingTheCase();

        bridge.tabAddressChanged("entity-explorer", null);

        assertThat(history.replaced)
            .as("у выбора адреса нет: шаг истории вёл бы Back к тому же месту, из которого пришли")
            .containsExactly("/");
        assertThat(history.pushed).isEmpty();

        bridge.tabAddressChanged("entity-explorer", "/entity-explorer/nomenclature");

        assertThat(history.pushed)
            .as("после безадресного выбора следующий ключ снова становится шагом истории")
            .containsExactly("/entity-explorer/nomenclature");
    }

    @Test
    void closingTheExplorerTabForgetsItsTypeAddress() {
        workspace.active = "home";
        bridge.install("/", this::enter);
        bridge.tabAddressed("entity-explorer", "/entity-explorer/nomenclature");
        workspace.becomeActive("entity-explorer");
        workspace.close("entity-explorer");
        history.forgetWhatHappenedWhilePreparingTheCase();

        workspace.becomeActive("entity-explorer");

        assertThat(history.pushed)
            .as("новая пустая вкладка с тем же entryId не наследует адрес закрытой карточки")
            .isEmpty();
    }

    /**
     * E3.2.2 §4.4: закрытие вкладки не забывает выбор текущего UI — повторное меню регистрирует
     * актуальный адрес заново до активации вкладки. Иначе восстановленный Explorer открывался бы
     * безадресно, хотя сохранённый тип известен.
     */
    @Test
    void reopeningTheExplorerTabRegistersTheRestoredAddressAgain() {
        workspace.active = "home";
        bridge.install("/", this::enter);
        bridge.tabAddressed("entity-explorer", "/entity-explorer/nomenclature");
        workspace.becomeActive("entity-explorer");
        workspace.close("entity-explorer");
        history.forgetWhatHappenedWhilePreparingTheCase();

        bridge.tabAddressed("entity-explorer", "/entity-explorer/nomenclature");
        workspace.becomeActive("entity-explorer");

        assertThat(history.pushed)
            .as("повторное меню заново регистрирует адрес до активации: вместо карточки не"
                + " записывается «/»")
            .containsExactly("/entity-explorer/nomenclature");
    }

    @Test
    void underAContextPathTheExplorerAddressCarriesTheBasePath() {
        FormRouteUrlBridge underBasePath = bridgeUnderContextPath("/app");
        workspace.active = "home";
        underBasePath.install("/", this::enter);
        underBasePath.tabAddressed("entity-explorer", "/entity-explorer/nomenclature");

        workspace.becomeActive("entity-explorer");

        assertThat(history.pushed)
            .as("под развёртыванием /app адрес Explorer читается как /app/entity-explorer/...:"
                + " без базового пути браузер разрешил бы его от корня origin, вне приложения")
            .containsExactly("/app/entity-explorer/nomenclature");
    }

    @Test
    void aMalformedExplorerAddressFromHistoryRendersTheOutcome() {
        workspace.active = "home";
        bridge.install("/", this::enter);

        history.goBackOrForward("/entity-explorer");
        history.goBackOrForward("/entity-explorer/nope/extra");

        assertThat(explorerEntries)
            .as("заявленный раздел Explorer без разбираемого ключа получает отказ host'а, а не тихую"
                + " главную: адрес, который ничего не открыл, не должен выглядеть как адрес, который"
                + " ничего и не просил")
            .containsExactly("/entity-explorer", "/entity-explorer/nope/extra");
        assertThat(workspace.activated).isEmpty();
        assertThat(history.pushed).isEmpty();
        assertThat(history.replaced).isEmpty();
    }

    @Test
    void switchingBackToTheExplorerTabWritesItsTypeAddressNotTheRoot() {
        workspace.active = "item-nomenclature-42";
        bridge.install("/records/nomenclature/42", this::enter);
        bridge.routedTabOpened("item-nomenclature-42", route(FormRouteKind.ITEM, "nomenclature", 42L));
        bridge.tabAddressed("entity-explorer", "/entity-explorer/nomenclature");

        workspace.becomeActive("entity-explorer");

        assertThat(history.pushed)
            .as("у вкладки Explorer адрес есть, и он не «/»: иначе карточка типа выглядела бы"
                + " безадресной, а ссылка на неё терялась бы")
            .containsExactly("/entity-explorer/nomenclature");
    }

    @Test
    void anAddressedExplorerTabIsNotRememberedAsTheAddresslessOne() {
        workspace.active = "entity-explorer";
        bridge.tabAddressed("entity-explorer", "/entity-explorer/nomenclature");
        bridge.install("/entity-explorer/nomenclature", this::enter);

        history.goBackOrForward("/");

        assertThat(workspace.activated)
            .as("«/» возвращает вкладку без адреса, а не Explorer: у Explorer адрес есть")
            .isEmpty();
    }

    private OpenResult enter(String address) {
        entries.add(address);
        return entryResult;
    }

    private static FormRoute route(FormRouteKind kind, String entityKey, Long id) {
        return new FormRoute(kind, entityKey, id, null);
    }

    /** Рабочая область: активная вкладка, открытые вкладки, активации и подписчики. */
    private static final class FakeWorkspace implements WorkspaceGateway {

        private final List<Consumer<String>> listeners = new ArrayList<>();
        private final List<Consumer<String>> closedListeners = new ArrayList<>();
        private final List<String> activated = new ArrayList<>();
        private final List<String> open = new ArrayList<>();
        private String active;

        @Override
        public <T extends Component> void open(Class<T> viewType, String entryId,
                                               String tabTitle, Consumer<T> initializer) {
            becomeActive(entryId);
        }

        @Override
        public void openComponent(Component view, String entryId, String tabTitle) {
            becomeActive(entryId);
        }

        /** Закрытие: сначала событие закрытия, затем активной становится следующая вкладка. */
        @Override
        public void close(String entryId) {
            open.remove(entryId);
            for (Consumer<String> listener : List.copyOf(closedListeners)) {
                listener.accept(entryId);
            }
            becomeActive(open.isEmpty() ? null : open.get(0));
        }

        @Override
        public void activate(String entryId) {
            activated.add(entryId);
            becomeActive(entryId);
        }

        @Override
        public String activeEntryId() {
            return active;
        }

        @Override
        public void addActiveEntryListener(Consumer<String> listener) {
            listeners.add(listener);
        }

        @Override
        public void addEntryClosedListener(Consumer<String> listener) {
            closedListeners.add(listener);
        }

        @Override
        public void showTransientContent(Component content) {
        }

        void becomeActive(String entryId) {
            if (entryId != null && !open.contains(entryId)) {
                open.add(entryId);
            }
            active = entryId;
            for (Consumer<String> listener : List.copyOf(listeners)) {
                listener.accept(entryId);
            }
        }
    }

    /** История браузера: шаг и замена различимы, как в браузере; переходы — отдельно. */
    private static final class FakeHistory implements BrowserHistory {

        private final List<String> pushed = new ArrayList<>();
        private final List<String> replaced = new ArrayList<>();
        private int subscriptions;
        private Consumer<String> backForward = address -> {
        };

        @Override
        public void onBackForward(Consumer<String> handler) {
            subscriptions++;
            backForward = handler;
        }

        @Override
        public void push(String address) {
            pushed.add(address);
        }

        @Override
        public void replace(String address) {
            replaced.add(address);
        }

        void goBackOrForward(String address) {
            backForward.accept(address);
        }

        /** Подготовка случая сама меняет адрес: проверяем только то, что случилось после неё. */
        void forgetWhatHappenedWhilePreparingTheCase() {
            pushed.clear();
            replaced.clear();
        }
    }
}

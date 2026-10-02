package org.ipro.form.host;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.page.History;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.NavigationTrigger;
import org.ipro.form.link.ApplicationBasePath;
import org.ipro.form.link.EntityExplorerAddress;
import org.ipro.form.link.FormRoute;
import org.ipro.form.link.FormRouteCodec;
import org.ipro.form.link.FormRouteKind;
import org.ipro.form.link.OpenResult;
import org.ipro.form.spi.WorkspaceGateway;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.node.BaseJsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Мост между адресом в окне браузера и активной вкладкой рабочей области (E2.3, ADR-0009 §7).
 *
 * <p><b>Что он удерживает.</b> Одно соответствие: «что видно» = «что в адресе». Вкладка, открытая
 * по адресу, имеет адрес; вкладка без адреса (главная, админские, подсистемы) даёт {@code "/"}.
 * Проверить это можно только на каждом изменении — вкладку выбрал пользователь, вкладку открыл
 * координатор, вкладку закрыли, браузер вернулся назад, — поэтому мост подписан на смену активной
 * вкладки, а не опрашивается.</p>
 *
 * <p><b>Почему адрес меняется через {@code History}, а не через {@code UI.navigate}.</b> Измерено в
 * E2.0: {@code UI.navigate} запускает {@code BeforeEnter} на каждое изменение адреса, то есть
 * обычный переход между вкладками превратился бы в новый route-entry с повторным чтением данных.
 * {@code History.pushState} меняет адрес без маршрутизации, а Back/Forward приходят в Java как
 * {@code trigger=HISTORY} — это и есть две разные вещи, которые здесь разведены.</p>
 *
 * <p><b>Источник перехода — часть контракта, а не деталь.</b> Переход, начатый браузером, не должен
 * порождать запись в историю: иначе Back возвращал бы не туда, куда шёл пользователь, а на свой же
 * предыдущий шаг. Поэтому вход по адресу из истории помечается и на время его выполнения адрес не
 * пишется.</p>
 *
 * <p><b>Адрес Explorer — второй, независимый вход (E3.0).</b> Карточка типа адресуется
 * опубликованным ключом ({@code /entity-explorer/{key}}), у неё нет ни id записи, ни формы,
 * поэтому она не выражается {@code FormRoute} и входит в мост своим обработчиком. При этом
 * «домой» по-прежнему ведёт только адрес, который ничего не просит: заявленный раздел Explorer
 * получает отказ host'а, а не тихую главную.</p>
 *
 * <p><b>Что мост не делает.</b> Он не читает данные, не решает, можно ли открыть форму, и не
 * показывает страниц: за это отвечает типизированный route-вход ({@code FormRouteOpener}) и host,
 * который передаёт сюда свою точку входа. Здесь только адрес и вкладки.</p>
 */
public class FormRouteUrlBridge {

    /** Адрес вкладки, у которой адреса нет: главная и прочие «не-routable» экраны. */
    private static final String ADDRESSLESS = "/";

    private final FormRouteCodec codec;
    private final ObjectProvider<WorkspaceGateway> workspaces;
    private final BrowserHistory browserHistory;

    /**
     * Базовый путь развёртывания: адрес в окне — адрес приложения, а не только формы (§3 ADR).
     * У развёртывания в корне он пуст, и мост остаётся тем же — меняется только запись в окно.
     */
    private final Supplier<String> basePath;

    /** Адрес открытой вкладки. Заполняет координатор в момент открытия — раньше, чем вкладка
     *  станет активной, иначе первая же смена активности не нашла бы адреса. */
    private final Map<String, FormRoute> routeOfEntry = new LinkedHashMap<>();

    /**
     * Адрес вкладки, не связанной с формой (E3.0): карточка типа в Entity Explorer. Отдельно от
     * {@link #routeOfEntry}, потому что адрес Explorer — не {@code FormRoute}: у карточки типа нет
     * ни id записи, ни вида формы, и изображать её формой значило бы завести фиктивный маршрут.
     */
    private final Map<String, String> explorerAddressOfEntry = new LinkedHashMap<>();

    /** Ключ последней активной вкладки без адреса: адресу {@code "/"} нужно куда-то вернуться. */
    private String addresslessEntryId;

    /** Активная вкладка по нашей записи: нужна, чтобы отличить закрытие активной от фоновой. */
    private String currentEntryId;

    /** Закрылась активная вкладка: следующая запись адреса обязана быть заменой, а не шагом. */
    private boolean replaceNextWrite;

    /** Что, по нашей записи, стоит в адресе. Своя запись, а не чтение из Vaadin: значение должно
     *  быть известно в момент решения, а не после обновления UI. */
    private String currentAddress = ADDRESSLESS;

    private Function<String, OpenResult> entryPoint = address ->
        OpenResult.invalidRoute("Host не подключён: адрес " + address + " открывать нечем");

    /**
     * Вход по адресу Explorer. По умолчанию бездействует: раздел Explorer — часть host'а, а не
     * формы, и приложение, которому он не нужен, ничего не подключает.
     */
    private Consumer<String> explorerEntryPoint = address -> {
    };

    private boolean listening;
    private boolean applyingHistory;

    public FormRouteUrlBridge(FormRouteCodec codec, ObjectProvider<WorkspaceGateway> workspaces) {
        this(codec, workspaces, new PageHistory(), ApplicationBasePath::current);
    }

    /** Точка расширения для проверки: история браузера подменяется, решения моста — те же. */
    FormRouteUrlBridge(FormRouteCodec codec, ObjectProvider<WorkspaceGateway> workspaces,
                       BrowserHistory browserHistory) {
        this(codec, workspaces, browserHistory, () -> "");
    }

    /**
     * Проверка с непустым базовым путём: развёртывание под контекстом — единственный случай,
     * в котором видно, что адрес формы и адрес окна — разные адреса.
     */
    FormRouteUrlBridge(FormRouteCodec codec, ObjectProvider<WorkspaceGateway> workspaces,
                       BrowserHistory browserHistory, Supplier<String> basePath) {
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.browserHistory = Objects.requireNonNull(browserHistory, "browserHistory must not be null");
        this.basePath = Objects.requireNonNull(basePath, "basePath must not be null");
    }

    /**
     * Координатор сообщает: вкладка {@code entryId} открыта по адресу {@code route}.
     *
     * <p>Вызывается <b>до</b> активации вкладки: смена активности — это событие, на которое мост
     * уже обязан знать адрес, иначе он записал бы {@code "/"} вместо адреса формы.</p>
     */
    public void routedTabOpened(String entryId, FormRoute route) {
        if (entryId == null || route == null) {
            return;
        }
        routeOfEntry.put(entryId, route);
    }

    /**
     * Подключить host: текущий адрес окна и его точка входа по адресу.
     *
     * <p>Повторный вызов — норма (host входит заново при каждой навигации), но подписка на смену
     * вкладки и на историю оформляется один раз: двойная подписка означала бы две записи в историю
     * на одно переключение.</p>
     *
     * @param address    адрес, который сейчас в окне; {@code null} трактуется как {@code "/"}
     * @param entryPoint вход по адресу: разбор, открытие и показ состояния при отказе
     */
    public void install(String address, Function<String, OpenResult> entryPoint) {
        Objects.requireNonNull(entryPoint, "entryPoint must not be null");
        this.entryPoint = entryPoint;
        this.currentAddress = canonicalAddress(address);

        WorkspaceGateway workspace = workspace();
        if (workspace == null) {
            // Host без рабочей области не может удерживать соответствие: адрес открывает форму
            // только в Workspace (ADR §3). Это ошибка конфигурации, и её называет startup-проверка.
            return;
        }
        if (!listening) {
            workspace.addActiveEntryListener(this::activeEntryChanged);
            workspace.addEntryClosedListener(this::entryClosed);
            browserHistory.onBackForward(this::historyChanged);
            listening = true;
        }
        String active = workspace.activeEntryId();
        if (active != null && addressOf(active) == null) {
            addresslessEntryId = active;
        }
    }

    /**
     * Подключить host: чем открывается адрес Explorer (E3.0).
     *
     * <p>Отдельный обработчик, а не общий с формами: у формы типизированный результат
     * ({@code OpenResult}) решает, что показать, а карточке типа маршрут формы не соответствует —
     * host открывает вкладку своим путём. Общий обработчик заставил бы изображать карточку типов
     * формой с фиктивным маршрутом.</p>
     */
    public void installExplorerEntryPoint(Consumer<String> entryPoint) {
        this.explorerEntryPoint = Objects.requireNonNull(entryPoint, "entryPoint must not be null");
    }

    /**
     * Координатор сообщает: вкладка {@code entryId} имеет адрес Explorer {@code address} (E3.0).
     *
     * <p>Вызывается <b>до</b> активации вкладки и, в отличие от {@link #routedTabOpened}, не
     * трогает историю: на входе по адресу адрес уже стоит в окне, и повторная запись добавила бы
     * шаг на одну загрузку страницы. Адрес, не разбираемый грамматикой Explorer, не запоминается:
     * в окно обязан попадать только тот адрес, который открывается снова.</p>
     */
    public void tabAddressed(String entryId, String address) {
        if (entryId == null || address == null || EntityExplorerAddress.keyOf(address).isEmpty()) {
            return;
        }
        explorerAddressOfEntry.put(entryId, address);
    }

    /**
     * Адрес той же вкладки изменился выбором пользователя (E3.0): в дереве выбран другой тип.
     *
     * <p>Пишется <b>немедленно</b> шагом истории, не дожидаясь смены активности: вкладка уже
     * активна, и события активации не будет. {@code null} означает «у выбора адреса нет» (тип без
     * опубликованного ключа): запись вкладки снимается, а текущая запись истории <b>заменяется</b>
     * на {@code "/"} — шаг вёл бы Back к тому же месту, из которого пришли.</p>
     *
     * <p>Повторный выбор того же типа шага не добавляет: адрес сравнивается с текущим в
     * {@link #write(String, boolean)}.</p>
     */
    public void tabAddressChanged(String entryId, String address) {
        if (entryId == null) {
            return;
        }
        if (address == null) {
            explorerAddressOfEntry.remove(entryId);
            if (entryId.equals(currentEntryId)) {
                write(ADDRESSLESS, true);
            }
            return;
        }
        if (EntityExplorerAddress.keyOf(address).isEmpty()) {
            return;
        }
        explorerAddressOfEntry.put(entryId, address);
        if (entryId.equals(currentEntryId)) {
            write(address, false);
        }
    }

    /** Смена активной вкладки: адрес обязан стать её адресом. */
    private void activeEntryChanged(String entryId) {
        // Флаг снимается до любых выходов: оставшись, он превратил бы в замену уже следующий,
        // ничем не связанный с закрытием переход.
        boolean replace = replaceNextWrite;
        replaceNextWrite = false;
        // Учёт активной вкладки ведётся и при переходе браузера, хотя запись в адрес там запрещена:
        // это два разных решения. Иначе после Back активной считалась бы прежняя вкладка, и
        // закрытие действительно активной принималось бы за закрытие фоновой — следующий адрес
        // добавился бы шагом истории вместо замены (измерено тестом «Back → закрыть активную»).
        currentEntryId = entryId;
        if (applyingHistory) {
            return;
        }
        String address = addressOf(entryId);
        if (address == null) {
            if (entryId != null) {
                addresslessEntryId = entryId;
            }
            // Вкладка без адреса: адреса этой формы не существует, поэтому шага в истории не
            // делаем — заменяем текущую запись, как и при закрытии.
            write(ADDRESSLESS, true);
            return;
        }
        write(address, replace);
    }

    /**
     * Адрес вкладки в канонической форме: форма — через кодек, Explorer — как записан координатором
     * (E3.0). {@code null} — адреса у вкладки нет вовсе.
     */
    private String addressOf(String entryId) {
        if (entryId == null) {
            return null;
        }
        FormRoute route = routeOfEntry.get(entryId);
        return route != null ? codec.format(route) : explorerAddressOfEntry.get(entryId);
    }

    /**
     * Закрылась вкладка. Адрес меняет только закрытие <b>активной</b>: закрытие фоновой вкладки
     * не трогает ни видимое, ни адрес.
     */
    private void entryClosed(String entryId) {
        if (entryId != null && entryId.equals(currentEntryId)) {
            replaceNextWrite = true;
        }
        // Адрес карточки типа принадлежит открытой вкладке. После закрытия меню может открыть
        // вкладку с тем же entryId без выбранного типа — старый адрес не должен ожить снова.
        explorerAddressOfEntry.remove(entryId);
    }

    /**
     * Переход браузера. Адрес <b>уже</b> изменён браузером, поэтому здесь остаётся привести в
     * соответствие видимое: разобрать адрес и войти по нему тем же путём, что и при cold-загрузке.
     *
     * <p>Повторный вход по тому же адресу означает и повторное чтение данных: право доступа и
     * существование строки перепроверяются на каждом входе (E2.2), а не наследуются от прошлого
     * показа этой же вкладки.</p>
     *
     * <p><b>На «Главную» ведёт только «Главная».</b> Раньше сюда попадал любой неразобранный адрес,
     * и ошибочный {@code /records/...} молча показывал главную вместо страницы состояния —
     * то есть адрес, который ничего не открыл, выглядел как адрес, который ничего и не просил.
     * Теперь домой ведёт только адрес без формы (в том числе {@code "/"}), а адрес, <b>заявляющий</b>
     * раздел формы ({@code /records/...}, {@code /lists/...}), идёт обычным входом: отказ будет
     * назван исходом и показан страницей состояния.</p>
     *
     * <p><b>Адрес окна и адрес формы — разные адреса.</b> Из истории приходит адрес окна: без
     * ведущего слэша (так его отдаёт {@code Location}) и с базовым путём развёртывания, если
     * приложение живёт под контекстом. Канонизация снимает базу и возвращает слэш — без неё
     * каждая корректная ссылка на Back читалась бы как ошибка грамматики (измерено на стенде:
     * Forward на {@code /records/journal/3} оставлял главную на экране при адресе карточки).</p>
     */
    private void historyChanged(String address) {
        String target = canonicalAddress(address);
        currentAddress = target;
        applyingHistory = true;
        try {
            if (claimsExplorerSection(target)) {
                explorerEntryPoint.accept(target);
                return;
            }
            if (!claimsAFormSection(target)) {
                WorkspaceGateway workspace = workspace();
                if (workspace != null && addresslessEntryId != null) {
                    workspace.activate(addresslessEntryId);
                }
                return;
            }
            entryPoint.apply(target);
        } finally {
            applyingHistory = false;
        }
    }

    private void write(String address, boolean replace) {
        if (address.equals(currentAddress)) {
            return;
        }
        currentAddress = address;
        String windowAddress = windowAddress(address);
        if (replace) {
            browserHistory.replace(windowAddress);
        } else {
            browserHistory.push(windowAddress);
        }
    }

    /**
     * Адрес так, как его видит окно: канонический адрес плюс базовый путь развёртывания.
     *
     * <p>Без базового пути браузер разрешает {@code /records/...} от корня origin — ссылка
     * уводила бы из приложения, размещённого под {@code /app} (ADR §5). Запись истории и
     * копирование ссылки спрашивают один и тот же источник — {@link ApplicationBasePath}.</p>
     */
    private String windowAddress(String address) {
        String base = basePath.get();
        return base == null || base.isEmpty() || "/".equals(base) ? address : base + address;
    }

    /**
     * Адрес окна из разобранного {@link Location} — в той форме, которую читают грамматики адреса
     * ({@link FormRouteCodec}, {@link EntityExplorerAddress}).
     *
     * <p>Собирается из частей, а не берётся у {@code Location#getPathWithQueryParameters()}: тот
     * отдаёт query в кодированном виде и кодирует {@code /} в значении
     * ({@code ?view=reading%2Fpaths} — измерено на стенде, E3.2.1 §8.3), а грамматика якоря
     * кодирование отвергает — разделовый якорь не доходил бы до словаря карточки. Vaadin query уже
     * разобрал, поэтому значения берутся разобранными; разделители параметров экранируются
     * обратно, чтобы границы пар не съезжали, а {@code /} в значении остаётся собой.</p>
     *
     * <p>Второго чтения адреса окна нет: этот метод — один источник и для {@code beforeEnter}
     * host'а, и для Back/Forward. Адрес отдаётся без ведущего слэша, как его отдаёт сам
     * {@code Location}; каноническую форму даёт {@link #canonicalAddress(String)}.</p>
     */
    public static String addressOf(Location location) {
        StringBuilder address = new StringBuilder(location.getPath());
        StringBuilder query = new StringBuilder();
        location.getQueryParameters().getParameters().forEach((name, values) -> {
            if (values.isEmpty()) {
                appendParameter(query, name, null);
                return;
            }
            for (String value : values) {
                appendParameter(query, name, value);
            }
        });
        return query.length() == 0 ? address.toString() : address.append('?').append(query).toString();
    }

    /**
     * Пара {@code имя} либо {@code имя=значение}. Пустое значение остаётся параметром без
     * {@code =}: так его отдаёт и {@code Location#getPathWithQueryParameters()}, и переносимый вход
     * приходит именно так — второй формы у него не появляется.
     */
    private static void appendParameter(StringBuilder query, String name, String value) {
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(escape(name));
        if (value == null || value.isEmpty()) {
            return;
        }
        query.append('=').append(escape(value));
    }

    /**
     * Экранируются только символы, меняющие разбор адреса ({@code %}, {@code &}, {@code =},
     * {@code #}, {@code ?}): значение вне грамматики обязано остаться отказом, а не превратиться в
     * другой адрес.
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ("%&=#?".indexOf(character) < 0) {
                escaped.append(character);
                continue;
            }
            escaped.append('%')
                .append(Character.toUpperCase(Character.forDigit((character >> 4) & 0xF, 16)))
                .append(Character.toUpperCase(Character.forDigit(character & 0xF, 16)));
        }
        return escaped.toString();
    }

    /**
     * Адрес из окна в канонической форме: базовый путь снят, ведущий слэш есть, пустое — «/».
     *
     * <p>Обе части — не косметика. Без снятия базы адрес под контекстом разбирался бы как адрес
     * с неизвестным первым сегментом, а без ведущего слэша — как адрес вне грамматики; оба раза
     * корректная ссылка выглядела бы ошибкой.</p>
     */
    private String canonicalAddress(String address) {
        String value = address == null ? "" : address.trim();
        String base = basePath.get();
        if (base != null && !base.isEmpty() && !"/".equals(base) && value.startsWith(base)) {
            value = value.substring(base.length());
        }
        if (value.isEmpty()) {
            return ADDRESSLESS;
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    /**
     * Заявляет ли адрес раздел Explorer: {@code /entity-explorer/...}. Грамматика раздела живёт в
     * {@link EntityExplorerAddress} — как грамматика формы в кодеке: мост только выбирает ветку.
     * Заявленным считается и адрес без ключа ({@code /entity-explorer}): он получает тот же отказ,
     * а не тихую главную.
     */
    private static boolean claimsExplorerSection(String address) {
        return EntityExplorerAddress.claims(address);
    }

    /**
     * Заявляет ли адрес раздел формы ({@code records}/{@code lists}) — а не «является ли он формой»:
     * грамматику решает кодек, здесь только грубое «наш ли это адрес». Различие нужно ровно для
     * одного выбора: домой ведёт адрес без формы, а ошибочный адрес формы обязан стать страницей
     * состояния, а не тихой главной.
     */
    private static boolean claimsAFormSection(String address) {
        String path = address.startsWith("/") ? address.substring(1) : address;
        int end = 0;
        while (end < path.length() && path.charAt(end) != '/' && path.charAt(end) != '?') {
            end++;
        }
        return FormRouteKind.ofSegment(path.substring(0, end)).isPresent();
    }

    private WorkspaceGateway workspace() {
        return workspaces.getIfAvailable();
    }

    /**
     * Реализация {@link BrowserHistory} поверх Vaadin.
     *
     * <p>Здесь же заперта зависимость от Jackson 3: {@code History} принимает узел состояния именно
     * его типом, тогда как приложение живёт на Jackson 2. Тип не должен протекать дальше этого
     * класса — иначе новая транзитивная зависимость стала бы частью прикладного кода.</p>
     */
    private static final class PageHistory implements BrowserHistory {

        @Override
        public void onBackForward(Consumer<String> handler) {
            UI.getCurrent().getPage().getHistory().setHistoryStateChangeHandler(event -> {
                // Программные изменения адреса тоже приходят сюда; они не переходы пользователя.
                if (event.getTrigger() != NavigationTrigger.HISTORY) {
                    return;
                }
                handler.accept(addressOf(event.getLocation()));
            });
        }

        @Override
        public void push(String address) {
            History history = UI.getCurrent().getPage().getHistory();
            history.pushState(state(), address);
        }

        @Override
        public void replace(String address) {
            History history = UI.getCurrent().getPage().getHistory();
            history.replaceState(state(), address);
        }

        // Сюда приходит уже адрес окна (базовый путь добавлен мостом): см. FormRouteUrlBridge#windowAddress.

        /**
         * Узел состояния: пустой объект. Ни состояние формы, ни параметры открытия в историю не
         * попадают (ADR §2) — адрес описывает только форму, а не то, что в ней набрано.
         */
        private static BaseJsonNode state() {
            return JsonNodeFactory.instance.objectNode();
        }
    }
}

package org.ip.views.workspace;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.ipro.form.Dirtyable;
import org.ipro.form.Savable;
import org.ipro.form.spi.WorkspaceGateway;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Рабочая область приложения: вкладки форм внутри одного UI.
 *
 * <p>D3.5.3-fix: бин UI-scoped, а не объект, созданный вручную в layout'е. Раньше
 * {@code MainLayout} делал {@code new Workspace(manager)} и передавал его дальше вьюхам, а те
 * вкладывали его в координатор. Теперь инстанс на UI даёт контейнер: у каждого UI своя область по
 * построению, вкладки не могут переехать в чужой UI, а весь путь «layout → view → coordinator»
 * с мутабельным {@code setWorkspace} исчез — он был тем самым состоянием, которое не принадлежало
 * UI явно.</p>
 */
@SpringComponent
@UIScope
public class Workspace extends VerticalLayout implements WorkspaceGateway {

    private final Span titleLabel = new Span();
    private final Div content = new Div();
    private final Tabs tabs = new Tabs();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final WorkspaceManager manager;

    /**
     * Подписчики на смену активной вкладки (E2.3): мост адреса и вкладок платформы. Поле есть
     * всегда, даже когда подписчиков нет: отсутствие host'а — это пустой список, а не другой
     * Workspace.
     */
    private final List<Consumer<String>> activeEntryListeners = new CopyOnWriteArrayList<>();

    /**
     * Подписчики на закрытие вкладки: адрес закрытой формы обязан стать другим, а не остаться
     * записями истории (мост адреса различает выбор и закрытие именно по этому событию).
     */
    private final List<Consumer<String>> entryClosedListeners = new CopyOnWriteArrayList<>();

    /** Имя JS-обработчика выгрузки: одно на UI, чтобы не плодились дубликаты слушателя. */
    private static final String UNLOAD_GUARD_JS = "__vaa25UnsavedChanges";

    /** Стоит ли сейчас JS-обработчик выгрузки: чтобы не переставлять его на каждом ответе. */
    private boolean unloadGuardInstalled;

    /**
     * Пауза перед сообщением о вводе: набор текста — это десятки событий, а решение нужно одно.
     * Задержка измеряется на клиенте, поэтому промежуточные события не порождают ни запроса,
     * ни пересчёта.
     */
    private static final int INPUT_WATCH_DEBOUNCE_MS = 200;

    /** Слушатели ввода ставятся один раз на рабочую область, а не на каждый ответ. */
    private boolean inputWatchInstalled;

    /** Заказ на пересчёт уже сделан: второй на тот же запрос был бы тем же решением дважды. */
    private boolean unloadGuardRefreshQueued;

    /**
     * Содержимое, показанное <b>вместо</b> активной вкладки: страница состояния адреса (403/404).
     * Вкладкой оно не становится — у адреса, по которому ничего не открылось, вкладки нет, иначе
     * закрывать было бы нечего, а в баре появился бы пункт, за которым нет формы.
     */
    private Component transientContent;

    /** Последнее разосланное значение: {@code showView} вызывается дважды на одно открытие. */
    private String notifiedActiveId;

    private String activeId;

    @Autowired
    public Workspace(WorkspaceManager manager) {
        this.manager = manager;
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        addClassName("workspace");

        titleLabel.addClassName("workspace-title");
        titleLabel.addClassNames("text-l", "font-bold", "px-m", "py-s");
        titleLabel.setWidthFull();

        content.addClassName("workspace-content");
        content.setSizeFull();

        tabs.addClassName("workspace-tabs");
        tabs.setWidthFull();
        tabs.getStyle().set("background", "var(--lumo-contrast-5pct)");
        tabs.getStyle().set("border-top", "1px solid var(--lumo-contrast-10pct)");
        tabs.getStyle().set("min-height", "40px");
        tabs.addSelectedChangeListener(e -> onTabSelected(e.getSelectedTab()));

        add(titleLabel, content, tabs);
        setFlexGrow(1, content);
    }

    public void openComponent(Component view, String entryId, String tabTitle) {
        Entry existing = entries.get(entryId);
        if (existing != null) {
            reveal(existing);
            return;
        }
        if (view instanceof HasSize sized) sized.setSizeFull();
        Tab tab = createTab(entryId, tabTitle);
        Entry entry = new Entry(entryId, tab, view, tabTitle);
        entries.put(entryId, entry);
        tabs.add(tab);
        tabs.setSelectedTab(tab);
        view.setVisible(false);
        content.add(view);
        showView(entry);
    }

    public <T extends Component> void open(Class<T> viewType, String entryId,
                                           String tabTitle, Consumer<T> initializer) {
        Entry existing = entries.get(entryId);
        if (existing != null) {
            reveal(existing);
            return;
        }

        Component view = manager.getOrCreate(entryId, viewType, initializer);
        if (view instanceof HasSize sized) sized.setSizeFull();

        Tab tab = createTab(entryId, tabTitle);
        Entry entry = new Entry(entryId, tab, view, tabTitle);
        entries.put(entryId, entry);
        tabs.add(tab);
        tabs.setSelectedTab(tab);

        view.setVisible(false);
        content.add(view);
        showView(entry);
    }

    /**
     * Ключи вкладок с несохранёнными изменениями — в порядке открытия.
     *
     * <p>Единственный источник истины для всех путей ухода (E2.4): закрытие вкладки, уход по
     * маршруту и перезагрузка страницы спрашивают одно и то же, а не каждый своё. Второй
     * предикат «есть ли грязное» означал бы два ответа на один вопрос.
     */
    public List<String> unsavedEntryIds() {
        return entries.values().stream()
            .filter(entry -> isDirty(entry.getView()))
            .map(Entry::getId)
            .toList();
    }

    /** Есть ли что терять при уходе. */
    public boolean hasUnsavedChanges() {
        return entries.values().stream().anyMatch(entry -> isDirty(entry.getView()));
    }

    /** Сообщение для подтверждения ухода — от первой грязной вкладки. */
    public String unsavedChangesMessage() {
        for (Entry entry : entries.values()) {
            if (entry.getView() instanceof Dirtyable dirty && dirty.isDirty()) {
                return dirty.getCloseConfirmMessage();
            }
        }
        return "Есть несохранённые изменения.";
    }

    /**
     * Можно ли предложить "Сохранить и продолжить": только если сохранить можно <b>все</b> грязные
     * вкладки. В режиме просмотра (E1.5) сохранять нечего, и такая кнопка пообещала бы запись,
     * которую сервер отклонит.
     *
     * <p>«Хотя бы одну» здесь было бы достаточно только в мире одной вкладки: при смеси
     * сохраняемой и несохраняемой грязных вкладок действие выполнилось бы частично, а переход
     * продолжился бы — то есть несохраняемая вкладка потеряла бы правки под видом успешного
     * сохранения остальных.</p>
     */
    public boolean canSaveUnsavedChanges() {
        List<String> dirty = unsavedEntryIds();
        return !dirty.isEmpty() && dirty.stream().allMatch(this::canSave);
    }

    /**
     * Сохранить все грязные вкладки перед уходом.
     *
     * <p>Возвращает {@code false}, если хотя бы одно сохранение не удалось: уход в этом случае
     * не продолжается, а вкладка остаётся открытой вместе с введённым. Сохранение нескольких
     * вкладок именно последовательное: сохранить остальные после неудачи нельзя — провал мог
     * быть про невалидное поле, и дописывать данные в других формах до его исправления значило
     * бы продолжать действие, которое уже не удалось целиком.</p>
     *
     * <p><b>Грязная вкладка, которую нечем сохранить, останавливает уход до первой записи.</b>
     * Такая вкладка (режим просмотра) не станет чище ни от одного {@code doSave()}, поэтому
     * продолжение перехода после «успешного» сохранения остальных означало бы потерю её правок —
     * ровно то, от чего E2.4 уходит. Ответ {@code false} здесь не отказ от работы, а отказ от
     * <b>обещания</b>: сохранить всё нельзя, значит кнопка этого и не предлагает (см.
     * {@link #canSaveUnsavedChanges()}).</p>
     */
    public boolean saveUnsavedChanges() {
        List<String> dirty = unsavedEntryIds();
        if (dirty.stream().anyMatch(entryId -> !canSave(entryId))) {
            refreshUnloadGuard();
            return false;
        }
        for (String entryId : dirty) {
            Entry entry = entries.get(entryId);
            if (entry == null) {
                continue;
            }
            if (!((Savable) entry.getView()).doSave()) {
                refreshUnloadGuard();
                return false;
            }
        }
        refreshUnloadGuard();
        return !hasUnsavedChanges();
    }

    /** Есть ли у вкладки чем сохранять: в режиме просмотра (E1.5) сохранять нечего. */
    private boolean canSave(String entryId) {
        Entry entry = entries.get(entryId);
        return entry != null && entry.getView() instanceof Savable savable && !savable.isReadOnly();
    }

    private static boolean isDirty(Component view) {
        return view instanceof Dirtyable dirty && dirty.isDirty();
    }

    /**
     * Нативная защита от потери данных при перезагрузке/закрытии вкладки браузера (E2.4).
     *
     * <p>Реализуется одним JS-обработчиком, который <b>стоит ровно тогда, когда есть что терять</b>:
     * присутствие обработчика и есть флаг, поэтому сервер не должен передавать клиенту значение
     * dirty — иначе на закрытой чистой вкладке появилось бы ложное предупреждение. Наличием
     * управляет не пользовательское действие, а фактическое состояние форм: {@code isDirty()}
     * спрашивается у каждой вкладки, как и во всех остальных путях ухода.</p>
     *
     * <p><b>Чего эта защита не может.</b> Браузер не спрашивает сервер перед выгрузкой, поэтому в
     * решении участвует состояние на момент последнего ответа серверу: значение, которое браузер
     * ещё не отправил (набрано и курсор не покинул поле), в него не входит. Это свойство самого
     * механизма, а не выбор: альтернатива — догадываться на клиенте, и тогда предупреждение
     * появлялось бы и на чистых вкладках.</p>
     */
    public void refreshUnloadGuard() {
        boolean shouldGuard = hasUnsavedChanges();
        if (shouldGuard == unloadGuardInstalled) {
            return;
        }
        if (!applyUnloadGuard(shouldGuard)) {
            // Исполнить не удалось (элемент ещё не в UI) — запоминать нечего: иначе решение
            // считалось бы принятым, и обработчик не появился бы уже никогда. Следующий признак
            // жизни UI (ввод, открытие или закрытие вкладки, сохранение) повторит попытку.
            return;
        }
        unloadGuardInstalled = shouldGuard;
    }

    /**
     * Отделено ради проверки: решение считается без браузера, JS — только исполнение решения.
     *
     * @return исполнено ли решение; {@code false} означает «некому передать», а не «не надо»
     */
    boolean applyUnloadGuard(boolean installed) {
        if (getUI().isEmpty()) {
            return false;
        }
        if (installed) {
            getElement().executeJs(
                "window." + UNLOAD_GUARD_JS + " = function (e) {"
                    + " e.preventDefault(); e.returnValue = ''; };"
                    + "window.addEventListener('beforeunload', window." + UNLOAD_GUARD_JS + ");");
        } else {
            getElement().executeJs(
                "if (window." + UNLOAD_GUARD_JS + ") {"
                    + " window.removeEventListener('beforeunload', window." + UNLOAD_GUARD_JS + ");"
                    + " window." + UNLOAD_GUARD_JS + " = null; }");
        }
        return true;
    }

    public void close(String entryId) {
        Entry entry = entries.get(entryId);
        if (entry == null) return;

        if (entry.getView() instanceof Dirtyable dirty && dirty.isDirty()) {
            ConfirmDialog dialog = new ConfirmDialog();
            dialog.setHeader("Несохранённые изменения");
            dialog.setText(dirty.getCloseConfirmMessage());

            if (entry.getView() instanceof Savable savable && !savable.isReadOnly()) {
                dialog.setConfirmButton("Сохранить и закрыть", e -> {
                    if (savable.doSave()) doClose(entryId);
                });
                dialog.setCancelButton("Закрыть", e -> doClose(entryId));
                dialog.setRejectButton("Отмена", e -> {});
            } else {
                // Режим просмотра (E1.5) сохранения не предлагает: сохранять нечего, а
                // «Сохранить и закрыть» привело бы к записи, которую сервер отклонит.
                dialog.setConfirmButton("Закрыть", e -> doClose(entryId));
                dialog.setCancelButton("Отмена", e -> {});
            }
            dialog.open();
            return;
        }
        doClose(entryId);
    }

    private void doClose(String entryId) {
        Entry entry = entries.remove(entryId);
        if (entry == null) return;
        // Закрытие сообщается <b>до</b> удаления вкладки, а не после: удаление выбранной вкладки
        // само переключает активную (Tabs выбирает соседнюю) и успевает разослать смену
        // активности, так что «кто был активным до» по этому событию уже не восстановить.
        // Различать закрытие и выбор обязательно — от этого зависит, добавить шаг в историю
        // адреса или заменить текущий.
        notifyEntryClosed(entryId);
        manager.remove(entryId);
        tabs.remove(entry.getTab());
        content.remove(entry.getView());
        refreshUnloadGuard();

        if (entryId.equals(activeId)) {
            if (!entries.isEmpty()) {
                Entry last = entries.values().iterator().next();
                tabs.setSelectedTab(last.getTab());
            } else {
                titleLabel.setText("");
                activeId = null;
                notifyActiveEntry();
            }
        }
    }

    @Override
    public void addEntryClosedListener(Consumer<String> listener) {
        if (listener != null) {
            entryClosedListeners.add(listener);
        }
    }

    private void notifyEntryClosed(String entryId) {
        for (Consumer<String> listener : entryClosedListeners) {
            listener.accept(entryId);
        }
    }

    /**
     * Активировать уже открытую вкладку (E2.3). Незнакомый ключ — ничего не делать: активация
     * приходит из перехода браузера, и выдумывать по ней вкладку значило бы открывать форму,
     * которую пользователь не просил.
     */
    @Override
    public void activate(String entryId) {
        Entry entry = entryId == null ? null : entries.get(entryId);
        if (entry == null) return;
        reveal(entry);
    }

    @Override
    public String activeEntryId() {
        return activeId;
    }

    @Override
    public void addActiveEntryListener(Consumer<String> listener) {
        if (listener != null) {
            activeEntryListeners.add(listener);
        }
    }

    @Override
    public void showTransientContent(Component content) {
        if (content == null) return;
        clearTransientContent();
        transientContent = content;
        Entry active = activeId == null ? null : entries.get(activeId);
        if (active != null) active.getView().setVisible(false);
        if (content instanceof HasSize sized) sized.setSizeFull();
        content.setVisible(true);
        this.content.add(content);
    }

    private void clearTransientContent() {
        if (transientContent == null) return;
        content.remove(transientContent);
        transientContent = null;
    }

    /**
     * Показать уже открытую вкладку — общий путь для повторного открытия, активации и выбора.
     *
     * <p><b>«Уже выбрана» — не то же самое, что «уже видна».</b> Страница состояния адреса
     * ({@link #showTransientContent}) скрывает активную вкладку, но <b>не</b> снимает выбор с её
     * {@code Tab}: повторный выбор той же вкладки не порождает события, и без явного показа
     * содержимого отказ по адресу оставался бы на экране даже после перехода на «Главную».
     * Поэтому решение «что видно» принимается здесь, а не в обработчике события выбора.</p>
     *
     * <p><b>Возврат к вкладке — это и выбор адреса.</b> Пока был показан отказ, адрес в окне
     * описывал не вкладку, а неудавшуюся ссылку; когда содержимое вкладки возвращается, адрес
     * обязан стать её адресом, а без повторного уведомления мост так и оставил бы в окне адрес
     * ошибки. Уведомление повторяется ровно в этом случае — когда было что снимать.</p>
     */
    private void reveal(Entry entry) {
        if (tabs.getSelectedTab() != entry.getTab()) {
            tabs.setSelectedTab(entry.getTab());
            return;
        }
        if (transientContent != null) {
            // Содержимое вкладки возвращается поверх показанной страницы состояния — значит
            // решение «какой адрес в окне» надо принять заново, а не считать его уже принятым.
            notifiedActiveId = null;
        }
        showView(entry);
    }

    /**
     * Уход из host'а: вкладки живут, пока живёт экран, который их показывает.
     *
     * <p><b>Почему это делает host, а не сборщик мусора.</b> Рабочая область — бин уровня UI,
     * а оболочка приложения — нет: переход на другой экран уничтожает {@code MainLayout}, но не
     * UI. Без явного закрытия после ухода оставались бы стоять глобальный {@code beforeunload}
     * (предупреждение при перезагрузке страницы, где несохранённого уже не видно) и сами вкладки:
     * при следующем входе возвращались бы в том числе те, правки которых пользователь отказался
     * сохранять. Поэтому «вкладки принадлежат экрану» — не украшение описания, а контракт.</p>
     *
     * <p><b>Уведомления о закрытии здесь не рассылаются.</b> Мост адреса решает по закрытию, чем
     * должна стать текущая запись истории, а в момент ухода писать адрес нельзя: адрес уже меняет
     * навигация. Следующее открытие вкладки начинается для моста с чистого листа.</p>
     */
    public void closeAll() {
        clearTransientContent();
        for (Entry entry : entries.values()) {
            manager.remove(entry.getId());
            tabs.remove(entry.getTab());
            content.remove(entry.getView());
        }
        entries.clear();
        activeId = null;
        notifiedActiveId = null;
        titleLabel.setText("");
        refreshUnloadGuard();
    }

    /** Разослать смену активной вкладки — только когда значение действительно изменилось. */
    private void notifyActiveEntry() {
        if (java.util.Objects.equals(notifiedActiveId, activeId)) return;
        notifiedActiveId = activeId;
        for (Consumer<String> listener : activeEntryListeners) {
            listener.accept(activeId);
        }
    }

    private Tab createTab(String entryId, String title) {
        Button closeBtn = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> close(entryId));
        closeBtn.getStyle().set("margin", "0").set("padding", "0");
        closeBtn.setWidth("16px");
        closeBtn.setHeight("16px");

        Tab tab = new Tab(new HorizontalLayout(
            new com.vaadin.flow.component.html.Span(title), closeBtn
        ));
        tab.setId(entryId);
        tab.getStyle().set("border", "1px solid var(--lumo-contrast-10pct)");
        tab.getStyle().set("border-bottom", "none");
        tab.getStyle().set("border-left", "none");
        tab.getStyle().set("border-radius", "4px 4px 0 0");
        tab.getStyle().set("background", "var(--lumo-contrast-5pct)");
        tab.getStyle().set("padding", "4px 8px");
        tab.getStyle().set("font-size", "var(--lumo-font-size-s)");
        tab.getStyle().set("margin-top", "4px");
        tab.getStyle().set("transition", "background 0.15s");
        tab.getStyle().set("cursor", "pointer");
        if (tabs.getComponentCount() == 0) {
            tab.getStyle().set("border-left", "1px solid var(--lumo-contrast-10pct)");
        }
        return tab;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        installInputWatch();
        refreshUnloadGuard();
    }

    /**
     * Поставить сторож ввода: клиент сообщает серверу «пользователь набирал», сервер отвечает
     * решением о защите.
     *
     * <p><b>Почему сторож, а не серверный хук.</b> Отсюда убраны два способа, оба проверенные
     * наблюдением и оба негодные. Задание Vaadin «перед каждым ответом», переставляющее себя из
     * себя же, вешает UI: {@code StateTree.runExecutionsBeforeClientResponse} вычерпывает очередь
     * <b>до пустоты</b>, поэтому задание, добавляющее задание, делает её непустой навсегда —
     * сервер не отвечает, страница остаётся пустой. Обработчик запроса сессии
     * ({@code VaadinSession.addRequestHandler}) до UIDL-запросов вообще не доходит — замерено:
     * ни одного вызова на весь сеанс работы с формой. Серверного «каждый запрос» без этих двух
     * способов у приложения нет, поэтому о вводе сообщает клиент — единственный, кто о нём знает
     * раньше сервера.</p>
     *
     * <p><b>Почему DOM-событие, а не проба через промис.</b> Третья редакция — та, где клиент
     * разрешал промис, а сервер на каждом ответе ставил следующий, — не годилась, и это тоже
     * измерено: промис разрешается не вводом, а самим фактом ответа, поэтому каждый ответ
     * заказывал следующий. Счётчик запросов в открытой вкладке формы показал <b>~300 UIDL-запросов
     * в секунду при полном отсутствии ввода</b> (2977 за 10 секунд, 3231 за следующие 10) — UI не
     * висел, но работал как опрос. Слушатель DOM-события такой петли не имеет по построению:
     * клиент отправляет сообщение только тогда, когда событие действительно произошло, а сервер
     * в ответ ничего не заказывает.</p>
     *
     * <p>Сторож не решает — он только поднимает вопрос, и ровно один раз на запрос
     * ({@link #queueUnloadGuardRefresh}). Решение остаётся серверным (что терять, решает
     * {@code hasUnsavedChanges()}), и считается оно в конце того же запроса, когда ввод уже
     * применён: клиентское «я что-то набрал» не может ни переоценить, ни отменить его.</p>
     *
     * <p>Слушатели ставятся на саму рабочую область: {@code input}/{@code change} — composed-события,
     * поэтому они всплывают от полей вложенных форм (свой shadow DOM их не задерживает). Ввод
     * публикуется с задержкой {@link #INPUT_WATCH_DEBOUNCE_MS}: набор текста — это десятки событий,
     * а вопрос к серверу нужен один.</p>
     */
    private void installInputWatch() {
        if (inputWatchInstalled || getUI().isEmpty()) {
            return;
        }
        inputWatchInstalled = true;
        getElement().addEventListener("input",
                event -> queueUnloadGuardRefresh(getUI().orElse(null)))
            .debounce(INPUT_WATCH_DEBOUNCE_MS);
        getElement().addEventListener("change",
                event -> queueUnloadGuardRefresh(getUI().orElse(null)))
            .debounce(INPUT_WATCH_DEBOUNCE_MS);
    }

    /**
     * Заказать один пересчёт на текущий запрос.
     *
     * <p>Отделено ради проверки: заказ не требует ни сессии, ни привязки к UI — только UI, на
     * котором задание и выполнится. Исполнитель живёт вне вычерпывания очереди заданий, и именно
     * это отличает рабочую редакцию от той, что вешала UI.</p>
     */
    void queueUnloadGuardRefresh(UI ui) {
        if (ui == null || unloadGuardRefreshQueued) {
            return;
        }
        unloadGuardRefreshQueued = true;
        ui.beforeClientResponse(this, context -> {
            unloadGuardRefreshQueued = false;
            refreshUnloadGuard();
        });
    }

    private void showView(Entry entry) {
        clearTransientContent();
        titleLabel.setText(entry.getTitle());
        if (activeId != null) {
            Entry prev = entries.get(activeId);
            if (prev != null) prev.getView().setVisible(false);
        }
        entry.getView().setVisible(true);
        if (entry.getView() instanceof HasSize sized) sized.setSizeFull();
        activeId = entry.getId();
        notifyActiveEntry();
        refreshUnloadGuard();
    }

    private void onTabSelected(Tab tab) {
        if (tab == null) return;
        String id = tab.getId().orElse(null);
        if (id == null || id.equals(activeId)) return;

        entries.values().stream()
            .map(Entry::getTab)
            .forEach(t -> t.removeClassName("tab-selected"));
        tab.addClassName("tab-selected");

        Entry entry = entries.get(id);
        if (entry != null) showView(entry);
    }
}

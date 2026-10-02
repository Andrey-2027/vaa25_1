package org.ipro.form.action;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.form.link.ApplicationBasePath;
import org.ipro.form.link.FormLinkService;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Узкий affordance «скопировать ссылку» (E2.1, ADR-0009 §5): кнопка, состояние которой приходит
 * <b>решением</b>, а адрес берётся в момент клика.
 *
 * <p><b>Почему это отдельный тип, а не три кнопки в трёх хостах.</b> Копирование состоит из двух
 * частей, которые нельзя разнести по местам: «видна ли кнопка» — это решение E1
 * ({@link ActionDecision} с причиной {@code NOT_LINKABLE}), а «что именно попадает в буфер» —
 * требовательная к браузеру операция с деградацией. Если бы каждая форма строила это сама, вторая
 * формула доступности (предикат у кнопки) вернулась бы вместе с третьей копией JS, а поведение на
 * HTTP-стенде разошлось бы от формы к форме.</p>
 *
 * <p><b>Правило клавиатуры:</b> доступность не пересчитывается в рендерере. Кнопка лишь
 * перерисовывает уже принятое решение, а в момент клика решение спрашивается снова — тот же
 * инвариант, что у объявленных действий ({@code ListForm#executeDeclaredAction}): устаревшее
 * состояние кнопки или программный клик не отдают ссылку и не открывают недоступное.</p>
 *
 * <p><b>Деградация вместо обещания.</b> Browser Clipboard API доступен только в secure context
 * ({@code https} либо доверенный {@code localhost}). На HTTP-стенде адрес показывается в
 * выделяемом поле: пользователь копирует его вручную. Отказ самого API (например, отсутствие
 * разрешения или потеря фокуса окна) обрабатывается тем же путём — молчаливого «скопировано
 * ничего» здесь нет.</p>
 *
 * <p><b>Адрес — относительный, полный строится в браузере.</b> {@code path} платформа отдаёт как
 * {@code /records/...}; origin и базовый путь развёртывания подставляются клиентом в том же вызове,
 * что и запись в буфер. Поэтому стенд не попадает ни в контракт адреса, ни в тесты, а копируется при
 * этом полный адрес, годный для вставки в письмо или мессенджер.</p>
 *
 * <p><b>Базовый путь — часть адреса, а не украшение.</b> Под развёртыванием {@code /app} адрес
 * формы читается как {@code /app/records/...}; «origin плюс относительный путь» дал бы ссылку
 * <b>вне</b> приложения, и она открывала бы не то, что открыто у отправителя (ADR §5). Тот же
 * базовый путь добавляет и запись истории — источник у них один, {@link ApplicationBasePath}.</p>
 */
public final class CopyLinkButton extends Button {

    /**
     * Запись в буфер на клиенте. Возвращает {@code true} только тогда, когда адрес действительно
     * попал в буфер: «есть API» и «разрешение получено» — разные вещи, и вторая выясняется лишь
     * попыткой.
     */
    private static final String COPY_JS = """
        const address = window.location.origin + $1 + $0;
        if (!window.isSecureContext || !navigator.clipboard || !navigator.clipboard.writeText) {
          return false;
        }
        try {
          await navigator.clipboard.writeText(address);
          return true;
        } catch (refused) {
          return false;
        }
        """;

    /**
     * Полный адрес для деградировавшего пути: показывать относительный путь пользователю нельзя.
     * {@code $1} — базовый путь развёртывания; вместе с origin он даёт тот же адрес, что ушёл бы
     * в буфер обмена.
     */
    private static final String ORIGIN_JS = "return window.location.origin + $1 + $0;";

    private final Supplier<ActionDecision> decision;
    private final Supplier<Optional<String>> address;
    private final Consumer<ActionDecision> onBlocked;

    /**
     * Живая кнопка: присоединена к UI. До этого решение об адресе не спрашивается вовсе.
     *
     * <p>Это не лень, а контракт. Каталог адресов строится <b>один раз после готовности</b>
     * композиции форм, и вопрос к нему во время сборки формы — вопрос раньше срока: набор
     * вариантов ещё не полон, а в режиме с разрешённой регистрацией во время работы он не станет
     * полным никогда. Форма, которую только собирают, не показывает affordance — значит, и спрашивать
     * ей нечего.</p>
     */
    private boolean live;

    private CopyLinkButton(String title,
                           Supplier<ActionDecision> decision,
                           Supplier<Optional<String>> address,
                           Consumer<ActionDecision> onBlocked) {
        super(Objects.requireNonNull(title, "title must not be null"));
        this.decision = Objects.requireNonNull(decision,
            "decision must not be null: доступность ссылки — решение, а не предикат у кнопки");
        this.address = Objects.requireNonNull(address, "address must not be null");
        this.onBlocked = Objects.requireNonNull(onBlocked,
            "onBlocked must not be null: отказ обязан быть объяснён пользователю, а не проглочен");
        addClickListener(event -> execute());
        // До присоединения — скрыта и недоступна: состояние появится вместе с живой кнопкой.
        setVisible(false);
        setEnabled(false);
        addAttachListener(event -> activate());
    }

    /**
     * Кнопка для тулбара списка: ссылка на сам список. Выделенная строка не нужна — адрес списка
     * от неё не зависит, поэтому «нет выделения» не делает ссылку непостроимой.
     */
    public static CopyLinkButton forList(String title, FormLinkService links, Class<?> entityType,
                                         String variant, Supplier<ActionDecision> decision,
                                         Consumer<ActionDecision> onBlocked) {
        Objects.requireNonNull(links, "links must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        return new CopyLinkButton(title, decision,
            () -> links.linkToList(entityType, variant).linkPath(), onBlocked);
    }

    /**
     * Кнопка для подвала карточки: ссылка на открытую запись. {@code recordId} спрашивается в
     * момент клика, потому что у только что созданной записи id появляется лишь после сохранения.
     *
     * <p>{@code null} вместо id — не отсутствие адреса «по типу», а «запись ещё не сохранена»:
     * различие принадлежит {@link FormLinkService} и приходит оттуда причиной
     * {@code MISSING_ID}.</p>
     */
    public static CopyLinkButton forRecord(String title, FormLinkService links, Class<?> entityType,
                                           String variant, Supplier<Long> recordId,
                                           Supplier<ActionDecision> decision,
                                           Consumer<ActionDecision> onBlocked) {
        Objects.requireNonNull(links, "links must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(recordId, "recordId must not be null");
        return new CopyLinkButton(title, decision,
            () -> links.linkToRecord(entityType, recordId.get(), variant).linkPath(), onBlocked);
    }

    /**
     * Кнопка стала частью UI: с этого момента решение об адресе можно спрашивать.
     *
     * <p>Вынесено отдельно от слушателя присоединения, чтобы состояние проверялось тестом без
     * поднятого UI — сам путь «присоединение → вопрос к каталогу адресов» один и тот же.</p>
     */
    void activate() {
        live = true;
        refresh();
    }

    /**
     * Перерисовать состояние из решения: видимость, доступность и подсказку с причиной.
     *
     * <p>Причина в подсказке приходит из решения и не даёт прав: она объясняет уже принятое
     * решение, поэтому этот метод нельзя использовать как второй источник доступности.</p>
     *
     * <p>У неживой кнопки (форма ещё собирается) состояние не пересчитывается: см. {@link #live}.</p>
     */
    public void refresh() {
        if (!live) {
            return;
        }
        applyState(decision.get());
    }

    /** Отображение решения в состояние кнопки — единственное место этого отображения. */
    void applyState(ActionDecision current) {
        Objects.requireNonNull(current, "decision must not be null");
        setVisible(current.visible());
        setEnabled(current.actionable());
        setTooltipText(current.actionable() || current.message().isEmpty()
            ? null
            : current.message());
    }

    /** Клик: решение пересчитывается здесь же, поэтому устаревшая кнопка не отдаёт адрес. */
    private void execute() {
        ActionDecision current = decision.get();
        if (!current.actionable()) {
            onBlocked.accept(current);
            return;
        }
        Optional<String> path = address.get();
        if (path.isEmpty()) {
            // Решение сказало «ссылка построима», а каталог адресов её не построил. Это
            // расхождение двух источников, и молчать о нём нельзя: пользователь нажал кнопку,
            // которая есть, и обязан получить причину, а не тишину.
            onBlocked.accept(ActionDecision.blocked(ActionDecision.Reason.NOT_LINKABLE,
                "Ссылка не построена: адрес формы недоступен в этой сессии"));
            return;
        }
        copyAddress(path.get());
    }

    /**
     * Отдать адрес буферу обмена; при недоступном API — показать его в выделяемом поле.
     *
     * <p>Без активного UI операция невозможна (запись в буфер — действие браузера), поэтому это
     * названный отказ, а не тихий no-op: вызов вне UI-потока означает ошибку вызывающего.</p>
     */
    public static void copyAddress(String path) {
        Objects.requireNonNull(path, "path must not be null");
        UI ui = UI.getCurrent();
        if (ui == null) {
            throw new IllegalStateException("Скопировать ссылку можно только в UI-потоке:"
                + " запись в буфер обмена выполняет браузер, а не сервер. Путь: " + path);
        }
        ui.getPage().executeJs(COPY_JS, path, ApplicationBasePath.current()).then(Boolean.class, copied -> {
            if (Boolean.TRUE.equals(copied)) {
                Notification.show("Ссылка скопирована", 2000, Notification.Position.BOTTOM_START);
            } else {
                showAddress(ui, path);
            }
        });
    }

    /** Деградировавший путь: сначала полный адрес, затем диалог с выделяемым полем. */
    private static void showAddress(UI ui, String path) {
        ui.getPage().executeJs(ORIGIN_JS, path, ApplicationBasePath.current()).then(String.class, absolute -> {
            String address = absolute == null || absolute.isBlank() ? path : absolute;
            showAddressDialog(address);
        });
    }

    /**
     * Диалог с адресом для ручного копирования.
     *
     * <p>Поле только для чтения и с авто-выделением: адрес нельзя исправить «на глаз», а ручное
     * копирование обязано быть одним движением. Подсказка называет причину, по которой браузер не
     * принял адрес сам, — иначе непонятно, почему привычная кнопка ведёт себя иначе.</p>
     */
    private static void showAddressDialog(String address) {
        TextField field = new TextField("Адрес");
        field.setValue(address);
        field.setReadOnly(true);
        field.setWidthFull();
        field.setAutoselect(true);

        Paragraph hint = new Paragraph("Браузер не позволил записать адрес в буфер обмена"
            + " (это доступно только на https или доверенном localhost). Выделите адрес"
            + " и скопируйте его вручную.");

        VerticalLayout content = new VerticalLayout(hint, field);
        content.setPadding(false);
        content.setSpacing(false);

        Dialog dialog = new Dialog(content);
        dialog.setHeaderTitle("Ссылка на форму");
        dialog.getFooter().add(new Button("Закрыть", event -> dialog.close()));
        dialog.open();
        field.focus();
    }
}

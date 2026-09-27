package org.ipro.form.action;

import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilities;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.action.ActionDecision.Reason;
import org.ipro.form.action.ActionRequirement.Level;
import org.ipro.form.link.NotLinkableReason;

import java.util.Objects;

/**
 * Решающая функция E1.1: чистая, тотальная и объяснимая.
 *
 * <p>Функция не имеет состояния, не ходит в Spring, не читает UI и не знает про RLS: всё, что
 * нужно решению, лежит в {@link ActionContext}. Поэтому основная проверка — таблица решений,
 * а не UI-тест (§4.1.6 плана).</p>
 *
 * <p>Порядок проверок фиксирован, чтобы причина была детерминированной:</p>
 * <ol>
 *   <li>явное подавление ({@code visible = false}) — {@code NOT_APPLICABLE};</li>
 *   <li>применимость по типу и правам: обязанной операции у типа нет — {@code TYPE_NOT_SUPPORTED}
 *       (действие скрыто); требованию «операция обязана отсутствовать у типа» или «обязана быть
 *       недоступна» тип/права противоречат — {@code NOT_APPLICABLE} (действие скрыто);</li>
 *   <li>выделение — {@code NO_SELECTION};</li>
 *   <li>права пользователя на обязанную write-операцию — {@code ACCESS_DENIED} (действие видно,
 *       но недоступно: причина остаётся в tooltip);</li>
 *   <li>обязательный контекст — {@code CONTEXT_INCOMPLETE};</li>
 *   <li>адрес у формы отсутствует — {@code NOT_LINKABLE} (действие скрыто, E2.1).</li>
 * </ol>
 *
 * <p><b>Почему отсутствие адреса — последнее и скрытое (E2.1).</b> Последнее — потому что адрес
 * это свойство контракта, а не пользователя: сначала решение отвечает на вопросы «применимо ли
 * действие типу» и «можно ли его выполнить сейчас», и только потом на «есть ли куда дать ссылку».
 * Скрытое — потому что «нет адреса» не станет «есть адрес» от действия пользователя: у
 * {@code SklNomOpa} нет generic записи, а список {@code PrdSpec} не восстанавливается без
 * необъявленного контекста. Серая кнопка обещала бы ссылку, которой платформа не выдаст ни при
 * каком состоянии экрана, — тогда как «сначала выберите строку» действительно ждёт пользователя.
 * Второй случай и остаётся {@code blocked}.</p>
 *
 * <p><b>Почему выделение проверяется раньше прав (E1.2a).</b> Строковые права
 * ({@code update}/{@code delete}) вычисляются только для конкретной строки: без выделения они не
 * «запрещены», а <b>не проверены</b>. Обратный порядок заставил бы список без выделения
 * показывать {@code ACCESS_DENIED} — то есть объяснять недоступную кнопку правами, которых никто
 * не оценивал. Текущее поведение {@code ListForm} именно такое: и включение кнопок, и RLS-проверка
 * строки происходят только при непустом выделении ({@code configureGridSelection}). Для
 * {@code Create} выделение не требуется, поэтому порядок «права → контекст» сохраняется как в
 * {@code ListForm.updateCreateButtonState}.</p>
 *
 * <p>Разделение «скрыто» и «недоступно» — не косметика: {@code SklNomOpa} без generic записи не
 * должен показывать серую «Создать» (её у типа нет), а невыбранная строка не должна скрывать
 * «Изменить» (действие применимо, но ждёт выбора).</p>
 *
 * <p>Пакетной функции «решить всё» здесь сознательно нет: {@link ActionId} не уникален между
 * поверхностями, поэтому ключом пакета был бы не id, а разрешённое описание. Появится
 * потребитель — появится и функция с честным ключом (E1.3).</p>
 */
public final class ActionPolicy {

    private ActionPolicy() {
    }

    /** Одно решение: применимость, доступность и типизированная причина. */
    public static ActionDecision decide(ActionDefinition definition, ActionContext context) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(context, "context must not be null");

        if (!definition.visible()) {
            return ActionDecision.hidden(Reason.NOT_APPLICABLE,
                "Действие отключено для этого типа");
        }

        ActionRequirement requirement = definition.requirement();
        Effective effective = Effective.of(requirement, context);

        ActionDecision structural = structuralDecision(effective, context);
        if (structural != null) {
            return structural;
        }

        if (requirement.requiresSelection() && !context.hasSelection()) {
            return ActionDecision.blocked(Reason.NO_SELECTION, "Сначала выберите строку");
        }

        ActionDecision rights = rightsDecision(effective, context.permission());
        if (rights != null) {
            return rights;
        }

        if (definition.requirement().requiresRequiredContext() && !context.requiredContextComplete()) {
            return ActionDecision.blocked(Reason.CONTEXT_INCOMPLETE,
                "Сначала заполните обязательный контекст");
        }

        if (requirement.linkableRequired()) {
            return linkabilityDecision(context);
        }

        return ActionDecision.allowed();
    }

    /**
     * Решение о действии, которому нужен публичный адрес формы (E2.1).
     *
     * <p>Вход спрашивается только здесь: у остальных действий адрес не участвует в решении, и
     * первое обращение к каталогу адресов (которое его строит) не должно происходить ради кнопки
     * «Создать». Поэтому вход и ленивый.</p>
     *
     * <p>Отсутствующий вход — не «адреса нет», а ошибка композиции: контекст собран без знания о
     * адресах, и выдать по нему решение значило бы либо спрятать действие без причины, либо
     * разрешить его и упасть позже, в момент клика.</p>
     */
    private static ActionDecision linkabilityDecision(ActionContext context) {
        RouteLinkability linkability = context.linkability();
        if (linkability == null) {
            throw new IllegalStateException("Действие требует публичный адрес формы, но контекст"
                + " собран без адресного входа: " + context.entityType().getSimpleName()
                + ". Каталог адресов обязан быть подключён там, где собирается контекст"
                + " (ActionContextProvider), а не спрашиваться решением.");
        }
        NotLinkableReason reason = linkability.notLinkable();
        if (reason == null) {
            return ActionDecision.allowed();
        }
        return ActionDecision.hidden(Reason.NOT_LINKABLE, addressMessage(reason));
    }

    /**
     * Текст отказа по типизированной причине. Все ветви перечислены: новая причина не может
     * появиться без решения о том, что именно увидит пользователь.
     */
    private static String addressMessage(NotLinkableReason reason) {
        return switch (reason) {
            case NOT_PUBLISHED -> "У этого типа нет публичной ссылки";
            case SCENARIO_NOT_ALLOWED -> "Тип не открывается по ссылке: чтение карточки недоступно";
            case REQUIRED_CONTEXT -> "Ссылка не выдаётся: форма требует необъявленного контекста";
            case MISSING_ID -> "Запись ещё не сохранена: адреса у неё нет";
            case INVALID_ID -> "Запись без корректного идентификатора не адресуется";
            case UNEXPECTED_ID -> "Список адресуется без идентификатора записи";
            case UNKNOWN_VARIANT -> "Такого варианта формы нет в реестре";
        };
    }

    /**
     * Действующие требования с учётом состояния объекта (E1.5).
     *
     * <p>Обычное действие проверяет фиксированную операцию. У действия с
     * {@link ActionRequirement#rowStateSelectsOperation()} операция выбирается состоянием
     * объекта: у новой записи действует требование к {@code CREATE}, у существующей — к
     * {@code UPDATE}, а вторая операция в решении не участвует. Иначе {@code crud.save} пришлось бы
     * требовать обе операции сразу — и тип, умеющий только создавать, не смог бы сохранить новую
     * запись, хотя сохранять он как раз умеет.</p>
     */
    private record Effective(Level create, Level update, Level delete, Level detail) {

        static Effective of(ActionRequirement requirement, ActionContext context) {
            if (!requirement.rowStateSelectsOperation()) {
                return new Effective(requirement.create(), requirement.update(),
                    requirement.delete(), requirement.detail());
            }
            boolean rowIsNew = context.rowState() == ActionContext.RowState.NEW;
            return new Effective(
                rowIsNew ? requirement.create() : Level.ANY,
                rowIsNew ? Level.ANY : requirement.update(),
                requirement.delete(), requirement.detail());
        }
    }

    private static ActionDecision structuralDecision(Effective requirement, ActionContext context) {
        EntityCapabilities capabilities = context.capabilities();
        ActionDecision missing = requiredMissing(requirement, capabilities);
        if (missing != null) {
            return missing;
        }
        ActionDecision forbidden = forbiddenPresent(requirement, capabilities);
        if (forbidden != null) {
            return forbidden;
        }
        return unavailablePresent(requirement, context);
    }

    /** Обязанная операция отсутствует у типа: действие скрыто, а не «неактивно». */
    private static ActionDecision requiredMissing(Effective requirement,
                                                  EntityCapabilities capabilities) {
        if (requirement.create() == Level.REQUIRED && !capabilities.allows(DataOperation.CREATE)) {
            return notSupported("CREATE", capabilities);
        }
        if (requirement.update() == Level.REQUIRED && !capabilities.allows(DataOperation.UPDATE)) {
            return notSupported("UPDATE", capabilities);
        }
        if (requirement.delete() == Level.REQUIRED && !capabilities.allows(DataOperation.DELETE)) {
            return notSupported("DELETE", capabilities);
        }
        if (requirement.detail() == Level.REQUIRED && !capabilities.allows(FetchScenario.DETAIL)) {
            return notSupported("DETAIL", capabilities);
        }
        return null;
    }

    /** Требование «операция обязана отсутствовать» нарушено: действие неприменимо. */
    private static ActionDecision forbiddenPresent(Effective requirement,
                                                   EntityCapabilities capabilities) {
        if (requirement.update() == Level.FORBIDDEN && capabilities.allows(DataOperation.UPDATE)) {
            return notApplicable("UPDATE", capabilities);
        }
        if (requirement.create() == Level.FORBIDDEN && capabilities.allows(DataOperation.CREATE)) {
            return notApplicable("CREATE", capabilities);
        }
        if (requirement.delete() == Level.FORBIDDEN && capabilities.allows(DataOperation.DELETE)) {
            return notApplicable("DELETE", capabilities);
        }
        if (requirement.detail() == Level.FORBIDDEN && capabilities.allows(FetchScenario.DETAIL)) {
            return notApplicable("DETAIL", capabilities);
        }
        return null;
    }

    /** Право на обязанную операцию: capability есть, но пользователю она запрещена. */
    private static ActionDecision rightsDecision(Effective requirement,
                                                 ActionPermission permission) {
        if (requirement.create() == Level.REQUIRED && !permission.create()) {
            return ActionDecision.blocked(Reason.ACCESS_DENIED, permission.createReason());
        }
        if (requirement.update() == Level.REQUIRED && !permission.update()) {
            return ActionDecision.blocked(Reason.ACCESS_DENIED, permission.updateReason());
        }
        if (requirement.delete() == Level.REQUIRED && !permission.delete()) {
            return ActionDecision.blocked(Reason.ACCESS_DENIED, permission.deleteReason());
        }
        return null;
    }

    /**
     * Требование {@link Level#UNAVAILABLE}: действие применимо только там, где операция
     * <b>недоступна</b> — её не поддерживает тип или не разрешают права на выбранной строке.
     *
     * <p>В отличие от {@link Level#FORBIDDEN}, который смотрит только на capability, здесь
     * проверяются оба входа: именно этого требовал {@code crud.open}, иначе строка, изменение
     * которой отказано правами, оставалась без пути чтения (E1.2-pilot). Решение от этого не
     * перестаёт быть чистым: оба входа уже вычислены и лежат в {@link ActionContext}.</p>
     */
    private static ActionDecision unavailablePresent(Effective requirement, ActionContext context) {
        EntityCapabilities capabilities = context.capabilities();
        if (requirement.create() == Level.UNAVAILABLE && createAvailable(context)) {
            return appliesOnlyWhenUnavailable("CREATE", capabilities);
        }
        if (requirement.update() == Level.UNAVAILABLE
                && rowOperationAvailable(context, capabilities.allows(DataOperation.UPDATE),
                    context.permission().update())) {
            return appliesOnlyWhenUnavailable("UPDATE", capabilities);
        }
        if (requirement.delete() == Level.UNAVAILABLE
                && rowOperationAvailable(context, capabilities.allows(DataOperation.DELETE),
                    context.permission().delete())) {
            return appliesOnlyWhenUnavailable("DELETE", capabilities);
        }
        if (requirement.detail() == Level.UNAVAILABLE && capabilities.allows(FetchScenario.DETAIL)) {
            return appliesOnlyWhenUnavailable("DETAIL", capabilities);
        }
        return null;
    }

    /** Создание оценивается по классу: его право известно и без выделенной строки. */
    private static boolean createAvailable(ActionContext context) {
        return context.capabilities().allows(DataOperation.CREATE)
            && context.permission().create();
    }

    /**
     * Строчная операция доступна, если её поддерживает тип и — при выделенной строке — разрешают
     * права.
     *
     * <p>Без выделения строчные права не проверены ({@link ActionPermission#NO_ROW_REASON}): это
     * «не отказано, а не оценено», поэтому операция считается доступной, а действие —
     * неприменимым. Иначе состав кнопок зависел бы от того, выбрана ли строка: «Просмотр»
     * появлялся бы в панели до выбора и исчезал при выборе изменяемой строки.</p>
     */
    private static boolean rowOperationAvailable(ActionContext context, boolean capabilityAllows,
                                                 boolean permissionAllows) {
        return capabilityAllows && (!context.hasSelection() || permissionAllows);
    }

    private static ActionDecision appliesOnlyWhenUnavailable(String operation,
                                                             EntityCapabilities capabilities) {
        return ActionDecision.hidden(Reason.NOT_APPLICABLE,
            "Действие применимо только там, где " + operation + " недоступно (policy: "
                + capabilities.reason() + ")");
    }

    private static ActionDecision notSupported(String operation, EntityCapabilities capabilities) {
        return ActionDecision.hidden(Reason.TYPE_NOT_SUPPORTED,
            "Тип не поддерживает " + operation + " (policy: " + capabilities.reason() + ")");
    }

    private static ActionDecision notApplicable(String operation, EntityCapabilities capabilities) {
        return ActionDecision.hidden(Reason.NOT_APPLICABLE,
            "Действие неприменимо: тип поддерживает " + operation);
    }
}

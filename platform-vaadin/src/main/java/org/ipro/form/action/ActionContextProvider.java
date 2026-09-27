package org.ipro.form.action;

import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.FormRouteKind;
import org.ipro.form.link.NotLinkableReason;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.rls.RlsUiGate;
import org.ipro.rls.RlsUiGate.AccessDecision;

import java.util.Objects;

/**
 * Провайдер входов решения (E1.2a): превращает «тип + строка» в нейтральный
 * {@link ActionContext}, по которому работает чистая {@link ActionPolicy}.
 *
 * <p>Провайдер существует затем, чтобы порядок вычисления входов был ровно один. Если бы каждый
 * host собирал {@link ActionPermission} сам ({@code ListForm}, {@code FormCoordinator},
 * {@code ItemFormWrapperView}), платформа получила бы столько же формул прав, сколько у неё
 * хостов, — именно тот шов, который устраняет E1. Решающая функция при этом остаётся чистой: она
 * не знает ни про RLS, ни про каталог дескрипторов, ни про UI.</p>
 *
 * <p><b>Откуда берутся входы.</b> Capabilities — из {@link EntityDescriptorCatalog} (C4, ADR-0007),
 * то есть из той же policy типа, которой пользуется canonical path, а не из второй таблицы
 * исключений в UI. Права — из {@link RlsUiGate}. После C5 (E1.2b) в {@link ActionPermission}
 * добавится resource/action-решение; менять сигнатуры провайдера для этого не потребуется, а
 * вводить permissive-заглушку вместо отсутствующего провайдера C5 — нельзя.</p>
 *
 * <p><b>Строковые права и выделение.</b> {@code canUpdate}/{@code canDelete} принимают строку,
 * поэтому без выделения их нечем вычислить: {@link #classPermissionOf} отдаёт запрет с причиной
 * {@link ActionPermission#NO_ROW_REASON}, а {@link #listContext} собирает контекст с
 * {@code hasSelection = false}. Пользователь при этом видит {@code NO_SELECTION} (проверка
 * выделения идёт раньше прав — см. {@link ActionPolicy}), а не отказ в правах, которого никто не
 * оценивал. Обратное — фабрикация «право разрешено, потому что мы его не проверяли» — здесь
 * сознательно не используется.</p>
 *
 * <p><b>Тип без descriptor.</b> {@link EntityDescriptorCatalog#descriptorOf} не возвращает
 * {@code null}: тип вне каталога получает {@code UNCLASSIFIED} с пустыми capabilities и названной
 * причиной. Поэтому «нет descriptor» превращается не в молчаливое разрешение, а в скрытие generic
 * действий с этой причиной (§2 «названный отказ» плана E1). Обратная сторона: если список работает
 * для неклассифицированного типа, generic CRUD-кнопки у него исчезнут — это проверяется в E1.3 на
 * фактическом составе списков, а не предполагается здесь.</p>
 *
 * <p><b>Потребитель.</b> На этом шаге провайдер ни к одному рендереру не подключён (граница среза
 * E1.2a). Регистрация бина и fail-fast при отсутствии обязательного коллаборатора — вместе с первым
 * потребителем, в E1.3: до него требование «коллаборатор обязателен» нечего проверять, а
 * преждевременная регистрация заставила бы контекст формы требовать каталог дескрипторов там, где
 * он сегодня не нужен.</p>
 */
public final class ActionContextProvider {

    private final EntityDescriptorCatalog descriptorCatalog;
    private final RlsUiGate rlsUiGate;
    private final FormRouteCatalog routes;

    /**
     * Провайдер без адресного входа: контексты не отвечают на вопрос адресуемости (E2.1).
     *
     * <p>Это <b>состояние «не подключено», а не «адресов нет»</b> — то же различение, что у
     * {@link ActionPermission#NO_ROW_REASON}. Действие, объявившее требование адреса, на таком
     * контексте падает с названной причиной композиции ({@link ActionPolicy}), поэтому этот
     * конструктор уместен только там, где адреса не спрашивают вовсе (проверки прав и
     * capability), а не как способ обойтись без каталога.</p>
     *
     * <p>Конструктор оставлен, потому что каталог адресов строится лениво и требует завершённой
     * композиции форм: подключить его везде, где собирается контекст, значило бы требовать адреса
     * там, где решается совсем другой вопрос.</p>
     */
    public ActionContextProvider(EntityDescriptorCatalog descriptorCatalog, RlsUiGate rlsUiGate) {
        this(descriptorCatalog, rlsUiGate, null);
    }

    /**
     * Провайдер с адресным входом (E2.1): каталог адресов становится источником ответа для действий,
     * которые его требуют. Список спрашивает про {@code lists}, строка — про {@code records};
     * привязка делается здесь, потому что вид адреса известен тому, кто собирает контекст.
     */
    public ActionContextProvider(EntityDescriptorCatalog descriptorCatalog, RlsUiGate rlsUiGate,
                                 FormRouteCatalog routes) {
        this.descriptorCatalog = Objects.requireNonNull(descriptorCatalog,
            "descriptorCatalog must not be null: capability типа — не опция, а первый вход решения");
        this.rlsUiGate = Objects.requireNonNull(rlsUiGate,
            "rlsUiGate must not be null: без него решение не знает прав и обязано падать, а не разрешать");
        this.routes = routes;
    }

    /** Effective capabilities типа — из той же policy, что читает canonical path. */
    public EntityCapabilities capabilitiesOf(Class<?> entityType) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        EntityDescriptor descriptor = descriptorCatalog.descriptorOf(entityType);
        return descriptor.capabilities();
    }

    /**
     * Права уровня класса: определён только {@code create}. Используется для списка без выделения.
     *
     * <p>{@code canCreate} в текущем RLS-гейте уже устроен как проверка «нового значения измерения»,
     * то есть строки ему не нужны — поэтому создание здесь вычисляется полностью.</p>
     */
    public ActionPermission classPermissionOf(Class<?> entityType) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        AccessDecision create = rlsUiGate.canCreate(entityType);
        return ActionPermission.forClass(create.allowed(), create.reason());
    }

    /**
     * Права на конкретную строку: {@code create} — по классу, {@code update}/{@code delete} — по
     * строке.
     *
     * @param row загруженная строка; {@code null} не допускается — для «строки нет» есть
     *            {@link #classPermissionOf}
     */
    public ActionPermission rowPermissionOf(Class<?> entityType, Object row) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(row, "row must not be null: строковые права вычисляются только"
            + " для конкретной строки, иначе это classPermissionOf");
        AccessDecision create = rlsUiGate.canCreate(entityType);
        AccessDecision update = rlsUiGate.canUpdate(row);
        AccessDecision delete = rlsUiGate.canDelete(row);
        return new ActionPermission(create.allowed(), create.reason(),
            update.allowed(), update.reason(), delete.allowed(), delete.reason());
    }

    /**
     * Контекст без выделенной строки: строковые права не проверяются, объект считается новым.
     *
     * <p>{@link ActionContext.RowState#NEW} — потому что строка не выбрана: действие, которое
     * зависит от состояния объекта ({@code crud.save}), в этом контексте означает создание,
     * а не правку неизвестно какой записи. Для действий списка это ничего не меняет: у них
     * операция фиксирована, а строковые действия до прав не доходят — выделения нет.</p>
     */
    public ActionContext listContext(Class<?> entityType, String variant,
                                     boolean requiredContextComplete) {
        return new ActionContext(entityType, variant, capabilitiesOf(entityType),
            classPermissionOf(entityType), false, requiredContextComplete,
            ActionContext.RowState.NEW, listLinkability(entityType, variant));
    }

    /**
     * Контекст выбранной строки. {@code row == null} означает «выделения нет» и даёт
     * {@link #listContext}: так вызывающему коду не нужно самому решать, чем строка без выделения
     * отличается от строки с пустыми правами.
     */
    public ActionContext rowContext(Class<?> entityType, String variant, Object row,
                                    boolean requiredContextComplete) {
        if (row == null) {
            return listContext(entityType, variant, requiredContextComplete);
        }
        ActionContext.RowState rowState = rowStateOf(row);
        return new ActionContext(entityType, variant, capabilitiesOf(entityType),
            rowPermissionOf(entityType, row), true, requiredContextComplete, rowState,
            rowState == ActionContext.RowState.NEW
                // Несохранённая карточка: адреса записи ещё нет — но это ответ про запись,
                // а не про тип, и спрашивать каталог об этом незачем.
                ? RouteLinkability.blocked(NotLinkableReason.MISSING_ID)
                : itemLinkability(entityType, variant));
    }

    /** Адресуемость списка: {@code null}, если адресный вход не подключён. */
    private RouteLinkability listLinkability(Class<?> entityType, String variant) {
        return routes == null
            ? null
            : () -> routes.notLinkable(entityType, FormRouteKind.LIST, variant);
    }

    /** Адресуемость карточки существующей записи: {@code null}, если адресный вход не подключён. */
    private RouteLinkability itemLinkability(Class<?> entityType, String variant) {
        return routes == null
            ? null
            : () -> routes.notLinkable(entityType, FormRouteKind.ITEM, variant);
    }

    /**
     * Состояние объекта по его идентичности: строка без {@code id} ещё не сохранена, поэтому
     * карточка для неё создаёт запись, а не правит существующую.
     *
     * <p>Признак тот же, которым пользовался прежний доступ-биндер
     * ({@code id == null} ⇒ запись не в режим просмотра, а на создание), и он остался на месте —
     * но теперь он один и участвует в решении, а не в каждом host'е отдельно. Строка, не
     * реализующая {@link IdentifiableEntity}, считается существующей: «нет идентичности» — это
     * отсутствие доказательства новизны, а не новизна.</p>
     */
    private static ActionContext.RowState rowStateOf(Object row) {
        return row instanceof IdentifiableEntity entity && entity.getId() == null
            ? ActionContext.RowState.NEW
            : ActionContext.RowState.EXISTING;
    }
}

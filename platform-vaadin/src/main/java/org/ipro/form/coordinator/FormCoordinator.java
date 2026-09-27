package org.ipro.form.coordinator;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import org.ipro.form.FieldFactory;
import org.ipro.form.FormSaveHandler;
import org.ipro.form.FormSaveResult;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.SelectionForm;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.action.ReadOnlyReason;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.link.FormRoute;
import org.ipro.form.link.FormRouteKind;
import org.ipro.form.link.OpenResult;
import org.ipro.form.coordinator.FormOpenMode;
import org.ipro.form.registry.FormContext;
import org.ipro.form.registry.FormRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ipro.form.registry.FormResolver;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.crud.BaseService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.host.FormRouteUrlBridge;
import org.ipro.form.spi.WorkspaceGateway;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.rls.RlsUiGate;
import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.core.MdcKeys;
import org.ipro.telemetry.core.TelemetryBridge;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.AccessDeniedException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Координатор форм. Центральный диспетчер для управления жизненным циклом форм.
 *
 * Основные функции:
 *   1. Открывает ListForm, ItemForm, SelectionForm из метаданных или кастомных вариантов
 *   2. Динамически находит нужный Service через Spring context
 *   3. Управляет callback'ами между формами (цепочки вызовов)
 *   4. Отслеживает сессии форм для поддержки parent-child связей
 *   5. Поддерживает варианты форм через FormRegistry и FormResolver
 *
 * Использование:
 * <pre>
 * // Из View:
 * coordinator.openListForm(Nomenclature.class);
 *
 * // Открыть кастомный вариант:
 * coordinator.openListForm(Nomenclature.class, "archived", Map.of("year", 2023));
 *
 * // Программно открыть форму редактирования:
 * coordinator.openItemForm(Nomenclature.class, id, saved -> {
 *     // callback после сохранения
 * });
 * </pre>
 *
 * <p>D3.5.3: бин живёт в UI-scope — рабочая область и режим открытия принадлежат конкретному
 * Vaadin UI, а не всей JVM. Синглтоном с mutable состоянием это был межсессионный дефект:
 * параллельные пользователи перезаписывали чужую рабочую область. Публичный навигационный
 * контракт — {@link FormNavigator}; сам координатор остаётся реализацией.</p>
 *
 * <p>D3.5.3-fix: рабочая область больше не приходит через мутабельный {@code setWorkspace(...)}:
 * UI-scoped бин {@code WorkspaceGateway} берётся лениво в момент открытия ({@code ObjectProvider}),
 * поэтому «режим вкладок» определяется самой композицией UI, а не порядком вызовов из вьюх.</p>
 */
/**
 * Регистрация бина — в {@code FormAutoConfiguration}, {@code @ConditionalOnMissingBean} +
 * {@code @UIScope} <b>на фабричном методе</b>: аннотация класса при {@code @Bean}-регистрации не
 * наследуется, поэтому скоуп объявлять здесь было бы недостаточно (D3.5.5).
 */
public class FormCoordinator implements FormNavigator {

    private static final Logger log = LoggerFactory.getLogger(FormCoordinator.class);

    private final MetadataResolver metadataResolver;
    private final FieldFactory fieldFactory;
    private final ApplicationContext applicationContext;
    private final FormResolver formResolver;
    private final ServiceLocator serviceLocator;
    private final org.ipro.form.spi.FormSettingsStore formSettingsStore;
    private final org.ipro.form.spi.GridViewStore gridViewStore;
    private final RlsUiGate rlsUiGate;
    private final ItemFormAccessBinder itemFormAccessBinder;
    private final ActionRegistry actionRegistry;
    private final ActionContextProvider actionContextProvider;
    private final ActionHandlerRegistry actionHandlerRegistry;
    private final FormLinkService formLinkService;
    private final org.ipro.crud.EntityCopyService entityCopyService;
    private final org.ipro.form.TableSectionFactory tableSectionFactory;

    // Рабочая область (1С-стиль) — UI-scoped бин приложения. Берётся лениво, в момент
    // открытия: координатор сам UI-scoped, поэтому провайдер всегда отдаёт область
    // текущего UI, а не первого записавшего.
    private final ObjectProvider<WorkspaceGateway> workspaceGateways;

    // Мост адреса и вкладок (E2.3). Опционален по той же причине, что и рабочая область: он нужен
    // только тому приложению, которое объявило route host. Без него вкладка открывается как
    // обычно, а адрес не меняется — молчаливой подмены навигации нет.
    private final ObjectProvider<FormRouteUrlBridge> routeUrlBridges;

    // Режим открытия форм элементов (по умолчанию — Dialog). UI-scoped состояние:
    // оставлен мутабельным осознанно — удаление поля потребовало бы нового правила
    // (например, presence workspace), которое молча переключило бы все ItemForm
    // из диалогов во вкладки. Поведение не меняем в D3.5.
    private FormOpenMode itemFormOpenMode = FormOpenMode.DIALOG;

    public FormCoordinator(MetadataResolver metadataResolver,
                           FieldFactory fieldFactory,
                           ApplicationContext applicationContext,
                           FormResolver formResolver,
                           ServiceLocator serviceLocator,
                            org.ipro.form.spi.FormSettingsStore formSettingsStore,
                            org.ipro.form.spi.GridViewStore gridViewStore,
                            RlsUiGate rlsUiGate,
                            ItemFormAccessBinder itemFormAccessBinder,
                            ActionRegistry actionRegistry,
                            ActionContextProvider actionContextProvider,
                            ActionHandlerRegistry actionHandlerRegistry,
                            FormLinkService formLinkService,
                            org.ipro.crud.EntityCopyService entityCopyService,                             org.ipro.form.TableSectionFactory tableSectionFactory,
                             ObjectProvider<WorkspaceGateway> workspaceGateways,
                             ObjectProvider<FormRouteUrlBridge> routeUrlBridges) {
        // D3.5.3: обязательные коллабораторы — fail-fast с причиной, никаких permissive
        // fallback (тихий null здесь превращался бы в «кнопки без прав» или формы без
        // резолва). D3.5.3-fix: проверены не 5 из 12, а все обязательные — включая
        // applicationContext (без него форма собирается частично) и itemFormAccessBinder
        // (гейт прав). Опциональны ровно три вещи, и каждая названа явно: UI-состояние
        // (WorkspaceGateway/режим) и два APP_SPI-адаптера (сторы видов и настроек).
        this.metadataResolver = Objects.requireNonNull(
            metadataResolver, "metadataResolver must not be null");
        this.fieldFactory = Objects.requireNonNull(
            fieldFactory, "fieldFactory must not be null");
        this.applicationContext = Objects.requireNonNull(applicationContext,
            "applicationContext must not be null: через него разрешаются FormSaveHandler"
                + " и LookupService — без него форма собирается частично");
        this.formResolver = Objects.requireNonNull(
            formResolver, "formResolver must not be null");
        this.serviceLocator = Objects.requireNonNull(
            serviceLocator, "serviceLocator must not be null");
        // Сторы видов/настроек — опциональные адаптеры приложения (APP_SPI):
        // ListForm.setViewSupport документированно работает без них (кнопка «Виды» скрыта,
        // компактность и вид по умолчанию не восстанавливаются). Это осознанный no-op, а не
        // забытая проверка: наличие адаптера — решение приложения, а не требование платформы.
        this.formSettingsStore = formSettingsStore;
        this.gridViewStore = gridViewStore;
        this.rlsUiGate = Objects.requireNonNull(
            rlsUiGate, "rlsUiGate must not be null: UI gate отражает серверное решение, "
                + "работать без него — показывать действия без проверки прав");
        this.itemFormAccessBinder = Objects.requireNonNull(itemFormAccessBinder,
            "itemFormAccessBinder must not be null: он решает, можно ли создавать и править"
                + " запись, работать без него — показывать форму без проверки прав");
        this.actionRegistry = Objects.requireNonNull(actionRegistry,
            "actionRegistry must not be null: без него список не знает, какие действия ему"
                + " объявлены, и обязан падать, а не показывать CRUD по догадке");
        this.actionContextProvider = Objects.requireNonNull(actionContextProvider,
            "actionContextProvider must not be null: он единственный собирает входы решения"
                + " (capability типа и права), без него список вернулся бы к своей формуле прав");
        // E1.6a: реестр исполнителей прикладных действий. Пустой — это состояние "приложение
        // действий не объявляет", а не отсутствие модели: без обязательного реестра список
        // молча терял бы прикладные кнопки, и именно этот дефект здесь и закрывается.
        this.actionHandlerRegistry = Objects.requireNonNull(actionHandlerRegistry,
            "actionHandlerRegistry must not be null: без него объявленные прикладные действия"
                + " исчезают из списка молча, а не с причиной");
        this.formLinkService = Objects.requireNonNull(formLinkService,
            "formLinkService must not be null: без него формы, открытые координатором, остались бы"
                + " без публичного адреса — а список и карточка обязаны вести себя одинаково");
        this.entityCopyService = Objects.requireNonNull(entityCopyService,
            "entityCopyService must not be null: копирование карточки со строками идёт через него");
        this.tableSectionFactory = Objects.requireNonNull(tableSectionFactory,
            "tableSectionFactory must not be null: подключение и копирование табличных частей идёт"
                + " через него");
        this.workspaceGateways = workspaceGateways;
        this.routeUrlBridges = routeUrlBridges;
    }

    /**
     * Сообщить мосту адреса, что вкладка открывается по адресу — <b>до</b> активации вкладки.
     *
     * <p>Порядок здесь и есть смысл вызова: смена активной вкладки — событие, на которое мост уже
     * обязан знать адрес. Записав адрес после открытия, получили бы в адресе {@code "/"} вместо
     * формы и вторую запись в истории на один переход.</p>
     *
     * <p>Мост может отсутствовать: тогда адрес просто не меняется. Это не ошибка, а другая
     * конфигурация — приложение без route host.</p>
     */
    private void registerRoutedTab(String entryId, FormRoute route) {
        if (routeUrlBridges == null) {
            return;
        }
        FormRouteUrlBridge bridge = routeUrlBridges.getIfAvailable();
        if (bridge != null) {
            bridge.routedTabOpened(entryId, route);
        }
    }

    /**
     * Рабочая область текущего UI, если приложение её предоставило.
     *
     * <p>Нет области — нет вкладок: это нормальный случай для потребителя платформы без
     * Workspace (тесты, headless-сценарии). Отказ формулируется на месте вызова, с причиной.</p>
     */
    private WorkspaceGateway workspaceOrNull() {
        return workspaceGateways == null ? null : workspaceGateways.getIfAvailable();
    }

    /**
     * Режим открытия форм элементов (ItemForm).
     * По умолчанию {@link FormOpenMode#DIALOG}.
     *
     * Пример — переключить на 1С-стиль (вкладки):
     * <pre>
     * coordinator.setItemFormOpenMode(FormOpenMode.WORKSPACE_TAB);
     * </pre>
     */
    public void setItemFormOpenMode(FormOpenMode mode) {
        this.itemFormOpenMode = mode;
    }

    public FormOpenMode getItemFormOpenMode() {
        return itemFormOpenMode;
    }

    /**
     * Получить FormRegistry для доступа к кастомным View.
     */
    public FormRegistry getFormRegistry() {
        return formResolver.getFormRegistry();
    }

    // === Открытие форм ===

    /**
     * Открывает форму списка (ListForm) в Workspace как вкладку (1С-стиль).
     * Если Workspace не установлен — возвращает ListForm для ручного добавления.
     *
     * @param entityClass класс сущности (например, Nomenclature.class)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> void openListForm(Class<T> entityClass) {
        openListForm(entityClass, null, null);
    }

    /**
     * Открывает форму списка (ListForm) с указанным вариантом и параметрами.
     *
     * @param entityClass класс сущности
     * @param variant имя варианта (null = default)
     * @param parameters параметры для кастомной формы
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T extends IdentifiableEntity, ID> void openListForm(Class<T> entityClass,
                                                                  String variant,
                                                                  Map<String, Object> parameters) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        String entryId = listEntryId(entityClass, variant, parameters);
        String title = meta.getListFormTitle()
            + (variant != null ? " (" + variant + ")" : "");

        WorkspaceGateway workspace = workspaceOrNull();
        if (workspace == null) {
            throw new IllegalStateException("В текущем UI нет рабочей области: вкладки — состояние"
                + " UI, поэтому открывать список некуда. Либо приложение предоставляет UI-scoped"
                + " бин WorkspaceGateway, либо вызывающий использует createListForm(...) и сам"
                + " встраивает форму (это не ошибка конфигурации, а другая точка входа).");
        }

        openListInWorkspace(entityClass, variant, parameters, entryId, title, workspace);
    }

    /**
     * Открытие списка в уже найденной рабочей области: один путь для обычного вызова и для
     * route-входа (E2.2). Вынесено затем, чтобы открытие по адресу не заводило вторую сборку
     * формы со своими обходами custom view и wrapper'а.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> void openListInWorkspace(
            Class<T> entityClass, String variant, Map<String, Object> parameters,
            String entryId, String title, WorkspaceGateway workspace) {
        Class<? extends com.vaadin.flow.component.Component> customViewClass =
            formResolver.getFormRegistry().getListFormViewClass(entityClass, variant);
        org.ipro.form.registry.FormFactory customViewFactory =
            formResolver.getFormRegistry().getListFormViewFactory(entityClass, variant);
        if (customViewFactory != null) {
            com.vaadin.flow.component.Component view = customViewFactory.create(buildListFormContext(entityClass, variant, parameters));
            workspace.openComponent(view, entryId, title);
        } else if (customViewClass != null) {
            workspace.open((Class) customViewClass, entryId, title, view -> { });
        } else {
            // Generic ListForm открывается через стандартный wrapper.
            workspace.open(ListFormWrapper.class, entryId, title, wrapper -> {
                ListForm<T, ID> listForm = createListForm(entityClass, variant, parameters, null);
                wrapper.setContent(listForm);
            });
        }
    }

    /**
     * Стабильный ключ вкладки карточки: один и тот же для обычного открытия и для адреса.
     *
     * <p>Ключ выведен из типа, варианта и id, а не из внешнего ключа адреса: иначе запись,
     * открытая из списка и по ссылке, дала бы две вкладки одной и той же формы. Соответствие
     * «адрес → класс» при этом однозначно и обеспечивается каталогом маршрутов.</p>
     */
    private static String itemEntryId(Class<?> entityClass, String variant, Object id) {
        return "item-" + entityClass.getSimpleName().toLowerCase()
            + (variant != null ? "-" + variant : "")
            + (id != null ? "-" + id.toString() : "-new");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private FormContext buildListFormContext(Class<?> entityClass, String variant, Map<String, Object> parameters) {
        return FormContext.builder(entityClass)
            .parameters(parameters)
            .metadataResolver(metadataResolver)
            .fieldFactory(fieldFactory)
            .entityLookup(applicationContext.getBean(org.ipro.crud.LookupService.class))
            .formNavigator(this)
            .parameter("variant", variant)
            .build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void initializeListFormView(com.vaadin.flow.component.Component view, Class<?> entityClass, String variant,
                                        Map<String, Object> parameters) {
        // Старые View-классы создаются без параметров; новые используют FormFactory.
    }

    /**
     * Стабильный ключ вкладки: одинаковый вариант, открытый с разными параметрами связи,
     * должен быть разными вкладками.
     */
    private String listEntryId(Class<?> entityClass, String variant, Map<String, Object> parameters) {
        String base = entityClass.getSimpleName().toLowerCase()
            + (variant != null ? "-" + variant : "");
        if (parameters == null || parameters.isEmpty()) {
            return base;
        }
        String canonical = parameters.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + stableParameterValue(e.getValue()))
            .collect(Collectors.joining("&"));
        return base + "-" + Integer.toHexString(canonical.hashCode());
    }

    private String stableParameterValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof IdentifiableEntity entity) {
            return entity.getClass().getName() + "#" + entity.getId();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                .map(e -> String.valueOf(e.getKey()) + "=" + stableParameterValue(e.getValue()))
                .sorted()
                .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .map(this::stableParameterValue)
                .collect(Collectors.joining(",", "[", "]"));
        }
        if (value instanceof Class<?> type) {
            return type.getName();
        }
        return String.valueOf(value);
    }

    /**
     * Открывает форму списка (ListForm) для указанной сущности.
     * Возвращает готовый компонент для встраивания в View.
     *
     * @param entityClass класс сущности (например, Nomenclature.class)
     * @return ListForm компонент
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> ListForm<T, ID> createListForm(Class<T> entityClass) {
        return createListForm(entityClass, null, null, null);
    }

    /**
     * Создаёт ListForm указанного варианта с параметрами открытия для встраивания в View.
     * В отличие от {@link #openListForm(Class, String, Map)} не открывает Workspace-вкладку.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T extends IdentifiableEntity, ID> ListForm<T, ID> createListForm(
            Class<T> entityClass, String variant, Map<String, Object> parameters) {
        return createListForm(entityClass, variant, parameters, null);
    }

    /**
     * Открывает форму списка (ListForm) для указанной сущности с возможностью кастомизации.
     *
     * @param entityClass класс сущности
     * @param configurator callback для кастомизации ListForm после автогенерации колонок
     * @return ListForm компонент
     *
     * Пример:
     * <pre>
     * ListForm&lt;Nomenclature, Long&gt; form = coordinator.createListForm(
     *     Nomenclature.class,
     *     listForm -> {
     *         // Добавляем вычисляемую колонку
     *         Grid&lt;Nomenclature&gt; grid = listForm.getGrid();
     *         grid.addColumn(n -> n.getCode() + " (" + n.getUnitOfMeasurement().getShortCode() + ")")
     *             .setHeader("Код + ЕИ");
     *
     *         // Добавляем кастомную кнопку
     *         Button exportBtn = new Button("Экспорт", VaadinIcon.DOWNLOAD.create());
     *         listForm.getToolbar().add(exportBtn);
     *     }
     * );
     * </pre>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> ListForm<T, ID> createListForm(
            Class<T> entityClass,
            Consumer<ListForm<T, ID>> configurator) {
        return createListForm(entityClass, null, null, configurator);
    }

    /**
     * Создает форму списка с поддержкой вариантов и параметров.
     *
     * @param entityClass класс сущности
     * @param variant имя варианта (null = default)
     * @param parameters параметры для кастомной формы
     * @param configurator callback для кастомизации
     * @return ListForm компонент
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> ListForm<T, ID> createListForm(
            Class<T> entityClass,
            String variant,
            Map<String, Object> parameters,
            Consumer<ListForm<T, ID>> configurator) {

        // Используем FormResolver для поиска формы (кастомная или generic)
        ListForm<T, ID> form = formResolver.resolveListForm(entityClass, variant, parameters);

        // «Ещё» справа над гридом (общий поиск, условное форматирование) — крайняя кнопка
        // тулбара. Предметные кнопки сюда больше не добавляет координатор (E1.6b): сквозной
        // шов «форма → отчёт» удалён, объявленные действия рисует сама форма из решения, до
        // разделителя, поэтому «Ещё» остаётся последней.
        form.installMoreMenu();

        // Включаем диалог "Настройка колонок" (нужен резолвер для полей связанных сущностей)
        form.setMetadataResolver(metadataResolver);

        // Выбор ссылочных полей отбора — через форму выбора (SelectionForm), как в остальном
        // приложении, а не комбобокс с полной загрузкой справочника.
        form.setEntitySelector((entityClass1, onSelect) ->
            formResolver.resolveSelectionForm((Class) entityClass1, (java.util.function.Consumer) onSelect).open());

        // Диалог выбора для SELECT-контрола панели контекст-фильтров — та же форма выбора,
        // что и у ссылочных полей; ручной ввод в самом поле идёт через LookupService.
        form.setSelectionFormProvider((entityClass1, onSelect) ->
            formResolver.resolveSelectionForm((Class) entityClass1, onSelect));

        // Источник данных для условий отбора по ссылкам. Сохранённое значение условия — это
        // display-строка, и компилятор FilterGrid сопоставляет её с вариантами, которые отдаёт
        // ListForm, поэтому источник должен быть тот же, что у формы выбора, иначе значение
        // «не найдётся среди вариантов поля». Сами варианты с D3.5.2b строятся из ссылок дерева
        // ограниченным lookup-чтением (FilterLookupOptions), а не выгрузкой справочника;
        // полный выбор значения идёт через форму выбора выше.
        form.setLookupService(applicationContext.getBean(org.ipro.crud.LookupService.class));

        // Поддержка сохранённых видов (GridFormView) + вид по умолчанию за пользователем.
        // Ключ различает варианты формы: у "archived"-варианта своя настройка/свои виды.
        form.setViewSupport(gridViewStore, formSettingsStore,
            entityClass.getSimpleName() + (variant != null ? "." + variant : ""));

        // Решения по действиям списка (E1.3): один решатель на список. Форма получает готовые
        // решения и не считает права сама — раньше «Создать» смотрело только RLS, а capability
        // типа не учитывалось вовсе, и кнопки могли обещать операцию, которой у типа нет.
        ActionResolver resolver = listActionResolver(entityClass, variant);
        form.setActionResolver(resolver);
        // Ссылка на список (E2.1): адрес списка не зависит от выделенной строки, поэтому кнопка
        // появляется так же, как остальные действия, — по решению, а не по предикату у кнопки.
        form.setFormLinkService(formLinkService);
        // Навигацию для объявленных действий даёт координатор: само действие её не ищет и
        // ApplicationContext не знает (E1.6a).
        form.setFormNavigator(this);

        // Панель контекст-фильтров списка: ряд варианта либо общий ряд сущности;
        // пусто — панели нет.
        form.setContextFilters(
            formResolver.getFormRegistry().resolveListContextFilters(entityClass, variant));

        // Настраиваемые команды списка (row-команды).
        // Составной View может отключить глобальные команды во вложенном ListForm,
        // оставив только свои локальные действия.

        // Настройка callback'ов для кнопок. Создание подставляет заполненные обязательные
        // контекст-значения (например, журнал) как начальные значения новой записи.
        form.setOnAdd(entity -> openItemForm(entityClass, null, null, saved -> form.refresh(),
            Map.of("initialValues", form.getRequiredContextValues())));
        form.setOnEdit(entity -> openRowAction(entityClass, entity, resolver,
            saved -> form.refresh()));
        form.setOnDelete(entity -> form.refresh());

        // Копирование использует то же решение формы, что и остальные CRUD-действия:
        // состояние кнопки и проверка при клике учитывают в том числе readOnly.
        form.setOnCopy(selected -> openCopyForm(entityClass, (ID) selected.getId(),
            saved -> form.refresh()));

        // Применяем кастомизацию ДО вызова build()
        if (configurator != null) {
            form.setAfterColumnsConfigured(() -> configurator.accept(form));
        }

        return form;
    }

    /**
     * Открывает форму элемента (ItemForm).
     * Режим определяется {@link #getItemFormOpenMode()}:
     * <ul>
     *   <li>{@link FormOpenMode#DIALOG} — модальный Dialog (по умолчанию)</li>
     *   <li>{@link FormOpenMode#WORKSPACE_TAB} — вкладка в Workspace (1С-стиль)</li>
     * </ul>
     *
     * @param entityClass класс сущности
     * @param id          ID записи для редактирования (null = создание новой)
     * @param onSaved     callback после успешного сохранения
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> void openItemForm(Class<T> entityClass,
                                                                  ID id,
                                                                  Consumer<T> onSaved) {
        openItemForm(entityClass, null, id, onSaved);
    }

    /**
     * Открывает форму элемента (ItemForm) с указанным вариантом.
     *
     * @param entityClass класс сущности
     * @param variant имя варианта (null = default)
     * @param id          ID записи для редактирования (null = создание новой)
     * @param onSaved     callback после успешного сохранения
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> void openItemForm(Class<T> entityClass,
                                                                  String variant,
                                                                  ID id,
                                                                  Consumer<T> onSaved) {
        openItemForm(entityClass, variant, id, onSaved, null);
    }

    /**
     * Открывает форму элемента (ItemForm) с указанным вариантом и параметрами открытия.
     *
     * <p>{@code parameters} — произвольные бизнес-параметры конкретного открытия (PR-1.5,
     * драйвер «по роли»: например, {@code "readOnlySections" = List.of(PrdSpecOper.class)} —
     * секция операций в режиме «только просмотр», материалы редактируются). Фабрика варианта
     * читает их из {@code FormContext.getParameter(...)}.</p>
     *
     * @param entityClass класс сущности
     * @param variant имя варианта (null = default)
     * @param id          ID записи для редактирования (null = создание новой)
     * @param onSaved     callback после успешного сохранения
     * @param parameters  параметры открытия формы (могут быть null)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T extends IdentifiableEntity, ID> void openItemForm(Class<T> entityClass,
                                                                  String variant,
                                                                  ID id,
                                                                  Consumer<T> onSaved,
                                                                  Map<String, Object> parameters) {
        Map<String, String> context = id != null
                ? Map.of(MdcKeys.ENTITY_ID, id.toString())
                : Map.of();
        try (OperationScope scope = TelemetryBridge.beginOperation(
                "openItemForm:" + entityClass.getSimpleName(), context)) {
            EntityMetadataInfo meta = metadataResolver.resolve(entityClass);

            if (itemFormOpenMode == FormOpenMode.WORKSPACE_TAB) {
                openItemFormInWorkspace(entityClass, variant, id, onSaved, meta, parameters);
            } else {
                openItemFormAsDialog(entityClass, variant, id, onSaved, meta, parameters);
            }
        }
    }

    /**
     * Маркер открытия в режиме просмотра: карточка открывается без правки, потому что изменения
     * не допускает само решение (E1.3), а не потому что RLS «отказала». Живёт рядом с остальными
     * параметрами открытия ({@code presetEntity}, {@code initialValues}, {@code readOnlySections}).
     */
    public static final String READ_ONLY_PARAMETER = "readOnly";

    /** Запрошен ли явный просмотр параметрами открытия. */
    public static boolean isReadOnlyRequested(Map<String, Object> parameters) {
        return parameters != null && Boolean.TRUE.equals(parameters.get(READ_ONLY_PARAMETER));
    }

    /**
     * Решатель действий списка: тем же решателем проверяется «Создать» в тулбаре и открытие
     * карточки создания. Карточка создания — продолжение действия списка, поэтому и решение
     * берётся у списка, а не у подвала карточки.
     */
    private ActionResolver listActionResolver(Class<?> entityClass, String variant) {
        return new ActionResolver(actionRegistry, actionContextProvider, actionHandlerRegistry,
            ActionSurface.LIST_TOOLBAR, entityClass, variant);
    }

    /** Решатель действий карточки (E1.5): по нему решается, правится открытая запись или нет. */
    private ActionResolver itemActionResolver(Class<?> entityClass, String variant) {
        return new ActionResolver(actionRegistry, actionContextProvider, actionHandlerRegistry,
            ActionSurface.ITEM_FOOTER, entityClass, variant);
    }

    /**
     * Строковое открытие списка (E1.3): режим выбирается тем же решением, что показано на кнопках
     * формы. Изменение — если оно доступно, иначе просмотр, иначе причина пользователю. Проверка
     * идёт в момент клика, а не при сборке списка, поэтому устаревшее состояние кнопки не открывает
     * недоступную строку.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> void openRowAction(Class<T> entityClass, T entity,
                                                                  ActionResolver resolver,
                                                                  Consumer<T> onSaved) {
        ActionDecision edit = resolver.decide(CrudAction.EDIT, entity, true);
        if (edit.actionable()) {
            openItemForm(entityClass, null, (ID) entity.getId(), onSaved);
            return;
        }
        ActionDecision open = resolver.decide(CrudAction.OPEN, entity, true);
        if (open.actionable()) {
            openItemForm(entityClass, null, (ID) entity.getId(), onSaved,
                Map.of(READ_ONLY_PARAMETER, Boolean.TRUE));
            return;
        }
        showError(edit.message());
    }

    /**
     * Открывает ItemForm в диалоге (оригинальное поведение).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> void openItemFormAsDialog(
            Class<T> entityClass, String variant, ID id, Consumer<T> onSaved, EntityMetadataInfo meta,
            Map<String, Object> parameters) {

        BaseService<T, ID> service = findService(entityClass);
        ItemForm<T> form = formResolver.resolveItemForm(entityClass, variant, id, parameters);
        form.setSaveHandler((FormSaveHandler) applicationContext.getBean(FormSaveHandler.class));

        // Создание без права — форму не открываем вовсе (Фаза 4; с E1.5 это решение по
        // crud.create, а не только RLS-проверка класса).
        if (id == null) {
            String reason = itemFormAccessBinder.blockReasonIfCannotCreate(
                listActionResolver(entityClass, variant));
            if (reason != null) {
                showError(reason);
                return;
            }
        }

        if (id != null) {
            Optional<T> existing = service.findById(id);
            if (existing.isPresent()) {
                form.setEntity(existing.get());
                itemFormAccessBinder.applyReadOnlyIfCannotSave(form,
                    itemActionResolver(entityClass, variant));
            } else {
                showError("Запись не найдена: " + id);
                return;
            }
        } else {
            Object preset = parameters == null ? null : parameters.get("presetEntity");
            if (preset != null) {
                T presetEntity = entityClass.cast(preset);
                form.setEntityFactory(() -> presetEntity);
            }
            form.initializeNewEntity(initialValuesOf(parameters));
            seedCopiedRows(form, parameters);
        }

        if (isReadOnlyRequested(parameters)) {
            // Явный просмотр: права не отказывают — режим запрошен решением списка (E1.3),
            // поэтому карточка открывается без правки и без сообщения о правах.
            form.setReadOnly(ReadOnlyReason.requested());
        }

        Dialog dialog = new Dialog();
        String variantSuffix = variant != null ? " (" + variant + ")" : "";
        dialog.setHeaderTitle((isReadOnlyRequested(parameters) ? "Просмотр: "
            : id == null ? "Создание: " : "Редактирование: ")
            + meta.getItemFormTitle() + variantSuffix);
        dialog.setWidth("800px");
        dialog.setHeight("600px");
        dialog.setModal(true);
        dialog.setDraggable(true);
        dialog.setResizable(true);
        dialog.add(form);

        form.setOnSave(() -> {
            FormSaveResult<T> result = form.save();
            if (result.success()) {
                dialog.close();
                if (onSaved != null) {
                    onSaved.accept(((FormSaveResult.Success<T>) result).saved());
                }
                showSuccess("Сохранено");
            } else if (result instanceof FormSaveResult.Conflict<T> conflict) {
                // Конфликт @Version: диалог остается открыт, изменения не потеряны —
                // пользователь перечитывает данные и повторяет сохранение.
                showError(String.join("\n", conflict.messages()));
            } else if (result instanceof FormSaveResult.Failure<T> failure) {
                showError(String.join("\n", failure.messages()));
                // форма остаётся открыта; in-memory rows сохраняются
            }
        });

        form.setOnCancel(() -> {
            if (form.isDirty()) {
                ConfirmDialog confirm = new ConfirmDialog();
                confirm.setHeader("Несохранённые изменения");
                confirm.setText(form.getCloseConfirmMessage());
                if (!form.isReadOnly()) {
                    confirm.setConfirmButton("Сохранить и закрыть", e -> form.doSave());
                    confirm.setCancelButton("Закрыть", e -> dialog.close());
                    confirm.setRejectButton("Отмена", e -> {});
                } else {
                    // Карточка просмотра не предлагает сохранение (E1.5): сохранять нечего, а
                    // «Сохранить и закрыть» вело бы к отклонённому сервером записи.
                    confirm.setConfirmButton("Закрыть", e -> dialog.close());
                    confirm.setCancelButton("Отмена", e -> {});
                }
                confirm.open();
            } else {
                dialog.close();
            }
        });
        form.withDefaultButtons();
        // Ссылка на запись (E2.1). Перерисовывать после сохранения нечего: успешное сохранение
        // закрывает диалог, а неуспешное адреса не создаёт.
        ItemFormLinkAffordance.attach(form, formLinkService,
            itemActionResolver(entityClass, variant), entityClass, variant);

        dialog.open();
    }

    /**
     * Открывает ItemForm как вкладку в Workspace (1С-стиль).
     * Поддерживает dirty/save-подтверждения при закрытии вкладки.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> void openItemFormInWorkspace(
            Class<T> entityClass, String variant, ID id, Consumer<T> onSaved, EntityMetadataInfo meta,
            Map<String, Object> parameters) {

        T existing = null;
        if (id == null) {
            // Создание без права — вкладку не открываем вовсе (Фаза 4; с E1.5 — решение по crud.create).
            String reason = itemFormAccessBinder.blockReasonIfCannotCreate(
                listActionResolver(entityClass, variant));
            if (reason != null) {
                showError(reason);
                return;
            }
        } else {
            // Preflight существования ДО открытия вкладки: нет строки (в том числе её скрыла
            // row-level RLS) — вкладка не создаётся и пустая карточка не показывается. Раньше
            // это выяснялось уже внутри wrapper'а, который всё равно добавлял форму, а
            // ItemForm.getEntity() умел лениво создать новый объект — то есть отказ в чтении мог
            // превратиться в создание записи.
            existing = findService(entityClass).findById(id).orElse(null);
            if (existing == null) {
                showError("Запись не найдена: " + id);
                return;
            }
        }

        WorkspaceGateway workspace = workspaceOrNull();
        if (workspace == null) {
            throw new IllegalStateException("Режим WORKSPACE_TAB требует UI-scoped бина"
                + " WorkspaceGateway: без рабочей области у текущего UI вкладку открыть некуда."
                + " Либо приложение предоставляет Workspace, либо режим открытия — DIALOG.");
        }

        String entryId = itemEntryId(entityClass, variant, id);

        String variantSuffix = variant != null ? " (" + variant + ")" : "";
        String title = (id == null ? "Создание: " : "Редактирование: ")
            + meta.getItemFormTitle() + variantSuffix;

        // Генерируем callback для refresh списка
        // onSaved уже содержит логику (form.refresh()), просто пробрасываем
        Consumer<T> tabOnSaved = saved -> {
            if (onSaved != null) onSaved.accept(saved);
        };

        // Прочитанная запись передаётся в сборку формы: wrapper не читает её повторно, поэтому
        // окно гонки между preflight и инициализацией отсутствует. Catch — защита на случай,
        // если запись всё же исчезнет; тогда WorkspaceManager не кеширует компонент, вкладка не
        // добавляется, а пользователь видит сообщение вместо пустой карточки.
        T loaded = existing;
        try {
            workspace.open(ItemFormWrapperView.class, entryId, title, view ->
                view.init(entityClass, variant, id, loaded, tabOnSaved, () -> workspace.close(entryId),
                    parameters));
        } catch (ItemFormWrapperView.RecordUnavailableException race) {
            showError(race.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Route-вход (E2.2, ADR-0009 §6)
    //
    // Эти два метода — единственное отличие «открытия по адресу» от обычной навигации: режим
    // открытия задаётся формой входа, а не мутабельной настройкой, и отказ возвращается
    // значением, а не сообщением. Ни уведомлений, ни исключений наружу: host рисует страницу
    // состояния по исходу, а показ Java-исключения и молчаливый переход на home запрещены.
    // ---------------------------------------------------------------------

    /**
     * Открыть существующую запись по адресу (ITEM) во вкладке Workspace.
     *
     * <p><b>Проверка до открытия.</b> Canonical чтение выполняется здесь и <b>до</b> создания
     * вкладки: нет строки (в том числе её скрыла row-level RLS) — вкладки нет. Это тот же
     * fail-closed preflight, что закрыт E2.0a для обычного открытия, но теперь его исход
     * типизирован, а не показан сообщением.</p>
     *
     * <p><b>Повторная оценка при активации уже открытой вкладки.</b> Чтение выполняется на каждый
     * вызов, даже если вкладка уже есть: права и видимость строки могли измениться за время
     * сеанса, и активация вкладки с прошлого раза не должна считаться разрешением. Вкладка при
     * повторном открытии не пересобирается — по тому же {@code entryId} активируется уже
     * собранная форма, а отказ (403/404) возвращается без активации.</p>
     *
     * <p><b>Режим просмотра здесь не решается.</b> «Запись есть, {@code UPDATE} запрещён» — это
     * {@code Opened} с read-only карточкой по правилам E1, а не отдельный исход: карточка
     * собирается тем же путём и тем же binder'ом, что и при обычном открытии.</p>
     *
     * @param entityClass persistence-класс из записи каталога маршрутов (не из строки адреса)
     * @param route       разобранный адрес: он же источник id и варианта, он же идентичность вкладки
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public OpenResult openRoutedRecord(Class<?> entityClass, FormRoute route) {
        Objects.requireNonNull(entityClass, "entityClass must not be null");
        Objects.requireNonNull(route, "route must not be null");
        if (route.kind() != FormRouteKind.ITEM) {
            throw new IllegalArgumentException("Запись открывается ITEM-адресом, а не " + route.kind()
                + ": вид адреса выбирает вызывающий, и ошибаться в нём нельзя молча");
        }
        if (!IdentifiableEntity.class.isAssignableFrom(entityClass)) {
            return OpenResult.invalidRoute("Тип " + entityClass.getSimpleName()
                + " не имеет идентичности: адрес записи для него не публикуется");
        }

        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        Long id = route.id();

        Object existing;
        try {
            // Capability и RLS проверяются внутри canonical чтения: сценарий DETAIL объявлен
            // каталогом, поэтому здесь остаётся только чтение — и оно идёт до вкладки.
            existing = readExisting(entityClass, id);
        } catch (AccessDeniedException denied) {
            return OpenResult.forbidden(route, "Чтение " + entityClass.getSimpleName()
                + " запрещено для текущего пользователя");
        } catch (RuntimeException failure) {
            // Детали остаются на сервере: сообщение страницы состояния стабильно и не рассказывает
            // пользователю ни про драйвер, ни про конфигурацию (E2.2, ADR-0009 §6).
            log.error("Чтение {} для route-входа завершилось ошибкой",
                entityClass.getSimpleName(), failure);
            return OpenResult.unavailable(route,
                "Форму открыть не удалось: чтение записи завершилось ошибкой");
        }
        if (existing == null) {
            return OpenResult.notFound(route);
        }

        WorkspaceGateway workspace = workspaceOrNull();
        if (workspace == null) {
            return OpenResult.unavailable(route, "В текущем UI нет рабочей области: прямой адрес"
                + " всегда открывает форму в Workspace (ADR-0009 §3), а вкладку открывать некуда");
        }

        String entryId = itemEntryId(entityClass, route.variant(), id);
        registerRoutedTab(entryId, route);
        String title = "Редактирование: " + meta.getItemFormTitle()
            + (route.variant() != null ? " (" + route.variant() + ")" : "");
        Object loaded = existing;
        try {
            workspace.open(ItemFormWrapperView.class, entryId, title, view ->
                initRouted(view, entityClass, route.variant(), id, loaded,
                    () -> workspace.close(entryId)));
        } catch (ItemFormWrapperView.RecordUnavailableException race) {
            // Запись исчезла между preflight и сборкой: тот же 404, что и у отсутствующей строки
            // (вкладка не добавлена и компонент не закеширован — см. E2.0a).
            return OpenResult.notFound(route);
        }
        return OpenResult.opened(route);
    }

    /**
     * Canonical чтение существующей записи для route-входа — тем же вызовом, что у обычного
     * открытия карточки, а не похожим: у списка и у ссылки обязан быть один путь чтения, иначе
     * «одна и та же строка» перестаёт быть одним и тем же.
     *
     * <p>Тип и id известны каталогу и адресу, но не компилятору, поэтому чтение живёт в обобщённой
     * точке: без неё пришлось бы выбирать между двумя перегрузками {@code findById} по статическому
     * типу аргумента, а это уже другой вызов.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> T readExisting(Class<?> entityClass, ID id) {
        BaseService<T, ID> service = findService((Class) entityClass);
        return service.findById(id).orElse(null);
    }

    /**
     * Инициализация вкладки по адресу: единственное место, где generic'и wrapper'а размыкаются.
     *
     * <p>Тип и id известны каталогу и адресу, но не компилятору: каталог отдаёт {@code Class<?>},
     * потому что маршруты выводятся из метаданных, а не из типизированного вызова. Обратная
     * проверка здесь не нужна и не делается повторно — вид формы подтвердил каталог, существование
     * строки — preflight; остаётся только передать их в сборку без второго реестра.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void initRouted(ItemFormWrapperView view, Class<?> entityClass, String variant,
                                   Object id, Object loaded, Runnable closeCallback) {
        view.init((Class) entityClass, variant, id, (IdentifiableEntity) loaded, null,
            closeCallback, null);
    }

    /**
     * Открыть самостоятельный список по адресу (LIST) во вкладке Workspace.
     *
     * <p>Список открывается тем же путём, что обычный вызов ({@code openListForm}), поэтому
     * custom view, вариант и wrapper не дублируются. Обязательный контекст сюда не доходит:
     * такой список не линкабелен (§5) и отсечён каталогом до чтения.</p>
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public OpenResult openRoutedList(Class<?> entityClass, FormRoute route) {
        Objects.requireNonNull(entityClass, "entityClass must not be null");
        Objects.requireNonNull(route, "route must not be null");
        if (route.kind() != FormRouteKind.LIST) {
            throw new IllegalArgumentException("Список открывается LIST-адресом, а не " + route.kind());
        }
        if (!IdentifiableEntity.class.isAssignableFrom(entityClass)) {
            return OpenResult.invalidRoute("Тип " + entityClass.getSimpleName()
                + " не имеет списка: адрес для него не публикуется");
        }

        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        WorkspaceGateway workspace = workspaceOrNull();
        if (workspace == null) {
            return OpenResult.unavailable(route, "В текущем UI нет рабочей области: прямой адрес"
                + " всегда открывает форму в Workspace (ADR-0009 §3), а вкладку открывать некуда");
        }

        String entryId = listEntryId(entityClass, route.variant(), null);
        registerRoutedTab(entryId, route);
        String title = meta.getListFormTitle()
            + (route.variant() != null ? " (" + route.variant() + ")" : "");
        try {
            openListInWorkspace((Class) entityClass, route.variant(), null, entryId, title, workspace);
        } catch (AccessDeniedException denied) {
            return OpenResult.forbidden(route, "Чтение списка " + entityClass.getSimpleName()
                + " запрещено для текущего пользователя");
        } catch (RuntimeException failure) {
            // Тот же контракт, что и у карточки: пользователю — стабильное сообщение, журналу —
            // причина.
            log.error("Открытие списка {} для route-входа завершилось ошибкой",
                entityClass.getSimpleName(), failure);
            return OpenResult.unavailable(route,
                "Список открыть не удалось: чтение завершилось ошибкой");
        }
        return OpenResult.opened(route);
    }

    /**
     * Открывает форму выбора (SelectionForm) в диалоге — точка входа для программных вызовов
     * "открыть выбор из произвольного места". {@code EntityField} не использует этот метод —
     * он обращается к {@code SelectionFormAssembler} напрямую (короче путь, не тянет
     * Workspace-специфичную логику координатора).
     *
     * @param entityClass класс сущности для выбора
     * @param onSelected  callback при выборе записи
     */
    public <T extends IdentifiableEntity> void openSelectionForm(Class<T> entityClass,
                                                                   Consumer<T> onSelected) {
        openSelectionForm(entityClass, onSelected, null);
    }

    /**
     * Открыть выбор с параметрами открытия; поддерживается сид-фильтр
     * ({@code parameters.get("seedFilter")}) — выбор открывается предотфильтрованным.
     */
    public <T extends IdentifiableEntity> void openSelectionForm(Class<T> entityClass,
                                                                   Consumer<T> onSelected,
                                                                   Map<String, Object> parameters) {
        openSelectionForm(entityClass, null, onSelected, parameters);
    }

    /**
     * Открыть выбор по именованному варианту (набор колонок/диалог из
     * {@code SelectionFormCustomization}); неизвестный вариант — ошибка конфигурации.
     */
    public <T extends IdentifiableEntity> void openSelectionForm(Class<T> entityClass,
                                                                   String variant,
                                                                   Consumer<T> onSelected,
                                                                   Map<String, Object> parameters) {
        Map<String, Object> filters = parameters != null
            && parameters.get("contextFilters") instanceof Map<?, ?> map
            ? map.entrySet().stream().collect(Collectors.toMap(
                e -> String.valueOf(e.getKey()), Map.Entry::getValue)) : Map.of();
        SelectionForm<T> form = formResolver.resolveSelectionForm(entityClass, variant, onSelected, filters);
        form.open();
    }

    /**
     * Открывает карточку создания как копию существующей записи (1С-стиль «Копировать»):
     * шапка и все табличные части переносятся (id/version/аудит/номера/unique-коды сброшены),
     * источник в БД не меняется. RLS-гейт создания применяется штатно.
     *
     * @param entityClass класс сущности
     * @param sourceId ID прототипа
     * @param onSaved callback после успешного сохранения
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> void openCopyForm(Class<T> entityClass, ID sourceId,
                                                                Consumer<T> onSaved) {
        BaseService<T, ID> service = findService(entityClass);
        Optional<T> source = service.findById(sourceId);
        if (source.isEmpty()) {
            showError("Запись не найдена: " + sourceId);
            return;
        }
        T headerCopy = entityCopyService.copyEntity(source.get());
        Map<Class<?>, List<?>> rowsCopy = tableSectionFactory.copyRowsFor(
            entityClass, source.get(), headerCopy, entityCopyService);
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("presetEntity", headerCopy);
        if (!rowsCopy.isEmpty()) {
            parameters.put("presetRows", rowsCopy);
        }
        openItemForm(entityClass, null, null, onSaved, parameters);
    }

    /**
     * Подсеивает скопированные строки в секции новой карточки (после attach секций
     * и инициализации шапки). Секции без строк и отсутствующие секции пропускаются;
     * засеянные помечаются изменёнными (молчаливая потеря при закрытии недопустима).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void seedCopiedRows(org.ipro.form.builtin.ItemForm<?> form, Map<String, Object> parameters) {
        if (parameters == null) return;
        Object raw = parameters.get("presetRows");
        if (!(raw instanceof Map<?, ?> map)) return;
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof Class<?> rowClass)
                    || !IdentifiableEntity.class.isAssignableFrom(rowClass)) continue;
            if (!(entry.getValue() instanceof List<?> rows) || rows.isEmpty()) continue;
            org.ipro.form.builtin.ItemTable table;
            try {
                table = form.tableSection((Class) rowClass);
            } catch (IllegalArgumentException noSuchSection) {
                continue;
            }
            table.applyPersistedRows(form.peekEntity(), (List) rows);
            table.markDirty();
        }
    }

    /**
     * Начальные значения новой записи из параметров открытия ({@code "initialValues"} —
     * карта имя поля → значение). Кладёт вызывающая сторона создания (например, список
     * подставляет заполненные обязательные контекст-значения). Только создание (id == null);
     * редактирование существующей записи начальных значений не принимает.
     */
    public static Map<String, Object> initialValuesOf(Map<String, Object> parameters) {
        if (parameters == null) return null;
        Object raw = parameters.get("initialValues");
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                "initialValues must be Map<String, Object>, got " + raw.getClass().getName());
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    // === Поиск сервисов ===

    /**
     * Находит data handle для указанной сущности через {@link ServiceLocator}.
     *
     * <p>C4.7: резолв по имени бина удалён. Сначала берётся типизированный application
     * service, зарегистрированный под своим entity type, затем — canonical generic handle,
     * если descriptor типа его допускает; иначе {@link ServiceLocator} отказывает с
     * реальной причиной.</p>
     */
    private <T extends IdentifiableEntity, ID> BaseService<T, ID> findService(Class<T> entityClass) {
        return serviceLocator.findService(entityClass);
    }

    private void showError(String message) {
        Notification.show(message, 5000, Notification.Position.MIDDLE)
            .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void showSuccess(String message) {
        Notification.show(message, 2000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    // === Доступ к метаданным ===

    public MetadataResolver getMetadataResolver() {
        return metadataResolver;
    }

    public FieldFactory getFieldFactory() {
        return fieldFactory;
    }

    public FormResolver getFormResolver() {
        return formResolver;
    }
}

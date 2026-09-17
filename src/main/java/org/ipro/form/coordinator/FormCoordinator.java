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
import org.ipro.form.coordinator.FormOpenMode;
import org.ipro.form.registry.FormContext;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.registry.ListCommand;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.crud.BaseService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.spi.ListFormToolbarContributor;
import org.ipro.form.spi.WorkspaceGateway;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.rls.RlsUiGate;
import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.core.MdcKeys;
import org.ipro.telemetry.core.TelemetryBridge;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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
 */
@Component
public class FormCoordinator {

    private final MetadataResolver metadataResolver;
    private final FieldFactory fieldFactory;
    private final ApplicationContext applicationContext;
    private final FormResolver formResolver;
    private final ServiceLocator serviceLocator;
    private final org.ipro.form.spi.FormSettingsStore formSettingsStore;
    private final org.ipro.form.spi.GridViewStore gridViewStore;
    private final java.util.List<ListFormToolbarContributor> toolbarContributors;
    private final RlsUiGate rlsUiGate;
    private final ItemFormAccessBinder itemFormAccessBinder;
    private final org.ipro.crud.EntityCopyService entityCopyService;
    private final org.ipro.form.TableSectionFactory tableSectionFactory;

    // Опциональная ссылка на Workspace для открытия форм в Tab (1С-стиль)
    private WorkspaceGateway workspace;

    // Режим открытия форм элементов (по умолчанию — Dialog)
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
                            org.ipro.crud.EntityCopyService entityCopyService,
                            org.ipro.form.TableSectionFactory tableSectionFactory,
                            java.util.List<ListFormToolbarContributor> toolbarContributors) {
        this.metadataResolver = metadataResolver;
        this.fieldFactory = fieldFactory;
        this.applicationContext = applicationContext;
        this.formResolver = formResolver;
        this.serviceLocator = serviceLocator;
        this.formSettingsStore = formSettingsStore;
        this.gridViewStore = gridViewStore;
        this.rlsUiGate = rlsUiGate;
        this.itemFormAccessBinder = itemFormAccessBinder;
        this.entityCopyService = entityCopyService;
        this.tableSectionFactory = tableSectionFactory;
        this.toolbarContributors =
            toolbarContributors == null ? java.util.List.of() : toolbarContributors;
    }

    /**
     * Установить Workspace для открытия форм в вкладках (1С-стиль).
     * Если не установлен — формы открываются в Dialog.
     */
    public void setWorkspace(WorkspaceGateway workspace) {
        this.workspace = workspace;
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
    public <T extends IdentifiableEntity, ID> void openListForm(Class<T> entityClass,
                                                                  String variant,
                                                                  Map<String, Object> parameters) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        String entryId = listEntryId(entityClass, variant, parameters);
        String title = meta.getListFormTitle()
            + (variant != null ? " (" + variant + ")" : "");

        if (workspace != null) {
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
        } else {
            throw new IllegalStateException(
                "Workspace not set. Call coordinator.setWorkspace(workspace) before using openListForm().");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private FormContext buildListFormContext(Class<?> entityClass, String variant, Map<String, Object> parameters) {
        return FormContext.builder(entityClass)
            .parameters(parameters)
            .metadataResolver(metadataResolver)
            .fieldFactory(fieldFactory)
            .lookupService(applicationContext != null
                ? applicationContext.getBean(org.ipro.crud.LookupService.class) : null)
            .applicationContext(applicationContext)
            .parameter("variant", variant)
            .parameter("coordinator", this)
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
        // Сквозные добавки тулбара (печать, экспорт и т.п.): бины приложения,
        // платформа знает только SPI. Составной View может не вызывать createListForm
        // и тем самым не получить глобальные добавки — это осознанно.
        for (ListFormToolbarContributor contributor : toolbarContributors) {
            contributor.contribute(form, entityClass, variant);
        }

        // «Ещё» справа над гридом (общий поиск, условное форматирование) — после
        // всех сквозных добавок, чтобы кнопка оставалась крайней справа.
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

        // Резолв значений ссылочных полей при компиляции фильтра (displayName → сущность):
        // нужен тот же источник данных, что и у формы выбора, иначе выбранное значение
        // «не найдётся среди вариантов поля».
        if (applicationContext != null) {
            form.setLookupService(applicationContext.getBean(org.ipro.crud.LookupService.class));
        }

        // Поддержка сохранённых видов (GridFormView) + вид по умолчанию за пользователем.
        // Ключ различает варианты формы: у "archived"-варианта своя настройка/свои виды.
        form.setViewSupport(gridViewStore, formSettingsStore,
            entityClass.getSimpleName() + (variant != null ? "." + variant : ""));

        // RLS-права на кнопки (Фаза 3): «Создать»/«Изменить»/«Удалить» по RlsUiGate.
        form.setRlsUiGate(rlsUiGate);

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
        form.setOnEdit(entity -> openItemForm(entityClass, null, (ID) entity.getId(), saved -> form.refresh()));
        form.setOnDelete(entity -> form.refresh());

        // Копирование объекта целиком (шапка + строки): только там, где доступно создание.
        // Required-контекст копию не гейтит — у прототипа значения свои.
        if (itemFormAccessBinder.blockReasonIfCannotCreate(entityClass) == null) {
            Button copyButton = new Button("Копировать", VaadinIcon.COPY.create());
            copyButton.setEnabled(false);
            copyButton.setTooltipText("Копировать выбранную запись со строками");
            form.getGrid().asSingleSelect().addValueChangeListener(
                e -> copyButton.setEnabled(e.getValue() != null));
            copyButton.addClickListener(e -> {
                T selected = form.getSelectedItem();
                if (selected != null && selected.getId() != null) {
                    openCopyForm(entityClass, (ID) selected.getId(), saved -> form.refresh());
                }
            });
            form.getToolbar().addComponentAtIndex(
                form.getToolbar().indexOf(form.getAddButton()) + 1, copyButton);
        }

        // Применяем кастомизацию ДО вызова build()
        if (configurator != null) {
            form.setAfterColumnsConfigured(() -> configurator.accept(form));
        }

        return form;
    }

    /** Навешивает зарегистрированные команды списка (row-команды) в тулбар ListForm. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T extends IdentifiableEntity, ID> void applyListCommands(
            ListForm<T, ID> form, Class<T> entityClass, String variant) {
        if (applicationContext == null) {
            return;
        }
        applicationContext.getBeanProvider(org.ipro.form.registry.ListCommandRegistry.class).ifAvailable(registry -> {
            for (org.ipro.form.registry.ListCommand<?> command : registry.byEntity(entityClass)) {
                if (!command.appliesToVariant(variant)) {
                    continue;
                }

                Button button = new Button(command.title());
                if (command.iconName() != null) {
                    try {
                        button.setIcon(new Icon(VaadinIcon.valueOf(command.iconName())));
                    } catch (IllegalArgumentException ignored) {
                        // неверное имя иконки — просто без иконки
                    }
                }

                org.ipro.form.registry.ListCommandContext initialContext = new org.ipro.form.registry.ListCommandContext(form, this);
                boolean enabled = !command.requiresSelection()
                    && command.isEnabled(initialContext);
                button.setEnabled(enabled);
                form.getGrid().asSingleSelect().addValueChangeListener(e -> {
                    org.ipro.form.registry.ListCommandContext current = new org.ipro.form.registry.ListCommandContext(form, this);
                    boolean hasSelection = !command.requiresSelection() || e.getValue() != null;
                    button.setEnabled(hasSelection && command.isEnabled(current));
                });
                form.addContextChangeListener(ignored -> {
                    org.ipro.form.registry.ListCommandContext current = new org.ipro.form.registry.ListCommandContext(form, this);
                    boolean hasSelection = !command.requiresSelection()
                        || current.selectedItem() != null;
                    button.setEnabled(hasSelection && command.isEnabled(current));
                });

                button.addClickListener(e -> {
                    org.ipro.form.registry.ListCommandContext current = new org.ipro.form.registry.ListCommandContext(form, this);
                    if ((!command.requiresSelection() || current.selectedItem() != null)
                            && command.isEnabled(current)) {
                        command.execute(current);
                    }
                });
                form.getToolbar().add(button);
            }
        });
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
     * Открывает ItemForm в диалоге (оригинальное поведение).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> void openItemFormAsDialog(
            Class<T> entityClass, String variant, ID id, Consumer<T> onSaved, EntityMetadataInfo meta,
            Map<String, Object> parameters) {

        BaseService<T, ID> service = findService(entityClass);
        ItemForm<T> form = formResolver.resolveItemForm(entityClass, variant, id, parameters);
        form.setSaveHandler((FormSaveHandler) applicationContext.getBean(FormSaveHandler.class));

        // Создание без права — форму не открываем вовсе (Фаза 4).
        if (id == null) {
            String reason = itemFormAccessBinder.blockReasonIfCannotCreate(entityClass);
            if (reason != null) {
                showError(reason);
                return;
            }
        }

        if (id != null) {
            Optional<T> existing = service.findById(id);
            if (existing.isPresent()) {
                form.setEntity(existing.get());
                itemFormAccessBinder.applyReadOnlyIfCannotUpdate(form);
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

        Dialog dialog = new Dialog();
        String variantSuffix = variant != null ? " (" + variant + ")" : "";
        dialog.setHeaderTitle((id == null ? "Создание: " : "Редактирование: ")
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
                confirm.setConfirmButton("Сохранить и закрыть", e -> form.doSave());
                confirm.setCancelButton("Закрыть", e -> dialog.close());
                confirm.setRejectButton("Отмена", e -> {});
                confirm.open();
            } else {
                dialog.close();
            }
        });
        form.withDefaultButtons();

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

        // Создание без права — вкладку не открываем вовсе (Фаза 4).
        if (id == null) {
            String reason = itemFormAccessBinder.blockReasonIfCannotCreate(entityClass);
            if (reason != null) {
                showError(reason);
                return;
            }
        }

        if (workspace == null) {
            throw new IllegalStateException(
                "Workspace not set. Call coordinator.setWorkspace(workspace) before using " +
                "WORKSPACE_TAB mode for openItemForm().");
        }

        String entryId = "item-" + entityClass.getSimpleName().toLowerCase()
            + (variant != null ? "-" + variant : "")
            + (id != null ? "-" + id.toString() : "-new");

        String variantSuffix = variant != null ? " (" + variant + ")" : "";
        String title = (id == null ? "Создание: " : "Редактирование: ")
            + meta.getItemFormTitle() + variantSuffix;

        // Генерируем callback для refresh списка
        // onSaved уже содержит логику (form.refresh()), просто пробрасываем
        Consumer<T> tabOnSaved = saved -> {
            if (onSaved != null) onSaved.accept(saved);
        };

        workspace.open(ItemFormWrapperView.class, entryId, title, view -> {
            view.init(entityClass, variant, id, tabOnSaved, () -> workspace.close(entryId), parameters);
        });
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

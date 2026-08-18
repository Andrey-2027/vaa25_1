package org.ip.form.coordinator;

import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import org.ip.form.FieldFactory;
import org.ip.application.form.FormSaveHandler;
import org.ip.application.form.FormSaveResult;
import org.ip.application.form.ItemFormSaveDispatcher;
import org.ip.form.builtin.ItemForm;
import org.ip.form.builtin.ListForm;
import org.ip.form.builtin.SelectionForm;
import org.ip.form.coordinator.FormOpenMode;
import org.ip.form.registry.FormRegistry;
import org.ip.form.registry.FormResolver;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ip.service.BaseService;
import org.ip.service.ServiceLocator;
import org.ip.views.reportstudio.ListFormReportActions;
import org.ip.views.workspace.Workspace;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.rls.RlsUiGate;
import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.core.MdcKeys;
import org.ipro.telemetry.core.TelemetryBridge;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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
    private final org.ip.service.FormSettingsService formSettingsService;
    private final org.ip.service.GridFormViewService gridFormViewService;
    private final RlsUiGate rlsUiGate;
    private final ItemFormAccessBinder itemFormAccessBinder;

    // Опциональная ссылка на Workspace для открытия форм в Tab (1С-стиль)
    private Workspace workspace;

    // Режим открытия форм элементов (по умолчанию — Dialog)
    private FormOpenMode itemFormOpenMode = FormOpenMode.DIALOG;

    public FormCoordinator(MetadataResolver metadataResolver,
                           FieldFactory fieldFactory,
                           ApplicationContext applicationContext,
                           FormResolver formResolver,
                           ServiceLocator serviceLocator,
                           org.ip.service.FormSettingsService formSettingsService,
                           org.ip.service.GridFormViewService gridFormViewService,
                           RlsUiGate rlsUiGate,
                           ItemFormAccessBinder itemFormAccessBinder) {
        this.metadataResolver = metadataResolver;
        this.fieldFactory = fieldFactory;
        this.applicationContext = applicationContext;
        this.formResolver = formResolver;
        this.serviceLocator = serviceLocator;
        this.formSettingsService = formSettingsService;
        this.gridFormViewService = gridFormViewService;
        this.rlsUiGate = rlsUiGate;
        this.itemFormAccessBinder = itemFormAccessBinder;
    }

    /**
     * Установить Workspace для открытия форм в вкладках (1С-стиль).
     * Если не установлен — формы открываются в Dialog.
     */
    public void setWorkspace(Workspace workspace) {
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
        String entryId = entityClass.getSimpleName().toLowerCase()
            + (variant != null ? "-" + variant : "");
        String title = meta.getListFormTitle()
            + (variant != null ? " (" + variant + ")" : "");

        if (workspace != null) {
            // Открываем в Workspace как вкладку
            workspace.open(ListFormWrapper.class, entryId, title, wrapper -> {
                ListForm<T, ID> listForm = createListForm(entityClass, variant, parameters, null);
                wrapper.setContent(listForm);
            });
        } else {
            throw new IllegalStateException(
                "Workspace not set. Call coordinator.setWorkspace(workspace) before using openListForm().");
        }
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
        // РџР»Р°С‚С„РѕСЂРјРµРЅРЅР°СЏ РєРѕРјР°РЅРґР° РїРµС‡Р°С‚Рё: РїРѕРґРєР»СЋС‡Р°РµС‚СЃСЏ РєРѕ РІСЃРµРј СЂРµРµСЃС‚СЂР°Рј, РµСЃР»Рё РЅРµ РІС‹РєР»СЋС‡РµРЅР° Р°РЅРЅРѕС‚Р°С†РёРµР№ СЃСѓС‰РЅРѕСЃС‚Рё.
        if (applicationContext != null) {
            var reportActionsProvider = applicationContext.getBeanProvider(ListFormReportActions.class);
            if (reportActionsProvider != null) {
                reportActionsProvider.ifAvailable(reportActions -> reportActions.addDefaultPrintAction(form, entityClass));
            }
        }

        // Включаем диалог "Настройка колонок" (нужен резолвер для полей связанных сущностей)
        form.setMetadataResolver(metadataResolver);

        // Поддержка сохранённых видов (GridFormView) + вид по умолчанию за пользователем.
        // Ключ различает варианты формы: у "archived"-варианта своя настройка/свои виды.
        form.setViewSupport(gridFormViewService, formSettingsService,
            entityClass.getSimpleName() + (variant != null ? "." + variant : ""));

        // RLS-права на кнопки (Фаза 3): «Создать»/«Изменить»/«Удалить» по RlsUiGate.
        form.setRlsUiGate(rlsUiGate);

        // Настройка callback'ов для кнопок
        form.setOnAdd(entity -> openItemForm(entityClass, null, null, saved -> form.refresh()));
        form.setOnEdit(entity -> openItemForm(entityClass, null, (ID) entity.getId(), saved -> form.refresh()));
        form.setOnDelete(entity -> form.refresh());

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
        form.setSaveHandler((FormSaveHandler) applicationContext.getBean(ItemFormSaveDispatcher.class));

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
            form.initializeNewEntity();
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
        SelectionForm<T> form = formResolver.resolveSelectionForm(entityClass, onSelected);
        form.open();
    }

    // === Поиск сервисов ===

    /**
     * Динамически находит Spring-бин Service для указанной сущности.
     *
     * Стратегия:
     *   1. Проверяет @EntityMetadata.serviceClass() — если указан, использует его
     *   2. Fallback: ищет бин по имени nomenclatureService для Nomenclature.class
     *   3. Если не найдено — бросает исключение с подсказкой
     *
     * Примеры:
     * <pre>
     * // Явное указание:
     * {@code @EntityMetadata(serviceClass = NomenclatureService.class)}
     *
     * // Автопоиск по имени:
     * {@code @EntityMetadata(...)} // ищет бин "nomenclatureService"
     * </pre>
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

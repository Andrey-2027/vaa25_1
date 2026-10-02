package org.ipro.form.coordinator;

import com.vaadin.flow.component.notification.Notification;
import org.ipro.form.FormSaveHandler;
import org.ipro.form.FormSaveResult;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.CopyLinkButton;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.link.EntityStructureNavigation;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.ReadOnlyReason;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.registry.FormResolver;
import org.ipro.crud.BaseService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.Dirtyable;
import org.ipro.form.Savable;
import org.ipro.identity.IdentifiableEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Wrapper-View для ItemForm, чтобы его можно было открыть в Workspace как вкладку.
 *
 * Аналогичен {@link ListFormWrapper}, но для форм редактирования элемента.
 * Реализует {@link Dirtyable} и {@link Savable}, чтобы Workspace мог
 * запрашивать подтверждение при закрытии несохранённой вкладки.
 *
 * Форма строится тем же {@link FormResolver}, что и Dialog-режим
 * (FormCoordinator.resolveItemForm), поэтому открытие вкладки и диалога для одного
 * (entityClass, variant) дают одинаковую форму и одинаковый набор табличных частей —
 * без ручной сборки и ручного поиска сервиса.
 *
 * Использование — через {@link FormCoordinator}:
 * <pre>
 * // FormCoordinator.openItemForm() автоматически использует этот класс
 * // когда FormOpenMode = WORKSPACE_TAB
 * </pre>
 */
/**
 * Регистрация бина — в {@code FormAutoConfiguration}, со {@code @Scope("prototype")} на фабричном
 * методе: аннотация класса при {@code @Bean}-регистрации не наследуется, а общий инстанс
 * представления означал бы, что вторая открытая форма получает состояние первой (D3.5.5).
 */
public class ItemFormWrapperView extends VerticalLayout implements Dirtyable, Savable {

    private final ApplicationContext applicationContext;
    private final FormResolver formResolver;
    private final ServiceLocator serviceLocator;
    private final ItemFormAccessBinder itemFormAccessBinder;
    private final ActionRegistry actionRegistry;
    private final ActionContextProvider actionContextProvider;
    private final ActionHandlerRegistry actionHandlerRegistry;
    private final FormLinkService formLinkService;
    private EntityStructureNavigation structureNavigation;

    @Autowired(required = false)
    public void setEntityStructureNavigation(EntityStructureNavigation structureNavigation) {
        this.structureNavigation = structureNavigation;
    }

    private ItemForm<?> itemForm;
    private Consumer<IdentifiableEntity> savedCallback;

    /** Кнопка «Скопировать ссылку» открытой карточки; перерисовывается после сохранения. */
    private CopyLinkButton copyLinkButton;

    public ItemFormWrapperView(
            @Autowired ApplicationContext applicationContext,
            @Autowired FormResolver formResolver,
            @Autowired ServiceLocator serviceLocator,
            @Autowired ItemFormAccessBinder itemFormAccessBinder,
            @Autowired ActionRegistry actionRegistry,
            @Autowired ActionContextProvider actionContextProvider,
            @Autowired ActionHandlerRegistry actionHandlerRegistry,
            @Autowired FormLinkService formLinkService) {
        this.applicationContext = applicationContext;
        this.formResolver = formResolver;
        this.serviceLocator = serviceLocator;
        this.itemFormAccessBinder = itemFormAccessBinder;
        this.actionRegistry = actionRegistry;
        this.actionContextProvider = actionContextProvider;
        this.actionHandlerRegistry = actionHandlerRegistry;
        this.formLinkService = formLinkService;
        setSizeFull();
        setPadding(false);
        setSpacing(false);
    }

    /**
     * Инициализирует форму для указанного класса сущности, варианта и ID.
     *
     * @param entityClass   класс сущности (например, Nomenclature.class)
     * @param variant       вариант формы (null = default)
     * @param id            ID записи для редактирования (null = новая запись)
     * @param onSaved       callback после успешного сохранения
     * @param closeCallback callback для закрытия вкладки
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> void init(
            Class<T> entityClass,
            String variant,
            ID id,
            Consumer<T> onSaved,
            Runnable closeCallback) {
        init(entityClass, variant, id, onSaved, closeCallback, null);
    }

    /**
     * Инициализирует форму для указанного класса сущности, варианта, ID и параметров
     * открытия (например, {@code "readOnlySections"} — см. FormCoordinator.openItemForm).
     *
     * @param entityClass   класс сущности (например, Nomenclature.class)
     * @param variant       вариант формы (null = default)
     * @param id            ID записи для редактирования (null = новая запись)
     * @param onSaved       callback после успешного сохранения
     * @param closeCallback callback для закрытия вкладки
     * @param parameters    параметры открытия формы (могут быть null)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> void init(
            Class<T> entityClass,
            String variant,
            ID id,
            Consumer<T> onSaved,
            Runnable closeCallback,
            Map<String, Object> parameters) {
        init(entityClass, variant, id, null, onSaved, closeCallback, parameters);
    }

    /**
     * То же открытие, но с уже прочитанной записью: canonical read выполняет вызывающий
     * (preflight до открытия вкладки). Это убирает второе чтение и окно гонки между проверкой
     * и сборкой формы.
     *
     * <p><b>Fail-closed.</b> Если {@code id != null}, а записи нет (строки нет либо её скрыла
     * row-level RLS), форма НЕ собирается и НЕ добавляется: выбрасывается
     * {@link RecordUnavailableException}. {@code WorkspaceManager} не кеширует компонент, а
     * {@code Workspace} не добавляет вкладку. Раньше пустая форма всё равно добавлялась, а
     * {@code ItemForm.getEntity()} лениво создавал новый объект — то есть отказ в чтении мог
     * превратиться в создание записи.</p>
     *
     * @param loadedEntity уже прочитанная запись для {@code id} либо {@code null}, если её должен
     *                     прочитать сам wrapper (или {@code id == null})
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> void init(
            Class<T> entityClass,
            String variant,
            ID id,
            T loadedEntity,
            Consumer<T> onSaved,
            Runnable closeCallback,
            Map<String, Object> parameters) {

        removeAll();

        // Fail-closed до сборки формы: недоступная или исчезнувшая запись прекращает открытие,
        // а не открывает пустую карточку, из которой можно сохранить новый объект.
        T existing = null;
        if (id != null) {
            existing = loadedEntity != null ? loadedEntity : loadExisting(entityClass, id);
            if (existing == null) {
                throw new RecordUnavailableException(entityClass, id);
            }
        }

        ItemForm<T> form = formResolver.resolveItemForm(entityClass, variant, id, parameters);
        form.setSaveHandler((FormSaveHandler) applicationContext.getBean(FormSaveHandler.class));
        this.savedCallback = (Consumer) onSaved;

        // Загружаем существующую запись или инициализируем несохранённую для новой
        if (existing != null) {
            form.setEntity(existing);
            // Запись нельзя сохранить — форма в режиме просмотра с названной причиной (E1.5).
            // Тем же путём, что и диалог: один binder, одно решение.
            itemFormAccessBinder.applyReadOnlyIfCannotSave(form,
                itemActionResolver(entityClass, variant));
        } else {
            Object preset = parameters == null ? null : parameters.get("presetEntity");
            if (preset != null) {
                T presetEntity = entityClass.cast(preset);
                form.setEntityFactory(() -> presetEntity);
            }
            form.initializeNewEntity(FormCoordinator.initialValuesOf(parameters));
            FormCoordinator.seedCopiedRows(form, parameters);
        }

        if (id != null && FormCoordinator.isReadOnlyRequested(parameters)) {
            // Явный просмотр (E1.3): решение списка не допускает изменения — карточка открывается
            // без правки. Это не отказ в правах, поэтому бейдж о правах здесь не показывается.
            form.setReadOnly(ReadOnlyReason.requested());
        }

        // Отмена → закрываем вкладку
        form.setOnCancel(() -> {
            if (closeCallback != null) closeCallback.run();
        });

        // Сохранение: исход решает host. Кнопка «Сохранить» закрывает вкладку при успехе;
        // ветка Workspace «Сохранить и закрыть» (doSave) закрытие не дублирует —
        // её закрывает сам Workspace по возвращённому результату.
        form.setOnSave(() -> {
            if (saveNow(form).success() && closeCallback != null) {
                closeCallback.run();
            }
        });

        form.withDefaultButtons();
        // Ссылка на запись (E2.1): тот же код, что у диалога, — адрес не должен зависеть от
        // того, каким путём открыли карточку. Кнопка скрыта, пока решения нет.
        this.copyLinkButton = ItemFormLinkAffordance.attach(form, formLinkService,
            itemActionResolver(entityClass, variant), entityClass, variant);
        FormStructureAffordance.attach(form.getFooter(), entityClass, structureNavigation, false);
        add(form);
        setFlexGrow(1, form);
        this.itemForm = form;
    }

    /** Решатель действий карточки: по нему решается, правится открытая запись или нет (E1.5). */
    private ActionResolver itemActionResolver(Class<?> entityClass, String variant) {
        return new ActionResolver(actionRegistry, actionContextProvider, actionHandlerRegistry,
            ActionSurface.ITEM_FOOTER, entityClass, variant);
    }

    /** Canonical чтение записи для открытия вкладки — тот же путь, что у диалога. */
    private <T extends IdentifiableEntity, ID> T loadExisting(Class<T> entityClass, ID id) {
        BaseService<T, ID> service = serviceLocator.findService(entityClass);
        return service.findById(id).orElse(null);
    }

    /**
     * Запись недоступна в момент открытия вкладки: строки нет либо её скрыла row-level RLS.
     *
     * <p>Unchecked намеренно: исключение летит через инициализатор {@code Workspace.open} в
     * {@code WorkspaceManager.getOrCreate} ({@code computeIfAbsent}) — при броске компонент не
     * кешируется, а вкладка не добавляется. Вызывающий ({@code FormCoordinator}) ловит его и
     * показывает сообщение, поэтому пользователь не видит стек.</p>
     */
    public static class RecordUnavailableException extends RuntimeException {

        private final Class<?> entityClass;
        private final Object id;

        public RecordUnavailableException(Class<?> entityClass, Object id) {
            super("Запись не найдена: " + id);
            this.entityClass = entityClass;
            this.id = id;
        }

        /** Тип, для которого не нашлась запись (для диагностики и route-маппинга). */
        public Class<?> entityClass() {
            return entityClass;
        }

        /** Идентификатор, по которому читалась запись. */
        public Object id() {
            return id;
        }
    }

    // === Dirtyable / Savable — делегируем к ItemForm ===

    @Override
    public boolean isDirty() {
        return itemForm != null && itemForm.isDirty();
    }

    /**
     * Режим просмотра карточки (E1.5): Workspace по нему решает, предлагать ли закрытие с
     * сохранением. Без открытой формы режим просмотра не заявлен — нечего сохранять.
     */
    @Override
    public boolean isReadOnly() {
        return itemForm != null && itemForm.isReadOnly();
    }

    @Override
    public String getCloseConfirmMessage() {
        return itemForm != null
            ? itemForm.getCloseConfirmMessage()
            : "Есть несохранённые изменения. Закрыть вкладку?";
    }

    /**
     * Честный исход сохранения (спецификация «Часть C.2»): возвращает
     * {@code save().success()}, а не «true всегда». Workspace закрывает вкладку
     * только при true; при failure вкладка остаётся открытой, строки не теряются.
     */
    @Override
    public boolean doSave() {
        if (itemForm == null) {
            return true;
        }
        return saveNow(itemForm).success();
    }

    /**
     * Общая ветка сохранения (кнопка и Workspace-закрытие): валидация + обработчик
     * внутри {@code ItemForm.save()}, уведомления, вызов onSaved при успехе.
     * Закрытие — только у вызывающего host-кода.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private FormSaveResult saveNow(ItemForm<?> form) {
        FormSaveResult result = form.save();
        if (result.success()) {
            // У только что сохранённой записи появился id: ссылка стала построимой, и кнопка
            // обязана это увидеть, не дожидаясь переоткрытия вкладки (E2.1).
            if (copyLinkButton != null) {
                copyLinkButton.refresh();
            }
            if (savedCallback != null) {
                savedCallback.accept(((FormSaveResult.Success) result).saved());
            }
            Notification.show("Сохранено", 2000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        } else if (result instanceof FormSaveResult.Conflict conflict) {
            // Конфликт @Version: форма остается открытой, изменения не потеряны —
            // пользователь перечитывает данные (открывает запись заново) и повторяет.
            Notification.show(String.join("\n", conflict.messages()), 5000, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_CONTRAST);
        } else if (result instanceof FormSaveResult.Failure failure) {
            Notification.show(String.join("\n", failure.messages()), 5000, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
        return result;
    }

    public ItemForm<?> getItemForm() {
        return itemForm;
    }
}

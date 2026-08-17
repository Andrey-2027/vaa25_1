package org.ip.form.coordinator;

import com.vaadin.flow.component.notification.Notification;
import org.ip.application.form.FormSaveHandler;
import org.ip.application.form.FormSaveResult;
import org.ip.application.form.ItemFormSaveDispatcher;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.springframework.context.annotation.Scope;
import org.ip.form.builtin.ItemForm;
import org.ip.form.registry.FormResolver;
import org.ip.service.BaseService;
import org.ip.service.ServiceLocator;
import org.ip.views.workspace.Dirtyable;
import org.ip.views.workspace.Savable;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.Optional;
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
@SpringComponent
@Scope("prototype")
public class ItemFormWrapperView extends VerticalLayout implements Dirtyable, Savable {

    private final ApplicationContext applicationContext;
    private final FormResolver formResolver;
    private final ServiceLocator serviceLocator;
    private final ItemFormAccessBinder itemFormAccessBinder;

    private ItemForm<?> itemForm;
    private Consumer<IdentifiableEntity> savedCallback;

    public ItemFormWrapperView(
            @Autowired ApplicationContext applicationContext,
            @Autowired FormResolver formResolver,
            @Autowired ServiceLocator serviceLocator,
            @Autowired ItemFormAccessBinder itemFormAccessBinder) {
        this.applicationContext = applicationContext;
        this.formResolver = formResolver;
        this.serviceLocator = serviceLocator;
        this.itemFormAccessBinder = itemFormAccessBinder;
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

        removeAll();

        ItemForm<T> form = formResolver.resolveItemForm(entityClass, variant, id, parameters);
        form.setSaveHandler((FormSaveHandler) applicationContext.getBean(ItemFormSaveDispatcher.class));
        this.savedCallback = (Consumer) onSaved;

        // Загружаем существующую запись или инициализируем несохранённую для новой
        if (id != null) {
            BaseService<T, ID> service = serviceLocator.findService(entityClass);
            Optional<T> existing = service.findById(id);
            if (existing.isPresent()) {
                form.setEntity(existing.get());
                // Без права на изменение — форма в режиме только просмотра (Фаза 4).
                itemFormAccessBinder.applyReadOnlyIfCannotUpdate(form);
            }
        } else {
            form.initializeNewEntity();
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
        add(form);
        setFlexGrow(1, form);
        this.itemForm = form;
    }

    // === Dirtyable / Savable — делегируем к ItemForm ===

    @Override
    public boolean isDirty() {
        return itemForm != null && itemForm.isDirty();
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
            if (savedCallback != null) {
                savedCallback.accept(((FormSaveResult.Success) result).saved());
            }
            Notification.show("Сохранено", 2000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
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

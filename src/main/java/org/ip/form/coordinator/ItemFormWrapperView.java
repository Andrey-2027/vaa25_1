package org.ip.form.coordinator;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.springframework.context.annotation.Scope;
import org.ip.form.FieldFactory;
import org.ip.form.TableSectionFactory;
import org.ip.form.builtin.ItemForm;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.service.BaseService;
import org.ip.views.workspace.Dirtyable;
import org.ip.views.workspace.Savable;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Wrapper-View для ItemForm, чтобы его можно было открыть в Workspace как вкладку.
 *
 * Аналогичен {@link ListFormWrapper}, но для форм редактирования элемента.
 * Реализует {@link Dirtyable} и {@link Savable}, чтобы Workspace мог
 * запрашивать подтверждение при закрытии несохранённой вкладки.
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

    private final MetadataResolver metadataResolver;
    private final FieldFactory fieldFactory;
    private final ApplicationContext applicationContext;
    private final TableSectionFactory tableSectionFactory;
    private final ItemFormAccessBinder itemFormAccessBinder;

    private ItemForm<?> itemForm;
    private Consumer<?> onSavedCallback;

    public ItemFormWrapperView(
            @Autowired MetadataResolver metadataResolver,
            @Autowired FieldFactory fieldFactory,
            @Autowired ApplicationContext applicationContext,
            @Autowired TableSectionFactory tableSectionFactory,
            @Autowired ItemFormAccessBinder itemFormAccessBinder) {
        this.metadataResolver = metadataResolver;
        this.fieldFactory = fieldFactory;
        this.applicationContext = applicationContext;
        this.tableSectionFactory = tableSectionFactory;
        this.itemFormAccessBinder = itemFormAccessBinder;
        setSizeFull();
        setPadding(false);
        setSpacing(false);
    }

    /**
     * Инициализирует форму для указанного класса сущности и ID.
     *
     * @param entityClass   класс сущности (например, Nomenclature.class)
     * @param id            ID записи для редактирования (null = новая запись)
     * @param onSaved       callback после успешного сохранения
     * @param closeCallback callback для закрытия вкладки
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> void init(
            Class<T> entityClass,
            ID id,
            Consumer<T> onSaved,
            Runnable closeCallback) {

        removeAll();

        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        BaseService<T, ID> service = findService(entityClass);

        ItemForm<T> form = new ItemForm<>(meta, fieldFactory);
        tableSectionFactory.attachTableSections(form, entityClass);

        // Загружаем существующую запись или оставляем пустой для новой
        if (id != null) {
            Optional<T> existing = service.findById(id);
            if (existing.isPresent()) {
                form.setEntity(existing.get());
                // Без права на изменение — форма в режиме только просмотра (Фаза 4).
                itemFormAccessBinder.applyReadOnlyIfCannotUpdate(form);
            }
        }

        // Отмена → закрываем вкладку
        form.setOnCancel(() -> {
            if (closeCallback != null) closeCallback.run();
        });

        // Сохранение
        form.setOnSave(() -> {
            if (!form.isValid()) {
                Notification.show(
                    "Заполните обязательные поля:\n" + String.join("\n", form.validate()),
                    5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            java.util.List<String> sectionErrors = form.validateTableSections();
            if (!sectionErrors.isEmpty()) {
                Notification.show(String.join("\n", sectionErrors),
                    5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }

            try {
                T entity = form.getEntity();
                T saved = service.save(entity);
                form.commitTableSections(saved);
                form.commitSnapshot();

                if (onSaved != null) {
                    onSaved.accept(saved);
                }

                Notification.show("Сохранено", 2000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);

                if (closeCallback != null) closeCallback.run();

            } catch (Exception ex) {
                Notification.show("Ошибка сохранения: " + ex.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
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

    @Override
    public boolean doSave() {
        if (itemForm != null && itemForm.getOnSave() != null) {
            itemForm.getOnSave().run();
        }
        return true; // сохранение асинхронное, показываем что команда отправлена
    }

    public ItemForm<?> getItemForm() {
        return itemForm;
    }

    @SuppressWarnings("unchecked")
    private <T extends IdentifiableEntity, ID> BaseService<T, ID> findService(Class<T> entityClass) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        Class<?> serviceClass = meta.getAnnotation().serviceClass();

        if (serviceClass != null && serviceClass != void.class) {
            return (BaseService<T, ID>) applicationContext.getBean(serviceClass);
        }

        String serviceName = uncapitalize(entityClass.getSimpleName()) + "Service";
        return (BaseService<T, ID>) applicationContext.getBean(serviceName);
    }

    private String uncapitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }
}

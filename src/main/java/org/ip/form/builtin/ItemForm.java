package org.ip.form.builtin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ip.form.FieldFactory;
import org.ip.form.FormBindingRegistry;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.FieldMetadataInfo;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Универсальная форма элемента. Генерируется из EntityMetadataInfo.
 *
 * Содержит:
 *   - FormLayout с полями, автоматически созданными FieldFactory
 *   - FormBindingRegistry с биндингами для каждого поля
 *   - Footer для кнопок "Сохранить"/"Отмена" (добавляются через withDefaultButtons или вручную)
 *
 * Использование:
 * <pre>
 * EntityMetadataInfo meta = resolver.resolve(Nomenclature.class);
 * ItemForm&lt;Nomenclature&gt; form = new ItemForm&lt;&gt;(meta, fieldFactory);
 * form.setEntity(nomenclature);  // для редактирования
 * // или
 * form.setEntityFactory(() -&gt; new Nomenclature());  // для нового
 * form.withDefaultButtons();
 * </pre>
 */
public class ItemForm<T> extends VerticalLayout
        implements org.ip.views.workspace.Dirtyable,
                   org.ip.views.workspace.Savable {

    private final EntityMetadataInfo metadata;
    private final FieldFactory fieldFactory;
    private final FormBindingRegistry registry = new FormBindingRegistry();
    private final FormLayout formLayout = new FormLayout();
    private final HorizontalLayout footer = new HorizontalLayout();

    private T entity;
    private T snapshot;
    private Supplier<T> entityFactory;
    private Runnable onSave;
    private Runnable onCancel;

    /**
     * Создать форму со всеми полями из метаданных.
     */
    public ItemForm(EntityMetadataInfo metadata, FieldFactory fieldFactory) {
        this(metadata, fieldFactory, null);
    }

    /**
     * Создать форму с фильтрацией полей.
     *
     * @param metadata метаданные сущности
     * @param fieldFactory фабрика полей
     * @param fieldNames список имён полей для отображения (null = все поля)
     *
     * Пример:
     * <pre>
     * // Только поля "code", "name", "description"
     * ItemForm&lt;Nomenclature&gt; form = new ItemForm&lt;&gt;(
     *     meta,
     *     fieldFactory,
     *     List.of("code", "name", "description")
     * );
     * </pre>
     */
    public ItemForm(EntityMetadataInfo metadata, FieldFactory fieldFactory, List<String> fieldNames) {
        this.metadata = metadata;
        this.fieldFactory = fieldFactory;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        formLayout.setWidthFull();
        formLayout.setResponsiveSteps(
            new FormLayout.ResponsiveStep("0", 1),
            new FormLayout.ResponsiveStep("500px", 2)
        );

        // Создаём компоненты для указанных полей (или всех, если fieldNames == null)
        List<FieldMetadataInfo> fieldsToShow = fieldNames == null || fieldNames.isEmpty()
            ? metadata.getFormFields()
            : metadata.getFormFields().stream()
                .filter(field -> fieldNames.contains(field.getName()))
                .toList();

        for (FieldMetadataInfo field : fieldsToShow) {
            Component component = fieldFactory.createField(field, registry);
            formLayout.add(component);
        }

        footer.setWidthFull();
        footer.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        footer.setPadding(false);
        footer.setSpacing(true);

        add(formLayout, footer);
        setFlexGrow(1, formLayout);
    }

    // === Entity lifecycle ===

    /**
     * Установить сущность для редактирования. Поля заполняются значениями.
     * Если entity == null — поля очищаются (для режима "новая запись").
     * Также сбрасывает snapshot для отслеживания изменений.
     */
    public void setEntity(T entity) {
        this.entity = entity;
        this.snapshot = deepClone(entity);
        if (entity != null) {
            registry.readAllFromEntity(entity);
        } else {
            registry.readAllFromEntity(newInstance());
        }
    }

    /**
     * Получить текущую сущность. Если entity не был установлен,
     * создаётся новый экземпляр через entityFactory или рефлексию.
     * Применяет все биндинги (значения из UI → поля сущности).
     */
    public T getEntity() {
        if (entity == null) {
            entity = newInstance();
        }
        registry.applyAllToEntity(entity);
        return entity;
    }

    /**
     * Текущая сущность без применения биндингов (peek).
     */
    public T peekEntity() {
        return entity;
    }

    /**
     * Обновить snapshot до текущего состояния. Вызывать после успешного сохранения.
     */
    public void commitSnapshot() {
        this.snapshot = deepClone(entity);
    }

    /**
     * Установить фабрику для создания новых экземпляров.
     * Если не задана — используется рефлексия (getDeclaredConstructor).
     */
    public void setEntityFactory(Supplier<T> factory) {
        this.entityFactory = factory;
    }

    @SuppressWarnings("unchecked")
    private T newInstance() {
        if (entityFactory != null) {
            return entityFactory.get();
        }
        try {
            return (T) metadata.getEntityClass().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(
                "Cannot create instance of " + metadata.getEntityClass().getName() +
                ". Provide entityFactory via setEntityFactory().", e);
        }
    }

    /**
     * Простой deep clone через сериализацию (или identity для неполных сущностей).
     * Если сущность не клонируется — сравниваем по equals().
     */
    @SuppressWarnings("unchecked")
    private T deepClone(T src) {
        if (src == null) return null;
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            new java.io.ObjectOutputStream(baos).writeObject(src);
            byte[] bytes = baos.toByteArray();
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
            return (T) new java.io.ObjectInputStream(bais).readObject();
        } catch (Exception e) {
            // Fallback: возвращаем сам объект (менее точно, но работает)
            return src;
        }
    }

    // === Dirtyable / Savable ===

    @Override
    public boolean isDirty() {
        if (snapshot == null && entity == null) return false;
        if (snapshot == null || entity == null) return true;
        // Применяем текущие значения UI к entity перед сравнением
        registry.applyAllToEntity(entity);
        return !snapshot.equals(entity);
    }

    @Override
    public String getCloseConfirmMessage() {
        return "Есть несохранённые изменения. Закрыть без сохранения?";
    }

    @Override
    public boolean doSave() {
        if (onSave != null) {
            onSave.run();
            return !isDirty(); // если after-save snapshot обновился — isDirty() вернёт false
        }
        return false;
    }

    // === Валидация ===

    public boolean isValid() {
        return registry.isValid();
    }

    public List<String> validate() {
        return registry.validate();
    }

    // === Read-only режим ===

    /**
     * Переключает форму в режим только для чтения (read-only).
     *
     * В режиме read-only:
     *   - Все поля становятся неизменяемыми
     *   - Кнопка "Сохранить" скрывается (если она была добавлена)
     *   - Кнопка "Отмена" остаётся видимой для закрытия формы
     *
     * Пример использования:
     * <pre>
     * ItemForm&lt;Nomenclature&gt; form = new ItemForm&lt;&gt;(meta, fieldFactory);
     * form.setEntity(entity);
     * form.setReadOnly(true);  // только просмотр
     * form.withDefaultButtons();
     * </pre>
     *
     * @param readOnly true = только просмотр, false = редактирование
     */
    public void setReadOnly(boolean readOnly) {
        registry.setReadOnly(readOnly);

        // Скрываем/показываем кнопку "Сохранить" в footer
        footer.getChildren()
            .filter(component -> component instanceof Button)
            .map(component -> (Button) component)
            .filter(button -> "Сохранить".equals(button.getText()))
            .forEach(button -> button.setVisible(!readOnly));
    }

    /**
     * Проверить, находится ли форма в режиме read-only.
     */
    public boolean isReadOnly() {
        return registry.isReadOnly();
    }

    // === Доступ к внутренностям ===

    public FormBindingRegistry getBindingRegistry() {
        return registry;
    }

    public EntityMetadataInfo getMetadata() {
        return metadata;
    }

    public FormLayout getFormLayout() {
        return formLayout;
    }

    public HorizontalLayout getFooter() {
        return footer;
    }

    // === Кнопки в footer ===

    public void setOnSave(Runnable onSave) {
        this.onSave = onSave;
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    public Runnable getOnSave() {
        return onSave;
    }

    public Runnable getOnCancel() {
        return onCancel;
    }

    /**
     * Добавить кнопку "Сохранить" в footer.
     */
    public Button addSaveButton() {
        Button btn = new Button("Сохранить", VaadinIcon.CHECK.create(), e -> {
            if (onSave != null) onSave.run();
        });
        btn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        footer.add(btn);
        return btn;
    }

    /**
     * Добавить кнопку "Отмена" в footer.
     */
    public Button addCancelButton() {
        Button btn = new Button("Отмена", e -> {
            if (onCancel != null) onCancel.run();
        });
        footer.add(btn);
        return btn;
    }

    /**
     * Добавить обе кнопки по умолчанию (Сохранить + Отмена).
     */
    public ItemForm<T> withDefaultButtons() {
        addCancelButton();
        addSaveButton();
        return this;
    }

    // === Notification helpers ===

    private void showError(String message) {
        Notification.show(message, 5000, Notification.Position.MIDDLE)
            .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void showSuccess(String message) {
        Notification.show(message, 2000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }
}

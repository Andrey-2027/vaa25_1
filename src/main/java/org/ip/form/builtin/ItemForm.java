package org.ip.form.builtin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H4;
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
import org.ipro.crud.IdentifiableEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Универсальная форма элемента. Генерируется из EntityMetadataInfo (или, для строк
 * табличных частей, напрямую из списка FieldMetadataInfo — см. конструктор без метаданных).
 *
 * Содержит:
 *   - FormLayout с полями, автоматически созданными FieldFactory
 *   - FormBindingRegistry с биндингами для каждого поля
 *   - 0..N табличных частей (ItemTable) — см. addTableSection(). Одна секция — без
 *     закладок; 2 и более — автоматически переключается на TabSheet
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
 *
 * Табличные части подключаются автоматически через TableSectionFactory (см.
 * FormResolver/ItemFormWrapperView) — вызывающему коду вручную создавать ItemTable не нужно.
 */
public class ItemForm<T extends IdentifiableEntity> extends VerticalLayout
        implements org.ip.views.workspace.Dirtyable,
                   org.ip.views.workspace.Savable {

    private final EntityMetadataInfo metadata; // null для форм строк табличных частей
    private final Class<T> entityClass;
    private final FieldFactory fieldFactory;
    private final FormBindingRegistry registry = new FormBindingRegistry();
    private final FormLayout formLayout = new FormLayout();
    private final HorizontalLayout footer = new HorizontalLayout();
    private final VerticalLayout sectionsContainer = new VerticalLayout();
    private final List<ItemTable<?, T>> tableSections = new ArrayList<>();
    private final List<String> tableSectionTitles = new ArrayList<>();
    private com.vaadin.flow.component.tabs.TabSheet tabSheet;

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
    @SuppressWarnings("unchecked")
    public ItemForm(EntityMetadataInfo metadata, FieldFactory fieldFactory, List<String> fieldNames) {
        this(
            (Class<T>) metadata.getEntityClass(),
            filterFields(metadata.getFormFields(), fieldNames),
            fieldFactory,
            metadata
        );
    }

    /**
     * Создать форму без EntityMetadataInfo — напрямую из класса и списка полей.
     * Используется ItemTable для диалога добавления/редактирования строки табличной части,
     * у которой нет @EntityMetadata (только @TableSectionMetadata + @FieldMetadata на полях).
     *
     * getMetadata() для формы, созданной этим конструктором, возвращает null —
     * вызывающий код не должен на него полагаться (это не generic ItemForm сущности,
     * а форма строки).
     */
    public ItemForm(Class<T> entityClass, List<FieldMetadataInfo> formFields, FieldFactory fieldFactory) {
        this(entityClass, formFields, fieldFactory, null);
    }

    private ItemForm(Class<T> entityClass,
                      List<FieldMetadataInfo> formFields,
                      FieldFactory fieldFactory,
                      EntityMetadataInfo metadata) {
        this.entityClass = entityClass;
        this.metadata = metadata;
        this.fieldFactory = fieldFactory;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        formLayout.setWidthFull();
        formLayout.setResponsiveSteps(
            new FormLayout.ResponsiveStep("0", 1, FormLayout.ResponsiveStep.LabelsPosition.ASIDE),
            new FormLayout.ResponsiveStep("600px", 2, FormLayout.ResponsiveStep.LabelsPosition.ASIDE)
        );

        for (FieldMetadataInfo field : formFields) {
            Component component = fieldFactory.createField(field, registry);
            formLayout.add(component);
        }

        footer.setWidthFull();
        footer.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        footer.setPadding(false);
        footer.setSpacing(true);

        sectionsContainer.setWidthFull();
        sectionsContainer.setPadding(false);
        sectionsContainer.setSpacing(true);

        add(formLayout, sectionsContainer, footer);
        setFlexGrow(1, formLayout);
        setFlexGrow(1, sectionsContainer);
    }

    private static List<FieldMetadataInfo> filterFields(List<FieldMetadataInfo> allFields, List<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return allFields;
        }
        return allFields.stream()
            .filter(field -> fieldNames.contains(field.getName()))
            .toList();
    }

    // === Табличные части ===

    /**
     * Подключает табличную часть к форме. Вызывается TableSectionFactory сразу после
     * конструктора, один раз на каждую секцию сущности, в порядке TableSectionMetadataInfo.getOrder() —
     * вручную вызывать не нужно.
     *
     * Режим отображения зависит от количества уже подключённых секций:
     *   - 1 секция — как раньше: заголовок (H4) + грид прямо под полями шапки, без закладок.
     *   - 2+ секции — переключение на TabSheet: при добавлении второй секции первая
     *     (уже показанная без закладок) переносится в первую вкладку, и дальше каждая
     *     новая секция — новая вкладка.
     */
    public void addTableSection(String title, ItemTable<?, T> table) {
        tableSections.add(table);
        tableSectionTitles.add(title);

        if (tableSections.size() == 1) {
            renderSingleSection(title, table);
        } else if (tableSections.size() == 2) {
            switchToTabbedSections();
        } else {
            tabSheet.add(title, table);
        }

        if (entity != null) {
            table.setParent(entity);
        }
    }

    private void renderSingleSection(String title, ItemTable<?, T> table) {
        sectionsContainer.removeAll();
        if (title != null && !title.isBlank()) {
            H4 heading = new H4(title);
            heading.getStyle().set("margin-top", "0.5em").set("margin-bottom", "0.25em");
            sectionsContainer.add(heading);
        }
        sectionsContainer.add(table);
        sectionsContainer.setFlexGrow(1, table);
    }

    private void switchToTabbedSections() {
        sectionsContainer.removeAll();
        tabSheet = new com.vaadin.flow.component.tabs.TabSheet();
        tabSheet.setSizeFull();
        for (int i = 0; i < tableSections.size(); i++) {
            tabSheet.add(tableSectionTitles.get(i), tableSections.get(i));
        }
        sectionsContainer.add(tabSheet);
        sectionsContainer.setFlexGrow(1, tabSheet);
    }

    public List<ItemTable<?, T>> getTableSections() {
        return List.copyOf(tableSections);
    }

    /**
     * Кросс-валидация всех табличных частей (см. TableSectionService.validateRows()).
     * Вызывается координатором формы ДО сохранения шапки — чтобы не оставить документ
     * в частично сохранённом состоянии при ошибке в строках.
     */
    public List<String> validateTableSections() {
        List<String> errors = new ArrayList<>();
        for (ItemTable<?, T> table : tableSections) {
            errors.addAll(table.validateRows(entity));
        }
        return errors;
    }

    /**
     * Синхронизирует строки всех табличных частей с БД для уже сохранённого родителя.
     * Вызывается координатором формы ПОСЛЕ успешного service.save(entity).
     */
    public void commitTableSections(T savedEntity) {
        for (ItemTable<?, T> table : tableSections) {
            table.commit(savedEntity);
        }
    }

    // === Entity lifecycle ===

    /**
     * Установить сущность для редактирования. Поля заполняются значениями.
     * Если entity == null — поля очищаются (для режима "новая запись").
     * Также сбрасывает snapshot для отслеживания изменений и перезагружает строки
     * табличных частей для этого родителя.
     */
    public void setEntity(T entity) {
        this.entity = entity;
        this.snapshot = deepClone(entity);
        if (entity != null) {
            registry.readAllFromEntity(entity);
        } else {
            registry.readAllFromEntity(newInstance());
        }
        for (ItemTable<?, T> table : tableSections) {
            table.setParent(entity);
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
            return (T) entityClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(
                "Cannot create instance of " + entityClass.getName() +
                ". Provide entityFactory via setEntityFactory().", e);
        }
    }

    /**
     * Простой deep clone через сериализацию (или identity для неполных сущностей).
     * Если сущность не клонируется — сравниваем по equals().
     *
     * ВНИМАНИЕ: если сущность не implements Serializable, клонирование падает и
     * возвращается тот же объект — тогда isDirty() ниже не сможет обнаружить изменения
     * этим способом. Это известное ограничение текущей реализации (не табличных частей),
     * см. обсуждение отдельно от табличных частей.
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
        boolean tableSectionsDirty = tableSections.stream().anyMatch(ItemTable::isDirty);
        if (tableSectionsDirty) return true;

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

        for (ItemTable<?, T> table : tableSections) {
            table.setReadOnly(readOnly);
        }
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

    /**
     * Метаданные сущности. Возвращает null для форм строк табличных частей
     * (созданных через конструктор ItemForm(Class, List, FieldFactory)) — такие формы
     * не привязаны к @EntityMetadata, только к @TableSectionMetadata.
     */
    public EntityMetadataInfo getMetadata() {
        return metadata;
    }

    public Class<T> getEntityClass() {
        return entityClass;
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

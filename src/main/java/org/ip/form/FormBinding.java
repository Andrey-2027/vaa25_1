package org.ip.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import org.ip.metadata.FieldMetadataInfo;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Биндинг между Vaadin-компонентом и Java-полем сущности.
 *
 * Хранит:
 *   - Ссылку на Vaadin-компонент
 *   - Метаданные поля (@FieldMetadata)
 *   - Лямбды для чтения/записи в обе стороны
 *
 * Используется ItemForm для синхронизации значений между UI и entity.
 *
 * Биндинг регистрируется в FormBindingRegistry. Регистр вызывает applyToEntity/readFromEntity
 * на всех биндингах при сохранении/загрузке.
 */
public class FormBinding {

    private final FieldMetadataInfo fieldInfo;
    private final Component component;
    private final BiConsumer<Object, Object> writeToEntity;
    private final Function<Object, Object> readFromEntity;
    private final Supplier<Object> readFromComponent;
    private final Consumer<Object> writeToComponent;
    private final Predicate<Object> isEmpty;
    private final Consumer<Boolean> setReadOnly;
    private boolean readOnly = false;
    private Object lastLoadedValue;

    public FormBinding(FieldMetadataInfo fieldInfo,
                       Component component,
                       Function<Object, Object> readFromEntity,
                       BiConsumer<Object, Object> writeToEntity,
                       Supplier<Object> readFromComponent,
                       Consumer<Object> writeToComponent,
                       Predicate<Object> isEmpty,
                       Consumer<Boolean> setReadOnly) {
        this.fieldInfo = fieldInfo;
        this.component = component;
        this.readFromEntity = readFromEntity;
        this.writeToEntity = writeToEntity;
        this.readFromComponent = readFromComponent;
        this.writeToComponent = writeToComponent;
        this.isEmpty = isEmpty;
        this.setReadOnly = setReadOnly;
    }

    /**
     * Прочитать значение из Vaadin-компонента и записать в поле сущности.
     */
    public void applyToEntity(Object entity) {
        Object value = readFromComponent.get();
        writeToEntity.accept(entity, value);
    }

    /**
     * Прочитать значение из поля сущности и записать в Vaadin-компонент.
     * Если значение null — пытаемся очистить компонент (если поддерживает).
     * Заодно фиксирует то, что реально осело в компоненте, как точку отсчёта для
     * {@link #isDirty()} — вызывается для каждого биндинга из
     * {@code FormBindingRegistry.readAllFromEntity(...)}, т.е. при каждом {@code setEntity(...)}.
     */
    public void readFromEntity(Object entity) {
        Object value = readFromEntity.apply(entity);
        if (value == null && component instanceof HasValue<?, ?> hv) {
            hv.clear();
        } else {
            writeToComponent.accept(value);
        }
        lastLoadedValue = readFromComponent.get();
    }

    /**
     * Изменилось ли значение в компоненте с момента последнего {@link #readFromEntity} (или
     * {@link #markClean()}) — сравнение по значению, не зависит от {@code equals()}/
     * сериализуемости самой сущности (в отличие от старого подхода через deep clone).
     */
    public boolean isDirty() {
        return !java.util.Objects.equals(readFromComponent.get(), lastLoadedValue);
    }

    /**
     * Считать текущее значение компонента новой "чистой" точкой отсчёта — вызывается после
     * успешного сохранения, когда форма остаётся открытой (см. {@code ItemForm.commitSnapshot()}).
     */
    public void markClean() {
        lastLoadedValue = readFromComponent.get();
    }

    /**
     * Проверить обязательность поля. Возвращает true, если:
     *   - поле не required
     *   - в компоненте есть значение
     *   - значение не пустое (isEmpty возвращает false)
     */
    public boolean isValid() {
        if (!fieldInfo.isRequired()) return true;
        Object value = readFromComponent.get();
        if (value == null) return false;
        return !isEmpty.test(value);
    }

    /**
     * Сообщение об ошибке валидации, или null если поле валидно.
     */
    public String getValidationError() {
        if (isValid()) return null;
        return fieldInfo.getLabel() + ": обязательно для заполнения";
    }

    /**
     * Установить режим "только чтение" для компонента.
     */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        if (setReadOnly != null) {
            setReadOnly.accept(readOnly);
        }
    }

    /**
     * Проверить, находится ли компонент в режиме read-only.
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    // === Геттеры ===

    public FieldMetadataInfo getFieldInfo() {
        return fieldInfo;
    }

    public String getFieldName() {
        return fieldInfo.getName();
    }

    public Component getComponent() {
        return component;
    }
}

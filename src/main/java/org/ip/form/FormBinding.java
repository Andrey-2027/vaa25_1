package org.ip.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import org.ipro.metadata.FieldMetadataInfo;

import java.util.Objects;
import java.util.Optional;
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
 *   - Описание поля ({@link BindingDescriptor}) — источник key/label/required
 *   - Метаданные поля ({@code FieldMetadataInfo}, nullable — только metadata-сценарии)
 *   - Лямбды для чтения/записи в обе стороны
 *
 * Два пути создания (спецификация «Часть D.2», PR-1.1):
 *   - {@link #forMetadata(FieldMetadataInfo, Component, ...)} — старый путь (поля из метаданных);
 *   - {@link #forExternal(BindingDescriptor, Component, ...)} — новый путь (Workshop, кастомный
 *     layout) — те же валидация/dirty/read-only, но без FieldMetadataInfo.
 *
 * Биндинг регистрируется в FormBindingRegistry. Регистр вызывает applyToEntity/readFromEntity
 * на всех биндингах при сохранении/загрузке.
 */
public class FormBinding {

    private final BindingDescriptor descriptor;
    private final FieldMetadataInfo fieldInfo; // nullable, только metadata-сценарии
    private final Component component;
    private final BiConsumer<Object, Object> writeToEntity;
    private final Function<Object, Object> readFromEntity;
    private final Supplier<Object> readFromComponent;
    private final Consumer<Object> writeToComponent;
    private final Predicate<Object> isEmpty;
    private final Consumer<Boolean> setReadOnly;
    private boolean readOnly = false;
    private Object lastLoadedValue;

    /**
     * @deprecated Переходная совместимость. Новый путь — {@link #forMetadata(FieldMetadataInfo, Component, Function, BiConsumer, Supplier, Consumer, Predicate, Consumer)}
     * и {@link #forExternal(BindingDescriptor, Component, Function, BiConsumer, Supplier, Consumer, Predicate, Consumer)}.
     */
    @Deprecated
    public FormBinding(FieldMetadataInfo fieldInfo,
                       Component component,
                       Function<Object, Object> readFromEntity,
                       BiConsumer<Object, Object> writeToEntity,
                       Supplier<Object> readFromComponent,
                       Consumer<Object> writeToComponent,
                       Predicate<Object> isEmpty,
                       Consumer<Boolean> setReadOnly) {
        this(BindingDescriptor.from(fieldInfo), fieldInfo, component,
            readFromEntity, writeToEntity, readFromComponent, writeToComponent,
            isEmpty, setReadOnly);
    }

    private FormBinding(BindingDescriptor descriptor,
                        FieldMetadataInfo fieldInfo,
                        Component component,
                        Function<Object, Object> readFromEntity,
                        BiConsumer<Object, Object> writeToEntity,
                        Supplier<Object> readFromComponent,
                        Consumer<Object> writeToComponent,
                        Predicate<Object> isEmpty,
                        Consumer<Boolean> setReadOnly) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.fieldInfo = fieldInfo;
        this.component = Objects.requireNonNull(component, "component must not be null");
        this.readFromEntity = readFromEntity;
        this.writeToEntity = writeToEntity;
        this.readFromComponent = readFromComponent;
        this.writeToComponent = writeToComponent;
        this.isEmpty = isEmpty;
        this.setReadOnly = setReadOnly;
    }

    /**
     * Старый путь: поля key/label/required берутся из {@link FieldMetadataInfo}.
     */
    public static FormBinding forMetadata(FieldMetadataInfo field,
                                          Component component,
                                          Function<Object, Object> readFromEntity,
                                          BiConsumer<Object, Object> writeToEntity,
                                          Supplier<Object> readFromComponent,
                                          Consumer<Object> writeToComponent,
                                          Predicate<Object> isEmpty,
                                          Consumer<Boolean> setReadOnly) {
        return new FormBinding(field, component, readFromEntity, writeToEntity,
            readFromComponent, writeToComponent, isEmpty, setReadOnly);
    }

    /**
     * Новый путь: явный {@link BindingDescriptor} вместо FieldMetadataInfo.
     * Используется для полей, которых нет в метаданных (Workshop, кастомный layout).
     */
    public static FormBinding forExternal(BindingDescriptor descriptor,
                                          Component component,
                                          Function<Object, Object> readFromEntity,
                                          BiConsumer<Object, Object> writeToEntity,
                                          Supplier<Object> readFromComponent,
                                          Consumer<Object> writeToComponent,
                                          Predicate<Object> isEmpty,
                                          Consumer<Boolean> setReadOnly) {
        return new FormBinding(descriptor, null, component, readFromEntity, writeToEntity,
            readFromComponent, writeToComponent, isEmpty, setReadOnly);
    }

    /**
     * Копия биндинга с переопределённым label (спецификация «Часть D.5», PR-1.2 —
     * {@code FieldNode.labelOverride}): ключ/required/lambdas сохраняются, подпись
     * (форма + required-валидация) меняется.
     */
    public FormBinding withLabel(String label) {
        return new FormBinding(
            new BindingDescriptor(descriptor.key(), label, descriptor.required()),
            fieldInfo, component, readFromEntity, writeToEntity,
            readFromComponent, writeToComponent, isEmpty, setReadOnly);
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
     *   - поле авто-нумеруемое ({@code @Numbered} — значение присвоит хук сервиса при
     *     сохранении; требовать заполнения в форме бессмысленно и мешает авто-коду,
     *     например у новых справочников Nomenclature/Oper)
     *   - в компоненте есть значение
     *   - значение не пустое (isEmpty возвращает false)
     */
    public boolean isValid() {
        if (!descriptor.required()) return true;
        if (isAutoNumbered()) return true;
        Object value = readFromComponent.get();
        if (value == null) return false;
        return !isEmpty.test(value);
    }

    /** Поле сущности помечено {@code @Numbered} (metadata-путь создания биндинга). */
    private boolean isAutoNumbered() {
        if (fieldInfo == null || fieldInfo.getField() == null) {
            return false;
        }
        return fieldInfo.getField().getAnnotation(org.ipro.numbering.annotation.Numbered.class) != null;
    }

    /**
     * Сообщение об ошибке валидации, или null если поле валидно.
     */
    public String getValidationError() {
        if (isValid()) return null;
        return descriptor.label() + ": обязательно для заполнения";
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

    /**
     * Метаданные поля, если биндинг создан через metadata-путь
     * ({@link #forMetadata(...)}); для external-биндингов — пустой Optional.
     */
    public Optional<FieldMetadataInfo> getMetadata() {
        return Optional.ofNullable(fieldInfo);
    }

    /**
     * @deprecated Используйте {@link #getMetadata()} (может быть пустым для external-биндингов).
     */
    @Deprecated
    public FieldMetadataInfo getFieldInfo() {
        return fieldInfo;
    }

    public String getFieldName() {
        return descriptor.key();
    }

    public BindingDescriptor getDescriptor() {
        return descriptor;
    }

    public Component getComponent() {
        return component;
    }
}

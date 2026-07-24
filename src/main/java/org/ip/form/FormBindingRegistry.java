package org.ip.form;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Реестр биндингов FieldFactory. Хранит все биндинги для одной формы
 * и предоставляет операции над ними как над единым целым.
 *
 * Основные операции:
 *   - add(binding)             — зарегистрировать биндинг (вызывается FieldFactory)
 *   - applyAllToEntity(entity) — прочитать все компоненты → записать в entity
 *   - readAllFromEntity(entity) — записать из entity во все компоненты
 *   - validate() / isValid()   — проверить required-поля
 *   - setReadOnly(true)        — перевести все поля в read-only
 */
public class FormBindingRegistry {

    private final List<FormBinding> bindings = new ArrayList<>();

    /**
     * Зарегистрировать биндинг.
     */
    public FormBindingRegistry add(FormBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        bindings.add(binding);
        return this;
    }

    /**
     * Прочитать значения из всех Vaadin-компонентов и записать в поля сущности.
     * Используется при сохранении формы.
     */
    public void applyAllToEntity(Object entity) {
        for (FormBinding binding : bindings) {
            binding.applyToEntity(entity);
        }
    }

    /**
     * Прочитать значения из полей сущности и записать в Vaadin-компоненты.
     * Используется при открытии формы на редактирование существующей записи
     * или при инициализации новой.
     */
    public void readAllFromEntity(Object entity) {
        for (FormBinding binding : bindings) {
            binding.readFromEntity(entity);
        }
    }

    /**
     * Список сообщений об ошибках валидации. Пустой, если всё валидно.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        for (FormBinding binding : bindings) {
            String error = binding.getValidationError();
            if (error != null) {
                errors.add(error);
            }
        }
        return errors;
    }

    /**
     * true, если все required-поля заполнены.
     */
    public boolean isValid() {
        return validate().isEmpty();
    }

    /**
     * Установить read-only для всех компонентов.
     */
    public void setReadOnly(boolean readOnly) {
        for (FormBinding binding : bindings) {
            binding.setReadOnly(readOnly);
        }
    }

    /**
     * Проверить, находятся ли все компоненты в режиме read-only.
     * Возвращает true, если хотя бы один биндинг есть и все они read-only.
     */
    public boolean isReadOnly() {
        if (bindings.isEmpty()) {
            return false;
        }
        return bindings.stream().allMatch(FormBinding::isReadOnly);
    }

    /**
     * Найти биндинг по имени поля.
     */
    public Optional<FormBinding> getBinding(String fieldName) {
        return bindings.stream()
                .filter(b -> b.getFieldName().equals(fieldName))
                .findFirst();
    }

    /**
     * Количество зарегистрированных биндингов.
     */
    public int size() {
        return bindings.size();
    }

    /**
     * Неизменяемый список всех биндингов (для отладки/итерации).
     */
    public List<FormBinding> getAll() {
        return Collections.unmodifiableList(bindings);
    }
}

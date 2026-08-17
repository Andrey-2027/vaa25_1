package org.ip.form;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Реестр биндингов FieldFactory. Хранит все биндинги для одной формы
 * и предоставляет операции над ними как над единым целым.
 *
 * Основные операции:
 *   - add(binding)             — зарегистрировать биндинг (вызывается FieldFactory);
 *                                ключ — {@code getFieldName()}, дубликат — IllegalArgumentException
 *   - applyAllToEntity(entity) — прочитать все компоненты → записать в entity
 *   - readAllFromEntity(entity) — записать из entity во все компоненты
 *   - validate() / isValid()   — проверить required-поля
 *   - setReadOnly(true)        — перевести все поля в read-only
 *
 * Порядок итерации — порядок регистрации (LinkedHashMap, спецификация «Часть D.3», PR-1.1).
 */
public class FormBindingRegistry {

    private final Map<String, FormBinding> bindings = new LinkedHashMap<>();

    /**
     * Зарегистрировать биндинг. Ключ — {@link FormBinding#getFieldName()};
     * при дубликате ключа бросает {@link IllegalArgumentException}.
     */
    public FormBindingRegistry add(FormBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        FormBinding previous = bindings.putIfAbsent(binding.getFieldName(), binding);
        if (previous != null) {
            throw new IllegalArgumentException(
                "Duplicate binding key: " + binding.getFieldName());
        }
        return this;
    }

    /**
     * Заменить биндинг по его ключу (сохраняя позицию в порядке регистрации).
     * Используется для label-оверрайдов ({@code FormBinding.withLabel(...)}, PR-1.2),
     * когда сам биндинг уже создан FieldFactory по метаданным.
     */
    public void replace(FormBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        if (!bindings.containsKey(binding.getFieldName())) {
            throw new IllegalArgumentException(
                "No binding to replace for key: " + binding.getFieldName());
        }
        bindings.put(binding.getFieldName(), binding);
    }

    /**
     * Прочитать значения из всех Vaadin-компонентов и записать в поля сущности.
     * Используется при сохранении формы.
     */
    public void applyAllToEntity(Object entity) {
        for (FormBinding binding : bindings.values()) {
            binding.applyToEntity(entity);
        }
    }

    /**
     * Прочитать значения из полей сущности и записать в Vaadin-компоненты.
     * Используется при открытии формы на редактирование существующей записи
     * или при инициализации новой.
     */
    public void readAllFromEntity(Object entity) {
        for (FormBinding binding : bindings.values()) {
            binding.readFromEntity(entity);
        }
    }

    /**
     * Список сообщений об ошибках валидации. Пустой, если всё валидно.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        for (FormBinding binding : bindings.values()) {
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
     * true, если хотя бы один биндинг изменился с момента последнего
     * {@code readAllFromEntity(...)}/{@link #markClean()} — см. {@link FormBinding#isDirty()}.
     */
    public boolean isDirty() {
        return bindings.values().stream().anyMatch(FormBinding::isDirty);
    }

    /**
     * Считать текущие значения всех компонентов новой "чистой" точкой отсчёта.
     */
    public void markClean() {
        bindings.values().forEach(FormBinding::markClean);
    }

    /**
     * Установить read-only для всех компонентов.
     */
    public void setReadOnly(boolean readOnly) {
        for (FormBinding binding : bindings.values()) {
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
        return bindings.values().stream().allMatch(FormBinding::isReadOnly);
    }

    /**
     * Найти биндинг по ключу (имени поля) — O(1) по карте.
     */
    public Optional<FormBinding> getBinding(String fieldName) {
        return Optional.ofNullable(bindings.get(fieldName));
    }

    /**
     * Количество зарегистрированных биндингов.
     */
    public int size() {
        return bindings.size();
    }

    /**
     * Неизменяемый список всех биндингов в порядке регистрации (для отладки/итерации).
     */
    public List<FormBinding> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(bindings.values()));
    }
}

package org.ipro.settings;

import org.ipro.metadata.annotation.FieldType;
import org.ipro.settings.setting.Setting;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * API чтения/записи констант (констант приложения, "Constants" в 1С-терминах). Значение
 * настройки: сначала admin-перекрытие из {@code setting_value} (сфера GLOBAL/0 в v1), при
 * отсутствии — дефолт разработчика из каталога {@link SettingsRegistry} (значение поля POJO).
 *
 * <p>Тип значения задаёт {@code FieldType} из {@code @Setting} — единственный источник правды
 * (в БД тип не дублируется): для {@code get()} этот же тип определяет, какую типизированную
 * колонку {@code SettingValue} читать, для {@code set()} — какую писать.</p>
 *
 * <p>Секретные настройки ({@code @Setting(secret=true)}): в БД НЕ хранятся — {@code set()}
 * запрещён, {@code get()} всегда возвращает дефолт из кода. Хранение секретов (env/system
 * properties) — отдельная задача, вне констант.</p>
 */
public class SettingsService {

    public static final String GLOBAL_SCOPE = "GLOBAL";
    public static final long GLOBAL_SCOPE_ID = 0L;

    private final SettingsRegistry registry;
    private final SettingValueRepository repository;

    public SettingsService(SettingsRegistry registry, SettingValueRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    public Object get(Class<?> groupClass, String fieldName) {
        SettingsRegistry.FieldDescriptor descriptor = requireDescriptor(groupClass, fieldName);
        if (descriptor.secret()) {
            return descriptor.defaultValue();
        }
        SettingValue row = repository.findBySettingKeyAndScopeTypeAndScopeId(
            key(groupClass, fieldName), GLOBAL_SCOPE, GLOBAL_SCOPE_ID).orElse(null);
        if (row == null) {
            return descriptor.defaultValue();
        }
        return readTyped(row, descriptor.type());
    }

    public String getString(Class<?> groupClass, String fieldName) {
        return (String) get(groupClass, fieldName);
    }

    public boolean getBoolean(Class<?> groupClass, String fieldName) {
        return Boolean.TRUE.equals(get(groupClass, fieldName));
    }

    public long getLong(Class<?> groupClass, String fieldName) {
        return get(groupClass, fieldName) instanceof Number n ? n.longValue() : 0L;
    }

    public BigDecimal getBigDecimal(Class<?> groupClass, String fieldName) {
        return (BigDecimal) get(groupClass, fieldName);
    }

    public LocalDate getLocalDate(Class<?> groupClass, String fieldName) {
        return (LocalDate) get(groupClass, fieldName);
    }

    public LocalDateTime getLocalDateTime(Class<?> groupClass, String fieldName) {
        return (LocalDateTime) get(groupClass, fieldName);
    }

    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> E getEnum(Class<?> groupClass, String fieldName, Class<E> enumClass) {
        Object value = get(groupClass, fieldName);
        return value instanceof String name ? Enum.valueOf(enumClass, name) : (E) value;
    }

    public Long getEntityRefId(Class<?> groupClass, String fieldName) {
        return (Long) get(groupClass, fieldName);
    }

    @Transactional
    public void set(Class<?> groupClass, String fieldName, Object value) {
        SettingsRegistry.FieldDescriptor descriptor = requireDescriptor(groupClass, fieldName);
        if (descriptor.secret()) {
            throw new IllegalStateException("Секретная настройка \"" + key(groupClass, fieldName) +
                "\" не хранится в БД: значение задаётся только в коде (@Setting(secret=true)).");
        }
        SettingValue row = repository.findBySettingKeyAndScopeTypeAndScopeId(
            key(groupClass, fieldName), GLOBAL_SCOPE, GLOBAL_SCOPE_ID).orElseGet(
                () -> new SettingValue(key(groupClass, fieldName), GLOBAL_SCOPE, GLOBAL_SCOPE_ID));
        writeTyped(row, descriptor.type(), value);
        repository.save(row);
    }

    /**
     * Сброс к дефолту разработчика: перекрытие администратора удаляется, последующие чтения
     * снова возвращают значение из кода. Настройку не «удаляют» — возвращают к каталогу.
     */
    @Transactional
    public void resetToDefault(Class<?> groupClass, String fieldName) {
        requireDescriptor(groupClass, fieldName);
        repository.deleteBySettingKeyAndScopeTypeAndScopeId(
            key(groupClass, fieldName), GLOBAL_SCOPE, GLOBAL_SCOPE_ID);
    }

    /** Имя RLS-измерения раздела ("SETTINGS:Directories") — для право-гейта в UI. */
    public String rlsDimensionOf(Class<?> groupClass) {
        return registry.rlsDimensionOf(groupClass);
    }

    private String key(Class<?> groupClass, String fieldName) {
        return groupClass.getSimpleName() + "." + fieldName;
    }

    private SettingsRegistry.FieldDescriptor requireDescriptor(Class<?> groupClass, String fieldName) {
        return registry.fieldOf(groupClass, fieldName).orElseThrow(() ->
            new IllegalArgumentException("Неизвестная настройка " + groupClass.getName() + "." + fieldName
                + " — нет @Setting на поле или группа вне скана " + registry.getClass().getName()));
    }

    private static Object readTyped(SettingValue row, FieldType type) {
        return switch (type) {
            case TEXT, TEXT_AREA, EMAIL, PASSWORD -> row.getStringValue();
            case INTEGER -> row.getIntegerValue();
            case DECIMAL -> row.getDecimalValue();
            case BOOLEAN -> row.getBoolValue();
            case DATE -> row.getDateValue();
            case DATETIME -> row.getDateTimeValue();
            case ENUM -> row.getEnumValue();
            case ENTITY_REFERENCE -> row.getEntityRefId();
            case AUTO -> throw new IllegalStateException("FieldType.AUTO не разрешён в SettingsRegistry");
        };
    }

    private static void writeTyped(SettingValue row, FieldType type, Object value) {
        row.setStringValue(null);
        row.setIntegerValue(null);
        row.setDecimalValue(null);
        row.setBoolValue(null);
        row.setDateValue(null);
        row.setDateTimeValue(null);
        row.setEnumValue(null);
        row.setEntityRefId(null);
        switch (type) {
            case TEXT, TEXT_AREA, EMAIL, PASSWORD -> row.setStringValue((String) value);
            case INTEGER -> row.setIntegerValue(((Number) value).longValue());
            case DECIMAL -> row.setDecimalValue((BigDecimal) value);
            case BOOLEAN -> row.setBoolValue((Boolean) value);
            case DATE -> row.setDateValue((LocalDate) value);
            case DATETIME -> row.setDateTimeValue((LocalDateTime) value);
            case ENUM -> row.setEnumValue(value instanceof Enum<?> e ? e.name() : String.valueOf(value));
            case ENTITY_REFERENCE -> row.setEntityRefId((Long) value);
            case AUTO -> throw new IllegalStateException("FieldType.AUTO не разрешён в SettingsRegistry");
        }
    }
}
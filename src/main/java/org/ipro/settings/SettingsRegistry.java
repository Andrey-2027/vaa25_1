package org.ipro.settings;

import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.Subsystem;
import org.ipro.settings.setting.Setting;
import org.ipro.settings.setting.SettingsGroup;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Каталог настроек приложения — строит метаданные из POJO-групп {@code org.ip.settings},
 * аннотированных {@code @SettingsGroup}, при старте (fail-fast). Каждая группа — раздел задач
 * "константы" приложения: поле {@code @Setting} интерпретируется по резолвнутому
 * {@link FieldType}, значение по умолчанию — как ЖИВОЙ инстанс группы (экземпляр создаётся
 * один раз здесь, конфигурация из кода), admin-перекрытие — из {@code setting_value}.
 *
 * <p>Обязательные контракты (fail-fast, не runtime):
 * <ul>
 * <li>{@code subsystem()} группы обязан быть классом, аннотированным {@code @Subsystem} —
 *     иначе нет ни сферы значений, ни измерения доступа {@code SETTINGS:*};</li>
 * <li>ключи "{GroupSimpleName}.{fieldName}" уникальны во всём приложении — два класса с
 *     одинаковым SimpleName столкнулись бы в одной таблице.</li>
 * </ul></p>
 */
public class SettingsRegistry implements InitializingBean {

    public record FieldDescriptor(String name, String label, FieldType type, boolean secret,
                                  Field field, Object defaultValue) {
    }

    public record GroupInfo(Class<?> groupClass, String title, Class<?> subsystemMarker,
                            String rlsDimension, Object defaultsInstance,
                            List<FieldDescriptor> fields) {
    }

    private final String basePackage;
    private Map<Class<?>, GroupInfo> groups = Map.of();

    public SettingsRegistry(@Value("${settings.scan-package:org.ip.settings}") String basePackage) {
        this.basePackage = basePackage;
    }

    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    public void rebuild() {
        List<Class<?>> groupClasses = scan();
        Map<Class<?>, GroupInfo> result = new LinkedHashMap<>();
        Map<String, String> keyOwners = new LinkedHashMap<>();

        for (Class<?> groupClass : groupClasses) {
            SettingsGroup ann = groupClass.getAnnotation(SettingsGroup.class);
            String groupSimple = groupClass.getSimpleName();

            Class<?> subsystem = ann.subsystem();
            if (subsystem.getAnnotation(Subsystem.class) == null) {
                throw new IllegalStateException("SettingsGroup " + groupClass.getName() +
                    ": subsystem() обязан быть маркером, аннотированным @Subsystem — расширяет ли " +
                    "он Subsystems?" + " (" + subsystem.getName() + " без @Subsystem).");
            }

            Object instance = instantiate(groupClass);
            List<FieldDescriptor> fields = new ArrayList<>();
            for (Field field : groupClass.getDeclaredFields()) {
                Setting setting = field.getAnnotation(Setting.class);
                if (setting == null) {
                    continue;
                }
                String key = groupSimple + "." + field.getName();
                String previousOwner = keyOwners.putIfAbsent(key, groupClass.getName());
                if (previousOwner != null) {
                    throw new IllegalStateException("Дубликат ключа настройки \"" + key +
                        "\": классы " + previousOwner + " и " + groupClass.getName() +
                        " имеют одинаковый SimpleName — переименуйте один из них.");
                }
                FieldType type = setting.type() == FieldType.AUTO ? resolveType(field) : setting.type();
                Object defaultValue = readField(instance, field);
                fields.add(new FieldDescriptor(field.getName(),
                    setting.label().isEmpty() ? field.getName() : setting.label(),
                    type, setting.secret(), field, defaultValue));
            }

            result.put(groupClass, new GroupInfo(groupClass,
                ann.title().isEmpty() ? groupSimple : ann.title(),
                subsystem,
                "SETTINGS:" + subsystem.getSimpleName(),
                instance, List.copyOf(fields)));
        }
        this.groups = Map.copyOf(result);
    }

    public List<GroupInfo> groups() {
        return List.copyOf(groups.values());
    }

    public Optional<GroupInfo> groupOf(Class<?> groupClass) {
        return Optional.ofNullable(groups.get(groupClass));
    }

    public Optional<FieldDescriptor> fieldOf(Class<?> groupClass, String fieldName) {
        return groupOf(groupClass).flatMap(g -> g.fields().stream()
            .filter(f -> f.name().equals(fieldName)).findFirst());
    }

    /** Имя измерения RLS "SETTINGS:*" для проверки доступа ({@code "SETTINGS:" + SimpleName подсистемы}). */
    public String rlsDimensionOf(Class<?> groupClass) {
        return groupOf(groupClass).map(GroupInfo::rlsDimension).orElse(null);
    }

    private List<Class<?>> scan() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false) {
                @Override
                protected boolean isCandidateComponent(
                        org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                    return true;
                }
            };
        scanner.addIncludeFilter(new AnnotationTypeFilter(SettingsGroup.class));
        List<Class<?>> result = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(basePackage)) {
            try {
                result.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Не удалось загрузить класс группы настроек "
                    + candidate.getBeanClassName(), e);
            }
        }
        return result;
    }

    private static Object instantiate(Class<?> groupClass) {
        try {
            return groupClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Группа настроек " + groupClass.getName() +
                " обязана иметь публичный конструктор без аргументов (каталог дефолтов строится " +
                "из инстанса)", e);
        }
    }

    private static Object readField(Object instance, Field field) {
        try {
            field.setAccessible(true);
            return field.get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Не удалось прочитать значение по умолчанию поля "
                + field.getName(), e);
        }
    }

    /** Резолв FieldType.AUTO по Java-типу поля — упрощённый аналог FieldMetadataInfo.resolveType. */
    static FieldType resolveType(Field field) {
        Class<?> t = field.getType();
        if (t == String.class) return FieldType.TEXT;
        if (t == Integer.class || t == int.class || t == Long.class || t == long.class) return FieldType.INTEGER;
        if (t == BigDecimal.class || t == Double.class || t == double.class) return FieldType.DECIMAL;
        if (t == Boolean.class || t == boolean.class) return FieldType.BOOLEAN;
        if (t == LocalDate.class) return FieldType.DATE;
        if (t == LocalDateTime.class) return FieldType.DATETIME;
        if (t.isEnum()) return FieldType.ENUM;
        return FieldType.TEXT;
    }
}
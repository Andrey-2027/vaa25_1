package org.ipro.telemetry.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Снимок «значения» сущности для записи журнала (например, при save):
 * JSON-объект со всеми скалярными полями, ссылками (id+class) и табличными
 * частями — списками, объявленными в {@code org.ip.metadata.annotation.TableSections}
 * (определяется рефлексией по имени, без зависимости от домена).
 * <p>
 * Ограничения: глубина вложенности табличных частей (MAX_DEPTH), размер
 * коллекции (MAX_ITEMS), длина строк (MAX_STRING), общий лимит символов
 * и узлов — снимок никогда не роняет и не «вешает» приложение.
 */
public final class EntitySnapshot {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TABLE_SECTIONS_ANNOTATION =
            "org.ip.metadata.annotation.TableSections";
    private static final int MAX_DEPTH = 3;
    private static final int MAX_ITEMS = 50;
    private static final int MAX_STRING = 200;
    private static final int MAX_NODES = 400;

    private EntitySnapshot() {
    }

    /** JSON-снимок сущности (null — если снять не удалось). */
    public static String render(Object entity, int maxChars) {
        if (entity == null || maxChars <= 0) {
            return null;
        }
        try {
            Budget budget = new Budget(maxChars);
            Map<String, Object> map = snapshot(entity, 1, tableSections(entity.getClass()), budget);
            if (map == null) {
                return null;
            }
            String json = MAPPER.writeValueAsString(map);
            return json.length() <= maxChars ? json : json.substring(0, maxChars) + "\"...\"";
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> snapshot(Object entity, int depth,
                                                Set<String> sections, Budget budget) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", entity.getClass().getSimpleName());
        for (Field field : fieldsOf(entity.getClass())) {
            if (!budget.spend()) {
                result.put("...truncated", true);
                break;
            }
            Object value = readField(field, entity);
            if (value == null) {
                result.put(field.getName(), null);
                continue;
            }
            if (value instanceof Collection<?> collection) {
                List<Object> items = new ArrayList<>();
                int shown = 0;
                for (Object element : collection) {
                    if (shown >= MAX_ITEMS) {
                        items.add("...more: " + (collection.size() - shown));
                        break;
                    }
                    items.add(itemValue(element, depth, sections, budget));
                    shown++;
                }
                result.put(field.getName(), items);
            } else if (sections.contains(value.getClass().getName())) {
                result.put(field.getName(),
                        snapshot(value, depth + 1, tableSections(value.getClass()), budget));
            } else if (isEntityReference(value.getClass())) {
                result.put(field.getName(), reference(value));
            } else {
                result.put(field.getName(), scalar(value));
            }
        }
        return result;
    }

    private static Object itemValue(Object element, int depth, Set<String> sections, Budget budget) {
        if (element == null) {
            return null;
        }
        if (sections.contains(element.getClass().getName())
                || (isEntityReference(element.getClass()) && depth < MAX_DEPTH)) {
            Map<String, Object> child = snapshot(element, depth + 1,
                    tableSections(element.getClass()), budget);
            if (child != null) {
                return child;
            }
            return reference(element);
        }
        if (isEntityReference(element.getClass())) {
            return reference(element);
        }
        return scalar(element);
    }

    private static Object scalar(Object value) {
        String text;
        if (value instanceof Enum<?> enumValue) {
            text = enumValue.name();
        } else {
            text = String.valueOf(value);
        }
        return text.length() <= MAX_STRING ? text : text.substring(0, MAX_STRING) + "...";
    }

    /** Ссылка на другую сущность: id + класс (без разворота содержимого). */
    private static Map<String, Object> reference(Object value) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("class", value.getClass().getSimpleName());
        Object id = findId(value);
        if (id != null) {
            ref.put("id", scalar(id));
        }
        return ref;
    }

    /** Чтение поля: сначала геттер (для Hibernate-прокси), затем напрямую. */
    private static Object readField(Field field, Object target) {
        try {
            Method getter = getterFor(target.getClass(), field.getName());
            if (getter != null) {
                return getter.invoke(target);
            }
            field.setAccessible(true);
            return field.get(target);
        } catch (RuntimeException | ReflectiveOperationException e) {
            return null;
        }
    }

    private static Method getterFor(Class<?> type, String fieldName) {
        String prefix = fieldName.length() > 1
                ? Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1)
                : fieldName.toUpperCase();
        String getterName = "get" + prefix;
        for (String candidate : new String[]{getterName, "is" + prefix, "get" + fieldName}) {
            try {
                Method method = type.getMethod(candidate);
                if (method.getParameterCount() == 0) {
                    return method;
                }
            } catch (NoSuchMethodException e) {
                // пробуем следующий вариант
            }
        }
        return null;
    }

    private static List<Field> fieldsOf(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isTransient(mods)
                        || field.isSynthetic()) {
                    continue;
                }
                fields.add(field);
            }
        }
        return fields;
    }

    private static Object findId(Object value) {
        try {
            Method getId = value.getClass().getMethod("getId");
            return getId.invoke(value);
        } catch (NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException e) {
            return null;
        }
    }

    private static boolean isEntityReference(Class<?> type) {
        if (CharSequence.class.isAssignableFrom(type) || Number.class.isAssignableFrom(type)
                || Boolean.class.isAssignableFrom(type) || Character.class.isAssignableFrom(type)
                || Enum.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)
                || TemporalAccessor.class.isAssignableFrom(type)
                || Optional.class.isAssignableFrom(type) || type.isArray()) {
            return false;
        }
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("jakarta.")
                || name.startsWith("org.springframework.") || name.startsWith("org.hibernate.")
                || name.startsWith("org.slf4j.") || name.startsWith("com.vaadin.")
                || name.startsWith("org.vaadin.")) {
            return false;
        }
        try {
            type.getMethod("getId");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Имена классов табличных частей сущности (аннотация TableSections). */
    private static Set<String> tableSections(Class<?> entityClass) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> annotationClass = (Class<? extends Annotation>)
                    Class.forName(TABLE_SECTIONS_ANNOTATION);
            Annotation annotation = entityClass.getAnnotation(annotationClass);
            if (annotation == null) {
                return Set.of();
            }
            Method value = annotationClass.getMethod("value");
            Class<?>[] sections = (Class<?>[]) value.invoke(annotation);
            Set<String> names = new java.util.HashSet<>();
            for (Class<?> section : sections) {
                names.add(section.getName());
            }
            return names;
        } catch (ReflectiveOperationException e) {
            return Set.of();
        }
    }

    private static final class Budget {
        private final int maxChars;
        private int nodes;

        private Budget(int maxChars) {
            this.maxChars = maxChars;
        }

        private boolean spend() {
            return ++nodes <= MAX_NODES;
        }
    }
}

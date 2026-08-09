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
import org.hibernate.Hibernate;

/**
 * Снимок «значения» сущности для записи журнала (например, при save):
 * JSON-объект со всеми скалярными полями, ссылками (id+class) и табличными
 * частями — списками, объявленными в {@code org.ip.metadata.annotation.TableSections}
 * (определяется рефлексией по имени, без зависимости от домена).
 * <p>
 * Ленивые состояния не инициализируются: неинициализированная коллекция
 * (Hibernate-прокси) записывается маркером {@code <lazy: not loaded>} —
 * снимок никогда не порождает собственных SQL-запросов.
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
            Map<String, Object> map = headerSnapshot(entity, budget);
            if (map == null) {
                return null;
            }
            String json = MAPPER.writeValueAsString(map);
            return json.length() <= maxChars ? json : json.substring(0, maxChars) + "\"...\"";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Снимок сущности + строки табличной части: шапка разворачивается как в
     * {@link #render}, строки (in-memory список из аргумента метода, например
     * replaceAll(parent, rows)) кладутся под ключ {@code rows}. Лимиты те же:
     * MAX_ITEMS строк, общий бюджет узлов/символов.
     */
    public static String renderWithRows(Object entity, Collection<?> rows, int maxChars) {
        if (entity == null || rows == null || maxChars <= 0) {
            return null;
        }
        try {
            Budget budget = new Budget(maxChars);
            Map<String, Object> map = headerSnapshot(entity, budget);
            if (map == null) {
                return null;
            }
            List<Object> rendered = new ArrayList<>();
            int total = Hibernate.isInitialized(rows) ? rows.size() : -1;
            int shown = 0;
            for (Object row : rows) {
                if (row == null) {
                    continue;
                }
                if (shown >= MAX_ITEMS) {
                    if (total >= 0) {
                        rendered.add("...more: " + (total - shown));
                    }
                    break;
                }
                Map<String, Object> rowMap = Hibernate.isInitialized(row)
                        ? snapshot(row, 2, tableSections(Hibernate.getClass(row)), budget)
                        : reference(row);
                rendered.add(rowMap != null ? rowMap : reference(row));
                shown++;
            }
            if (!rendered.isEmpty()) {
                map.put("rows", rendered);
            }
            String json = MAPPER.writeValueAsString(map);
            return json.length() <= maxChars ? json : json.substring(0, maxChars) + "\"...\"";
        } catch (Exception e) {
            return null;
        }
    }

    /** Снимок шапки: минимальный {class,id} для неинициализированного прокси. */
    private static Map<String, Object> headerSnapshot(Object entity, Budget budget) {
        if (!Hibernate.isInitialized(entity)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("class", Hibernate.getClass(entity).getSimpleName());
            Object id = findId(entity);
            if (id != null) {
                map.put("id", scalar(id));
            }
            map.put("_lazy", "proxy not loaded");
            return map;
        }
        return snapshot(entity, 1, tableSections(Hibernate.getClass(entity)), budget);
    }

    private static Map<String, Object> snapshot(Object entity, int depth,
                                                Set<String> sections, Budget budget) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", Hibernate.getClass(entity).getSimpleName());
        for (Field field : fieldsOf(Hibernate.getClass(entity))) {
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
                if (!Hibernate.isInitialized(collection)) {
                    result.put(field.getName(), "<lazy: not loaded>");
                    continue;
                }
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
            } else if (sections.contains(Hibernate.getClass(value).getName())) {
                result.put(field.getName(),
                        snapshot(value, depth + 1,
                                tableSections(Hibernate.getClass(value)), budget));
            } else if (isEntityReference(Hibernate.getClass(value))) {
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
        Class<?> elementClass = Hibernate.getClass(element);
        if (sections.contains(elementClass.getName())
                || (isEntityReference(elementClass) && depth < MAX_DEPTH)) {
            Map<String, Object> child = snapshot(element, depth + 1,
                    tableSections(elementClass), budget);
            if (child != null) {
                return child;
            }
            return reference(element);
        }
        if (isEntityReference(elementClass)) {
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

    /** Ссылка на другую сущность: id + класс (без разворота содержимого).
     * getId() на Hibernate-прокси не инициирует загрузку (id хранится
     * в прокси), поэтому ссылка безопасна даже для lazy-связи.
     * name — читабельное имя ({@link #displayNameOf}) ТОЛЬКО для уже
     * инициализированных ссылок: вызов не должен порождать SQL. */
    private static Map<String, Object> reference(Object value) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("class", Hibernate.getClass(value).getSimpleName());
        Object id = findId(value);
        if (id != null) {
            ref.put("id", scalar(id));
        }
        String name = displayNameOf(value);
        if (name != null) {
            ref.put("name", name);
        }
        return ref;
    }

    /**
     * Читабельное имя ссылки ({@code getDisplayName()}) — рефлексией по имени
     * метода, без зависимости от домена (паттерн TableSections). Единая точка
     * для снимков и field-аудита (FieldAuditListener).
     * <p>
     * Возвращает null, если: значение null; неинициализированный Hibernate-прокси
     * (вызов инициировал бы загрузку = нарушение контракта «снимок без SQL»);
     * у сущности нет getDisplayName(); имя не извлеклось (например, метод сам
     * обращается к ленивым полям — RuntimeException).
     */
    public static String displayNameOf(Object value) {
        if (value == null || !Hibernate.isInitialized(value)) {
            return null;
        }
        try {
            Method method = Hibernate.getClass(value).getMethod("getDisplayName");
            Object name = method.invoke(value);
            if (name == null) {
                return null;
            }
            String text = String.valueOf(name);
            return text.length() <= MAX_STRING ? text : text.substring(0, MAX_STRING) + "...";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
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

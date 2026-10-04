package org.ip.register;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Проверка декларации {@link RegisterMetadata} без Spring и без JPA-контекста (ADR-0013 §3).
 *
 * <p>Читает только аннотации классов и полей, поэтому проверяется обычным unit-тестом.
 * {@link RegisterStartupValidator} подключает проверку всех managed-регистров к старту приложения.</p>
 *
 * <p>Колонки ключа обязаны называться явно: валидатор не знает naming strategy проекта и не
 * угадывает имя колонки по имени поля. v1 поддерживает доступ JPA через поля: property/mixed
 * access отклоняется, а поля обычных суперклассов, static/transient и {@code @Transient}
 * не считаются сохраняемыми измерениями или ресурсами. Это проверка деклараций аннотаций,
 * а не проверка физической схемы БД.</p>
 */
public final class RegisterMetadataValidator {

    /** Закрытый словарь причин отказа: тесты и будущая диагностика ссылаются на код, а не на текст. */
    public enum Code {
        NOT_ENTITY,
        UNSUPPORTED_ACCESS,
        NO_DIMENSIONS,
        NO_RESOURCES,
        FIELD_MISSING,
        FIELD_NOT_PERSISTENT,
        DUPLICATE_FIELD,
        PERIOD_TYPE,
        KEY_COLUMN_NAME,
        KEY_NULLABLE,
        KEY_UPDATABLE,
        UNIQUE_MISSING
    }

    /** @param field имя поля, к которому относится отказ, или {@code null} для отказа типа */
    public record Violation(Code code, String field, String message) {
    }

    private RegisterMetadataValidator() {
    }

    /**
     * @throws IllegalArgumentException если тип вообще не объявляет {@link RegisterMetadata}
     */
    public static List<Violation> validate(Class<?> type) {
        RegisterMetadata meta = type.getAnnotation(RegisterMetadata.class);
        if (meta == null) {
            throw new IllegalArgumentException(type.getName() + " не объявляет @RegisterMetadata");
        }
        List<Violation> out = new ArrayList<>();
        if (!type.isAnnotationPresent(Entity.class)) {
            out.add(new Violation(Code.NOT_ENTITY, null,
                "Регистр обязан быть JPA-сущностью (@Entity)."));
            return out;
        }
        if (hasPropertyAccess(type)) {
            out.add(new Violation(Code.UNSUPPORTED_ACCESS, null,
                "Регистр v1 поддерживает только JPA field access; property/mixed access не поддерживается."));
            return out;
        }
        if (meta.dimensions().length == 0) {
            out.add(new Violation(Code.NO_DIMENSIONS, null, "Регистр без измерений не имеет ключа."));
        }
        if (meta.resources().length == 0) {
            out.add(new Violation(Code.NO_RESOURCES, null, "Регистр без ресурсов не хранит значений."));
        }

        boolean periodic = !meta.period().isBlank();
        List<String> keyNames = new ArrayList<>(List.of(meta.dimensions()));
        if (periodic) {
            keyNames.add(meta.period());
        }

        Set<String> seen = new HashSet<>();
        List<String> allNames = new ArrayList<>(keyNames);
        allNames.addAll(List.of(meta.resources()));
        for (String name : allNames) {
            if (!seen.add(name)) {
                out.add(new Violation(Code.DUPLICATE_FIELD, name,
                    "Поле '" + name + "' названо в декларации больше одного раза."));
            }
        }

        List<String> keyColumns = new ArrayList<>();
        boolean keyColumnsKnown = true;
        for (String name : keyNames) {
            Field field = findField(type, name);
            if (field == null) {
                out.add(new Violation(Code.FIELD_MISSING, name,
                    "Поле ключа '" + name + "' не найдено в " + type.getSimpleName() + "."));
                keyColumnsKnown = false;
                continue;
            }
            if (!isPersistentField(field)) {
                out.add(notPersistent(field));
                keyColumnsKnown = false;
                continue;
            }
            if (periodic && name.equals(meta.period()) && field.getType() != LocalDate.class) {
                out.add(new Violation(Code.PERIOD_TYPE, name,
                    "Период '" + name + "' должен иметь тип LocalDate, найден "
                        + field.getType().getSimpleName() + "."));
            }
            String column = checkKeyField(field, out);
            if (column == null) {
                keyColumnsKnown = false;
            } else {
                keyColumns.add(column);
            }
        }
        for (String name : meta.resources()) {
            Field field = findField(type, name);
            if (field == null) {
                out.add(new Violation(Code.FIELD_MISSING, name,
                    "Поле ресурса '" + name + "' не найдено в " + type.getSimpleName() + "."));
            } else if (!isPersistentField(field)) {
                out.add(notPersistent(field));
            }
        }

        if (keyColumnsKnown && !keyColumns.isEmpty() && !hasUniqueConstraint(type, keyColumns)) {
            out.add(new Violation(Code.UNIQUE_MISSING, null,
                "Нужен @Table(uniqueConstraints) ровно по колонкам ключа в порядке " + keyColumns
                    + " (измерения, затем период)."));
        }
        return out;
    }

    /** @return имя колонки или {@code null}, если оно не задано явно */
    private static String checkKeyField(Field field, List<Violation> out) {
        String name = field.getName();
        JoinColumn join = field.getAnnotation(JoinColumn.class);
        Column column = field.getAnnotation(Column.class);
        String columnName;
        boolean nullable;
        boolean updatable;
        if (join != null) {
            columnName = join.name();
            nullable = join.nullable();
            updatable = join.updatable();
        } else if (column != null) {
            columnName = column.name();
            nullable = column.nullable();
            updatable = column.updatable();
        } else {
            out.add(new Violation(Code.KEY_COLUMN_NAME, name,
                "Поле ключа '" + name + "' без @Column/@JoinColumn: задайте name, nullable = false,"
                    + " updatable = false."));
            return null;
        }
        if (columnName == null || columnName.isBlank()) {
            out.add(new Violation(Code.KEY_COLUMN_NAME, name,
                "Колонка поля ключа '" + name + "' должна называться явно."));
            columnName = null;
        }
        if (nullable) {
            out.add(new Violation(Code.KEY_NULLABLE, name,
                "Поле ключа '" + name + "' допускает NULL: в unique-индексе NULL ведёт себя по-разному"
                    + " на PostgreSQL, MSSQL и Oracle."));
        }
        if (updatable) {
            out.add(new Violation(Code.KEY_UPDATABLE, name,
                "Поле ключа '" + name + "' изменяемо: ключ записи регистра неизменяем после создания."));
        }
        return columnName;
    }

    private static boolean isPersistentField(Field field) {
        Class<?> declaringType = field.getDeclaringClass();
        return (declaringType.isAnnotationPresent(Entity.class)
                || declaringType.isAnnotationPresent(MappedSuperclass.class))
            && !Modifier.isStatic(field.getModifiers())
            && !Modifier.isTransient(field.getModifiers())
            && !field.isAnnotationPresent(Transient.class);
    }

    private static Violation notPersistent(Field field) {
        return new Violation(Code.FIELD_NOT_PERSISTENT, field.getName(),
            "Поле '" + field.getName() + "' не является сохраняемым JPA-полем регистра.");
    }

    private static boolean hasPropertyAccess(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (!c.isAnnotationPresent(Entity.class) && !c.isAnnotationPresent(MappedSuperclass.class)) {
                continue;
            }
            Access access = c.getDeclaredAnnotation(Access.class);
            if (access != null && access.value() == AccessType.PROPERTY) {
                return true;
            }
            for (var method : c.getDeclaredMethods()) {
                Access methodAccess = method.getDeclaredAnnotation(Access.class);
                if (method.isAnnotationPresent(Id.class) || method.isAnnotationPresent(EmbeddedId.class)
                    || (methodAccess != null && methodAccess.value() == AccessType.PROPERTY)) {
                    return true;
                }
            }
            for (Field field : c.getDeclaredFields()) {
                Access fieldAccess = field.getDeclaredAnnotation(Access.class);
                if (fieldAccess != null && fieldAccess.value() == AccessType.PROPERTY) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasUniqueConstraint(Class<?> type, List<String> keyColumns) {
        Table table = type.getAnnotation(Table.class);
        if (table == null) {
            return false;
        }
        for (UniqueConstraint constraint : table.uniqueConstraints()) {
            List<String> columns = new ArrayList<>();
            for (String column : constraint.columnNames()) {
                columns.add(column.trim().toLowerCase(Locale.ROOT));
            }
            List<String> expected = new ArrayList<>();
            for (String column : keyColumns) {
                expected.add(column.trim().toLowerCase(Locale.ROOT));
            }
            if (columns.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // ищем выше по иерархии (BaseEntity и другие @MappedSuperclass)
            }
        }
        return null;
    }
}

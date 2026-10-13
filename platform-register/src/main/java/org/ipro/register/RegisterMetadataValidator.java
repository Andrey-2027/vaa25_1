package org.ipro.register;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
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
        PERIODICITY_WITHOUT_PERIOD,
        PERIOD_NORMALIZER_MISSING,
        KEY_COLUMN_NAME,
        KEY_NULLABLE,
        KEY_UPDATABLE,
        UNIQUE_MISSING,
        RECORDER_METADATA_MISMATCH,
        RECORDER_TYPE_DECLARATION,
        RECORDER_MAPPING,
        RECORDER_CLOCK_KEY_MISSING,
        RECORDER_WRITE_GUARD_MISSING,
        PERIOD_CAPABILITY_MISMATCH,
        KEY_WRITE_RECORDED
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
        boolean recorded = RecordedRegisterEntry.class.isAssignableFrom(type);
        Class<?>[] recorderTypes = meta.recorderTypes();
        if (recorded && recorderTypes.length == 0) {
            out.add(new Violation(Code.RECORDER_METADATA_MISMATCH, null,
                "Подчинённый регистр должен явно перечислить допустимые recorderTypes."));
        } else if (!recorded && recorderTypes.length != 0) {
            out.add(new Violation(Code.RECORDER_METADATA_MISMATCH, null,
                "recorderTypes заданы, но сущность не реализует RecordedRegisterEntry."));
        }
        if (recorded && meta.keyWriteAllowed()) {
            out.add(new Violation(Code.KEY_WRITE_RECORDED, null,
                "keyWriteAllowed не применяется к подчинённому регистру: его строки принадлежат"
                    + " документу-регистратору и пишутся набором владельца (ADR-0015/0020)."));
        }
        if (recorded && periodic && !PeriodicRegisterEntry.class.isAssignableFrom(type)) {
            out.add(new Violation(Code.PERIOD_CAPABILITY_MISMATCH, meta.period(),
                "Периодический подчинённый регистр должен реализовать PeriodicRegisterEntry."));
        }
        if (PeriodicRegisterEntry.class.isAssignableFrom(type) && !periodic) {
            out.add(new Violation(Code.PERIOD_CAPABILITY_MISMATCH, null,
                "PeriodicRegisterEntry требует поле period в @RegisterMetadata."));
        }
        validateRecorderTypeDeclarations(recorderTypes, out);

        if (meta.periodicity() != RegisterPeriodicity.EXACT) {
            if (!periodic) {
                out.add(new Violation(Code.PERIODICITY_WITHOUT_PERIOD, null,
                    "Календарная периодичность требует поля периода."));
            } else if (!hasPeriodListener(type)) {
                out.add(new Violation(Code.PERIOD_NORMALIZER_MISSING, meta.period(),
                    "Календарный регистр требует @EntityListeners(RegisterPeriodListener.class)."));
            }
        }
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

        for (String name : allNames) {
            if (recorded && Set.of("recorder", "lineNumber", "recordingOrder").contains(name)) {
                out.add(new Violation(Code.RECORDER_METADATA_MISMATCH, name,
                    "Поле '" + name + "' зарезервировано контрактом регистратора и не может быть dimension/resource."));
            }
        }

        boolean recorderMappingKnown = true;
        if (recorded) {
            int violationsBefore = out.size();
            validateRecorderMapping(type, out);
            Table table = type.getAnnotation(Table.class);
            if (table == null || table.name().isBlank()) {
                out.add(new Violation(Code.RECORDER_CLOCK_KEY_MISSING, null,
                    "Подчинённый регистр должен явно задавать @Table(name) для стабильного ключа порядка записи."));
            }
            recorderMappingKnown = violationsBefore == out.size();
            if (!hasEntityListener(type, RecordedRegisterEntryWriteGuard.class)) {
                out.add(new Violation(Code.RECORDER_WRITE_GUARD_MISSING, null,
                    "Подчинённый регистр должен наследовать JPA guard, запрещающий generic writes."));
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
            if (periodic && name.equals(meta.period()) && field.getType() != LocalDateTime.class) {
                out.add(new Violation(Code.PERIOD_TYPE, name,
                    "Период '" + name + "' должен иметь тип LocalDateTime, найден "
                        + field.getType().getSimpleName() + "."));
            }
            String column = checkKeyField(type, field, meta.keyChangeAllowed(), out);
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

        List<String> rowKeyColumns = recorded
            ? List.of("recorder_type", "recorder_id", "line_no") : keyColumns;
        if (keyColumnsKnown && recorderMappingKnown && !rowKeyColumns.isEmpty()
                && !hasUniqueConstraint(type, rowKeyColumns)) {
            out.add(new Violation(Code.UNIQUE_MISSING, null,
                recorded
                    ? "Нужен @Table(uniqueConstraints) ровно по row key колонкам " + rowKeyColumns
                        + " (без dimensions и period)."
                    : "Нужен @Table(uniqueConstraints) ровно по колонкам ключа в порядке " + keyColumns
                        + " (измерения, затем период)."));
        }
        return out;
    }

    private static void validateRecorderTypeDeclarations(Class<?>[] types, List<Violation> out) {
        Set<Class<?>> seenTypes = new HashSet<>();
        Set<String> seenCodes = new HashSet<>();
        for (Class<?> recorderType : types) {
            if (recorderType == null || !seenTypes.add(recorderType)) {
                out.add(new Violation(Code.RECORDER_TYPE_DECLARATION, null,
                    "recorderTypes содержит null или повторный Java-тип."));
                continue;
            }
            if (!recorderType.isAnnotationPresent(Entity.class)) {
                out.add(new Violation(Code.RECORDER_TYPE_DECLARATION, null,
                    "Допустимый регистратор должен быть JPA @Entity: " + recorderType.getName()));
            }
            RecorderType declaration = recorderType.getAnnotation(RecorderType.class);
            if (declaration == null || !RecorderRef.isValidTypeCode(declaration.value())) {
                out.add(new Violation(Code.RECORDER_TYPE_DECLARATION, null,
                    "Допустимый регистратор должен объявить корректный @RecorderType: " + recorderType.getName()));
            } else if (!seenCodes.add(declaration.value())) {
                out.add(new Violation(Code.RECORDER_TYPE_DECLARATION, null,
                    "В recorderTypes повторяется стабильный код '" + declaration.value() + "'."));
            }
        }
    }

    private static void validateRecorderMapping(Class<?> type, List<Violation> out) {
        Field recorder = findField(type, "recorder");
        if (recorder == null || !isPersistentField(recorder) || recorder.getType() != RecorderRef.class
                || !recorder.isAnnotationPresent(Embedded.class) || !recorder.isAnnotationPresent(NotNull.class)) {
            out.add(new Violation(Code.RECORDER_MAPPING, "recorder",
                "Нужно сохраняемое @Embedded @NotNull поле recorder типа RecorderRef."));
        }
        if (!RecorderRef.class.isAnnotationPresent(jakarta.persistence.Embeddable.class)) {
            out.add(new Violation(Code.RECORDER_MAPPING, "recorder",
                "RecorderRef должен быть @Embeddable."));
        } else {
            validateRecorderReferenceColumn("typeCode", String.class, "recorder_type", out);
            validateRecorderReferenceColumn("id", Long.class, "recorder_id", out);
        }
        validateRecorderColumn(type, "lineNumber", Integer.class, "line_no", true, out);
        validateRecorderColumn(type, "recordingOrder", Long.class, "recording_order", true, out);
    }

    private static void validateRecorderReferenceColumn(String fieldName, Class<?> expectedType,
                                                       String expectedColumn, List<Violation> out) {
        Field field = findField(RecorderRef.class, fieldName);
        Column column = field == null ? null : field.getAnnotation(Column.class);
        if (field == null || field.getType() != expectedType || column == null
                || !expectedColumn.equals(column.name()) || column.nullable() || column.updatable()) {
            out.add(new Violation(Code.RECORDER_MAPPING, fieldName,
                "RecorderRef." + fieldName + " должен иметь тип " + expectedType.getSimpleName()
                    + " и колонку " + expectedColumn + " с NOT NULL/updatable=false."));
        }
    }

    private static void validateRecorderColumn(Class<?> type, String fieldName, Class<?> expectedType,
                                              String expectedColumn, boolean positive, List<Violation> out) {
        Field field = findField(type, fieldName);
        Column column = field == null ? null : effectiveColumn(type, field);
        if (field == null || !isPersistentField(field) || field.getType() != expectedType
                || column == null || !expectedColumn.equals(column.name()) || column.nullable() || column.updatable()
                || !field.isAnnotationPresent(NotNull.class)
                || positive && !field.isAnnotationPresent(Positive.class)) {
            out.add(new Violation(Code.RECORDER_MAPPING, fieldName,
                "Поле " + fieldName + " должно иметь тип " + expectedType.getSimpleName() + ", колонку "
                    + expectedColumn + ", NOT NULL, updatable=false"
                    + (positive ? " и положительное значение." : ".")));
        }
    }

    /** @return имя колонки или {@code null}, если оно не задано явно */
    private static String checkKeyField(Class<?> type, Field field, boolean keyChangeAllowed, List<Violation> out) {
        String name = field.getName();
        JoinColumn join = field.getAnnotation(JoinColumn.class);
        Column column = effectiveColumn(type, field);
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
        if (updatable != keyChangeAllowed) {
            out.add(new Violation(Code.KEY_UPDATABLE, name,
                keyChangeAllowed
                    ? "Поле ключа '" + name + "' требует updatable=true для отдельной команды смены ключа."
                    : "Поле ключа '" + name + "' изменяемо: ключ записи регистра неизменяем после создания."));
        }
        return columnName;
    }

    /** JPA may override an inherited basic mapping on the concrete entity. */
    static Column effectiveColumn(Class<?> type, Field field) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (var override : current.getDeclaredAnnotationsByType(jakarta.persistence.AttributeOverride.class)) {
                if (override.name().equals(field.getName())) return override.column();
            }
        }
        return field.getAnnotation(Column.class);
    }

    private static boolean isPersistentField(Field field) {
        Class<?> declaringType = field.getDeclaringClass();
        return (declaringType.isAnnotationPresent(Entity.class)
                || declaringType.isAnnotationPresent(MappedSuperclass.class))
            && !Modifier.isStatic(field.getModifiers())
            && !Modifier.isTransient(field.getModifiers())
            && !field.isAnnotationPresent(Transient.class);
    }

    private static boolean hasPeriodListener(Class<?> type) {
        return hasEntityListener(type, RegisterPeriodListener.class);
    }

    private static boolean hasEntityListener(Class<?> type, Class<?> listenerType) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            var listeners = current.getDeclaredAnnotation(EntityListeners.class);
            if (listeners != null && java.util.Arrays.asList(listeners.value()).contains(listenerType))
                return true;
            if (current.isAnnotationPresent(jakarta.persistence.ExcludeSuperclassListeners.class)) break;
        }
        return false;
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

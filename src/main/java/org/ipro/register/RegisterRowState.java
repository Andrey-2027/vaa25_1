package org.ipro.register;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Доступ к полям строки регистра по объявленному имени и отдельный исходный снимок для lifecycle.
 *
 * <p>Общий помощник команд регистра: {@code beforeUpdate} обязан получать независимое исходное
 * состояние, а не тот же managed instance, иначе сравнение объекта с самим собой выдаётся за
 * проверку изменения (ADR-0021, урок Р9).</p>
 */
final class RegisterRowState {

    private RegisterRowState() {
    }

    /** Shallow copy of every non-static field; the lifecycle callbacks only read values. */
    static <T> T snapshot(Class<T> type, T source) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            T copy = constructor.newInstance();
            for (Class<?> current = type; current != null && current != Object.class;
                    current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        field.set(copy, field.get(source));
                    }
                }
            }
            return copy;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Не удалось получить исходное состояние записи регистра", failure);
        }
    }

    static Object value(Class<?> type, Object row, String name) {
        try {
            return field(type, name).get(row);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static void assign(Class<?> type, Object row, String name, Object value) {
        try {
            field(type, name).set(row, value);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // ищем выше по иерархии: поля периода и ключа могут быть в базовом классе
            }
        }
        throw new IllegalStateException("Поле регистра не найдено: " + name);
    }
}

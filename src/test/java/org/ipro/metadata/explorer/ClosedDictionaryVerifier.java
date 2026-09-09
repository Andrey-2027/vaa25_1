package org.ipro.metadata.explorer;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Замкнутость словаря read-моделей Explorer (П1): в записях и вложенных records нет
 * {@code Object payload}, Vaadin-типов, callback'ов ({@code Function}/{@code Supplier})
 * и строкового SQL — только готовые резолвнутые факты. Рекурсивная рефлекс-проверка
 * компонентов records (примитивы, String, Class, enum'ы, records, {@code List<…>} из них).
 */
final class ClosedDictionaryVerifier {

    private ClosedDictionaryVerifier() {
    }

    static void verify(List<Class<?>> roots) {
        Set<Class<?>> visited = new HashSet<>();
        for (Class<?> root : roots) {
            verifyRecord(root, visited);
        }
    }

    private static void verifyRecord(Class<?> type, Set<Class<?>> visited) {
        assertThat(type.isRecord())
            .as("тип %s должен быть record для проверки компонентов", type)
            .isTrue();
        if (!visited.add(type)) {
            return;
        }
        for (var component : type.getRecordComponents()) {
            verifyType(component.getGenericType(), type, visited);
        }
    }

    private static void verifyType(Type type, Class<?> owner, Set<Class<?>> visited) {
        if (type instanceof Class<?> clazz) {
            verifyClass(clazz, owner, visited);
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw == Class.class) {
                return; // Class<?> — допускается (замкнутый класс-маркер без payload)
            }
            if (raw == List.class) {
                Type[] args = parameterized.getActualTypeArguments();
                assertThat(args).as("List в %s должен иметь один аргумент", owner).hasSize(1);
                verifyType(args[0], owner, visited);
                return;
            }
            fail("Параметризованный тип %s в %s не из замкнутого словаря (П1)", type, owner);
        }
        if (type instanceof GenericArrayType || type instanceof TypeVariable) {
            fail("Массив/type-переменная %s в %s не допускаются в словаре (П1)", type, owner);
        }
        fail("Неизвестный тип %s в %s (П1)", type, owner);
    }

    private static void verifyClass(Class<?> clazz, Class<?> owner, Set<Class<?>> visited) {
        if (clazz.isPrimitive() || clazz.isEnum()) {
            return;
        }
        if (clazz == String.class || clazz == Class.class) {
            return;
        }
        if (clazz.isRecord()) {
            verifyRecord(clazz, visited);
            return;
        }
        fail("Тип %s в %s не из замкнутого словаря read-моделей (П1): допускаются примитивы, " +
            "String, Class, enum'ы, records и List<…> из них — без Object payload, Vaadin-типов, " +
            "callback'ов (Function/Supplier) и SQL-строк", clazz.getName(), owner.getName());
    }
}

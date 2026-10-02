package org.ipro.rest.service;

import org.ipro.rest.catalog.ResolvedRestField;
import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rest.catalog.ResolvedRestResource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts scalar projection from loaded JPA entity instances into immutable Maps (F-REST-READ-3 §6.3, §8).
 * Guarantees no JPA entities, proxies, lazy collections, or callbacks escape across service boundary.
 */
public final class RestScalarProjector {

    private RestScalarProjector() {
    }

    public static Map<String, Object> project(Object entity, List<String> fieldAliases, ResolvedRestResource resource) {
        if (entity == null) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (String alias : fieldAliases) {
            ResolvedRestField field = resource.fields().get(alias);
            if (field == null) {
                continue;
            }
            Object value = extractPathValue(entity, field.source());
            result.put(alias, value);
        }

        return java.util.Collections.unmodifiableMap(result);
    }

    private static Object extractPathValue(Object root, ResolvedRestPath path) {
        Object current = root;
        for (ResolvedRestPath.Segment segment : path.segments()) {
            if (current == null) {
                return null;
            }
            current = readProperty(current, segment.name());
        }
        return current;
    }

    private static Object readProperty(Object target, String propertyName) {
        Class<?> clazz = target.getClass();
        String capitalized = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        try {
            Method getter = findMethod(clazz, "get" + capitalized);
            if (getter != null) {
                getter.setAccessible(true);
                return getter.invoke(target);
            }
            Method booleanGetter = findMethod(clazz, "is" + capitalized);
            if (booleanGetter != null) {
                booleanGetter.setAccessible(true);
                return booleanGetter.invoke(target);
            }
        } catch (Exception ignored) {
        }

        Field field = findField(clazz, propertyName);
        if (field != null) {
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}

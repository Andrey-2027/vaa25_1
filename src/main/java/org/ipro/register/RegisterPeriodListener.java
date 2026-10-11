package org.ipro.register;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

/** Normalizes period granularity and millisecond precision before writes and key checks. */
public final class RegisterPeriodListener {
    @PrePersist
    @PreUpdate
    public void beforeWrite(Object entity) {
        normalize(entity);
    }

    public static void normalize(Object entity) {
        Class<?> type = entity.getClass();
        while (type != null && !type.isAnnotationPresent(RegisterMetadata.class)) type = type.getSuperclass();
        if (type == null) throw new IllegalArgumentException("Entity does not declare @RegisterMetadata");
        var metadata = type.getAnnotation(RegisterMetadata.class);
        if (metadata.period().isBlank()) return;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(metadata.period());
                if (!field.trySetAccessible()) throw new IllegalStateException("Cannot access register period: " + field);
                LocalDateTime value = (LocalDateTime) field.get(entity);
                if (value != null) field.set(entity, metadata.periodicity().normalize(value));
                return;
            } catch (NoSuchFieldException ignored) {
                // Mapped superclass periods are supported.
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Cannot normalize register period", error);
            }
        }
        throw new IllegalStateException("Register period field not found: " + metadata.period());
    }
}

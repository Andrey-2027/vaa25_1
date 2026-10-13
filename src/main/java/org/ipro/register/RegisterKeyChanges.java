package org.ipro.register;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Validator;
import org.hibernate.Hibernate;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.FieldChangeRecord;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Dedicated key-only command; does not obtain a recorder document or call its lifecycle. */
public final class RegisterKeyChanges {
    private final EntityManager entityManager;
    private final RegisterRegistry registers;
    private final RegisterKeyChangePermissions permissions;
    private final EntityDataAccess reads;
    private final RlsPolicyEnforcer policies;
    private final Validator validator;
    private final EntityLifecycleRegistry lifecycles;
    private final EntityEventPublisher events;
    private final RlsCurrentUser currentUser;
    private final Optional<EventSink> audit;
    private final TransactionTemplate transactions;

    public RegisterKeyChanges(EntityManager entityManager, RegisterRegistry registers,
            RegisterKeyChangePermissions permissions, EntityDataAccess reads, RlsPolicyEnforcer policies,
            Validator validator, EntityLifecycleRegistry lifecycles, EntityEventPublisher events,
            RlsCurrentUser currentUser, Optional<EventSink> audit, PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.registers = registers;
        this.permissions = permissions;
        this.reads = reads;
        this.policies = policies;
        this.validator = validator;
        this.lifecycles = lifecycles;
        this.events = events;
        this.currentUser = currentUser;
        this.audit = Objects.requireNonNull(audit, "audit");
        transactions = new TransactionTemplate(transactionManager);
    }

    /** All dimensions are required. A to-one dimension takes a Long ID of its declared target type. */
    public <T extends BaseEntity> T change(Class<T> type, Long id, Long expectedVersion,
                                         Map<String, ?> dimensions, LocalDateTime period) {
        return transactions.execute(status -> changeInTransaction(type, id, expectedVersion, dimensions, period));
    }

    private <T extends BaseEntity> T changeInTransaction(Class<T> type, Long id, Long expectedVersion,
            Map<String, ?> dimensions, LocalDateTime period) {
        RegisterDescriptor descriptor = registers.require(type);
        permissions.requireOperations(descriptor);
        if (id == null || expectedVersion == null) throw new ValidationException("Перезагрузите запись: нужны ID и исходная версия.");
        if (dimensions == null || !dimensions.keySet().equals(new java.util.HashSet<>(descriptor.dimensionNames()))) {
            throw new ValidationException("Укажите все измерения регистра без дополнительных полей.");
        }
        if (descriptor.periodic() != (period != null)) throw new ValidationException("Укажите период записи.");
        if (descriptor.recorded()) RegisterWriteMutex.lock(entityManager, type, 1_000);
        else entityManager.flush();
        T row = type.cast(Hibernate.unproxy(reads.detail(type, id).orElseThrow(RegisterKeyChanges::unavailable)));
        // Refresh while locked; a first-level cache object is not an optimistic-lock check.
        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE,
            Map.of("jakarta.persistence.lock.timeout", 1_000));
        // Reapply the read policy to the refreshed state, including mandatory row predicates.
        if (reads.detail(type, id).isEmpty()) throw unavailable();
        if (!Objects.equals(row.getVersion(), expectedVersion)) {
            throw new OptimisticLockException("Запись уже изменена. Перезагрузите её и повторите действие.");
        }
        policies.requireUpdate(row);
        T original = RegisterRowState.snapshot(type, row);
        Map<String, Object> oldKey = key(descriptor, row);
        Map<String, Object> newKey = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (var field : descriptor.dimensions()) {
            Object value = dimensions.get(field.name());
            if (value == null) throw new ValidationException("Не заполнено измерение «" + field.name() + "».");
            if (field.mapping() == RegisterDescriptor.Mapping.TO_ONE) {
                if (!(value instanceof Long referenceId) || referenceId <= 0) {
                    throw new ValidationException("Измерение «" + field.name() + "» требует ID ссылки.");
                }
                value = reads.detail(field.javaType(), referenceId).orElseThrow(RegisterKeyChanges::unavailable);
            } else if (field.mapping() != RegisterDescriptor.Mapping.BASIC || !boxed(field.javaType()).isInstance(value)) {
                throw new ValidationException("Неверный тип измерения «" + field.name() + "».");
            }
            values.put(field.name(), value);
            newKey.put(field.name(), RegisterKeyWriteGuardInstaller.scalar(value));
        }
        descriptor.period().ifPresent(field -> {
            LocalDateTime normalized = descriptor.periodicity().normalize(period);
            values.put(field.name(), normalized);
            newKey.put(field.name(), normalized);
        });
        if (oldKey.equals(newKey)) return row; // No UPDATE, lifecycle or audit for a normalized no-op.
        EventContext context = events.contextFor(type, id, EventSource.SYSTEM, "change-register-key:" + descriptor.entityName());
        try (var scope = RegisterKeyChangeScope.open(row,
                entityManager.unwrap(SharedSessionContractImplementor.class), oldKey, newKey)) {
            values.forEach((name, value) -> RegisterRowState.assign(type, row, name, value));
            policies.requireUpdate(row);
            events.publishSaving(row, context);
            lifecycles.beforeSave(type, row, context);
            lifecycles.beforeUpdate(type, original, row, context);
            if (!key(descriptor, row).equals(newKey)) throw new ValidationException("Обработчик изменил запрошенный ключ записи.");
            var violations = validator.validate(row);
            if (!violations.isEmpty()) throw new ValidationException(violations.stream()
                .map(value -> value.getPropertyPath() + ": " + value.getMessage()).sorted()
                .collect(java.util.stream.Collectors.joining("; ")));
            policies.requireUpdate(row);
            policies.recordCanonicalWrite(row);
            try {
                entityManager.flush();
            } catch (RuntimeException failure) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (!descriptor.recorded() && cause instanceof java.sql.SQLException sql
                            && "23505".equals(sql.getSQLState())) {
                        throw new ValidationException("Запись с такими измерениями и периодом уже существует.", failure);
                    }
                }
                throw failure;
            }
            audit(descriptor, id, oldKey, newKey, context);
            events.publishSaved(row, context);
            events.publishChanged(row, context);
            // Only the row callback runs. It receives no owner write authority.
            lifecycles.onSave(type, row, context);
            entityManager.flush();
        }
        return row;
    }

    private void audit(RegisterDescriptor descriptor, Long id, Map<String, Object> oldKey,
                       Map<String, Object> newKey, EventContext context) {
        if (audit.isEmpty()) return; // Same optional telemetry contract as other canonical writes.
        var changes = new ArrayList<Map<String, String>>();
        for (String field : oldKey.keySet()) {
            if (!Objects.equals(oldKey.get(field), newKey.get(field))) {
                changes.add(Map.of("field", field, "old", String.valueOf(oldKey.get(field)),
                    "new", String.valueOf(newKey.get(field)), "operation", context.operationName()));
            }
        }
        try {
            audit.orElseThrow().acceptFieldChangeDurable(new FieldChangeRecord(Instant.now(), "UPDATE", descriptor.entityType().getSimpleName(),
                id.toString(), currentUser.requireAuthenticatedUsername(), context.correlationId(), changes.size(),
                new ObjectMapper().writeValueAsString(changes)));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Не удалось записать аудит изменения ключа", failure);
        }
    }

    private static Map<String, Object> key(RegisterDescriptor descriptor, Object row) {
        var values = new LinkedHashMap<String, Object>();
        for (var field : descriptor.dimensions()) values.put(field.name(), RegisterKeyWriteGuardInstaller.scalar(
            RegisterRowState.value(descriptor.entityType(), row, field.name())));
        descriptor.period().ifPresent(field -> values.put(field.name(),
            RegisterRowState.value(descriptor.entityType(), row, field.name())));
        return values;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        throw new IllegalArgumentException("Unsupported register dimension type: " + type);
    }
    private static ValidationException unavailable() {
        return new ValidationException("Запись или выбранный объект недоступны. Перезагрузите список и проверьте права.");
    }
}

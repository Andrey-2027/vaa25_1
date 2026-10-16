package org.ipro.register;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.validation.Validator;
import org.hibernate.StaleStateException;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.FieldChangeRecord;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Запись регистра по ключу (ADR-0023): ресурсы для полного ключа и удаление по ключу.
 *
 * <p><b>Контракт.</b> {@code set} без ожидаемой версии — upsert: отсутствующая строка создаётся,
 * существующая получает запрошенные ресурсы. {@code set} с ожидаемой версией — проверяемая запись:
 * {@link Verdict#ABSENT} вместо неявного создания и {@link Verdict#VERSION_CONFLICT} вместо
 * перезаписи чужого решения. {@code delete} требует версии. Ключ этим контрактом не меняется:
 * другой ключ — другая строка, а перенос строки остаётся командой {@link RegisterKeyChanges}.</p>
 *
 * <p><b>Границы.</b> Запись доступна только типу, объявившему {@code keyWriteAllowed}, и только
 * самостоятельному регистру: строки подчинённого принадлежат документу-регистратору. Проверки C5
 * (операция {@code SET_KEY}/{@code DELETE_KEY} плюс ENTITY READ и CREATE/UPDATE/DELETE), RLS-значения
 * строки, row lifecycle, Bean Validation, canonical-фиксация записи и durable-аудит выполняются тем
 * же порядком, что и в остальных canonical write-путях.</p>
 *
 * <p><b>Конфликты.</b> Гонку отсутствующего ключа решает физический UNIQUE, а не блокировка: её
 * нечем взять. Классификация идёт по SQLState, и повторяются только транзиентные отказы. Каждая
 * попытка — своя транзакция, потому что после ошибки оператора транзакция PostgreSQL непригодна
 * (25P02): «докрутить» её нельзя. Бюджет повторов ограничен; его исчерпание — вердикт
 * {@link Verdict#RETRY_EXHAUSTED}, а не исключение фреймворка.</p>
 *
 * <p><b>Владелец повтора.</b> Эта команда автономна: своя транзакция на попытку и bounded retry.
 * Внутри чужой транзакции вызывающий использует ту же команду, но обязан сам решить судьбу
 * конфликта — {@code REQUIRES_NEW} внутри обычной записи регистра не вводится.</p>
 */
public final class RegisterKeyWrites {

    /** Возможные исходы операции. Часть из них — отказы, а не исключения (§ ADR-0023). */
    public enum Verdict {
        CREATED, UPDATED, UNCHANGED, DELETED, ABSENT, VERSION_REQUIRED, VERSION_CONFLICT,
        CONCURRENT_INSERT, RETRY_EXHAUSTED, LOCK_TIMEOUT, SERIALIZATION_FAILURE, DEADLOCK
    }

    /** Результат операции: вердикт и строка, если она есть после операции. */
    public record Result<T>(Verdict verdict, T row) {

        public Result {
            Objects.requireNonNull(verdict, "verdict");
        }

        /** true, если строка создана или изменена этой операцией. */
        public boolean written() {
            return verdict == Verdict.CREATED || verdict == Verdict.UPDATED;
        }
    }

    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int LOCK_TIMEOUT_MILLIS = 1_000;
    private static final String LOCK_TIMEOUT_HINT = "jakarta.persistence.lock.timeout";

    private final EntityManager entityManager;
    private final RegisterRegistry registers;
    private final RegisterKeyWritePermissions permissions;
    private final EntityDataAccess reads;
    private final RlsPolicyEnforcer policies;
    private final Validator validator;
    private final EntityLifecycleRegistry lifecycles;
    private final EntityEventPublisher events;
    private final RlsCurrentUser currentUser;
    private final Optional<EventSink> audit;
    private final TransactionTemplate transactions;
    private final int maxAttempts;

    public RegisterKeyWrites(EntityManager entityManager, RegisterRegistry registers,
            RegisterKeyWritePermissions permissions, EntityDataAccess reads, RlsPolicyEnforcer policies,
            Validator validator, EntityLifecycleRegistry lifecycles, EntityEventPublisher events,
            RlsCurrentUser currentUser, Optional<EventSink> audit, PlatformTransactionManager transactionManager) {
        this(entityManager, registers, permissions, reads, policies, validator, lifecycles, events,
            currentUser, audit, transactionManager, DEFAULT_MAX_ATTEMPTS);
    }

    /** Бюджет повторов задаётся явно там, где вызывающий владеет политикой повторов. */
    public RegisterKeyWrites(EntityManager entityManager, RegisterRegistry registers,
            RegisterKeyWritePermissions permissions, EntityDataAccess reads, RlsPolicyEnforcer policies,
            Validator validator, EntityLifecycleRegistry lifecycles, EntityEventPublisher events,
            RlsCurrentUser currentUser, Optional<EventSink> audit, PlatformTransactionManager transactionManager,
            int maxAttempts) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.registers = Objects.requireNonNull(registers, "registers");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
        this.events = Objects.requireNonNull(events, "events");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        this.audit = Objects.requireNonNull(audit, "audit");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Записать ресурсы для ключа без проверки версии (upsert).
     *
     * <p>Отсутствующая строка создаётся, существующая получает запрошенные ресурсы; ключ не
     * меняется. Повтор с теми же ресурсами — {@link Verdict#UNCHANGED} без UPDATE, версии,
     * callbacks и аудита.</p>
     */
    public <T extends BaseEntity> Result<T> set(Class<T> type, Map<String, ?> dimensions,
            Map<String, ?> resources, LocalDateTime period) {
        return set(type, dimensions, resources, period, null);
    }

    /** Записать ресурсы для ключа с ожидаемой версией: отсутствие строки — отказ, а не создание. */
    public <T extends BaseEntity> Result<T> set(Class<T> type, Map<String, ?> dimensions,
            Map<String, ?> resources, LocalDateTime period, Long expectedVersion) {
        RegisterDescriptor descriptor = registers.require(type);
        if (expectedVersion != null && expectedVersion < 0) {
            throw new ValidationException("Исходная версия не может быть отрицательной.");
        }
        return attempt(() -> setInTransaction(descriptor, dimensions, resources, period, expectedVersion),
            expectedVersion != null);
    }

    /** Удалить строку по ключу; версия обязательна, отсутствие строки — вердикт {@code ABSENT}. */
    public <T extends BaseEntity> Result<T> delete(Class<T> type, Map<String, ?> dimensions,
            LocalDateTime period, Long expectedVersion) {
        RegisterDescriptor descriptor = registers.require(type);
        return attempt(() -> deleteInTransaction(descriptor, dimensions, period, expectedVersion), true);
    }

    // === попытки и классификация ===

    private <T extends BaseEntity> Result<T> attempt(Supplier<Result<T>> operation, boolean checked) {
        for (int number = 1; number <= maxAttempts; number++) {
            try {
                return transactions.execute(status -> operation.get());
            } catch (PersistenceException failure) {
                Verdict verdict = classify(failure);
                if (verdict == null) throw failure;
                if (number < maxAttempts && retryable(verdict)) continue;
                return new Result<>(verdict == Verdict.CONCURRENT_INSERT && maxAttempts > 1 && !checked
                    ? Verdict.RETRY_EXHAUSTED : verdict, null);
            }
        }
        throw new IllegalStateException("unreachable: retry budget is positive");
    }

    /**
     * Классификация отказа по SQLState (таблица ADR-0023).
     *
     * @return класс отказа или {@code null}, если отказ не классифицирован: незнакомое не «лечится» повтором
     */
    static Verdict classify(Throwable failure) {
        List<String> states = sqlStates(failure);
        if (states.contains("23505")) return Verdict.CONCURRENT_INSERT;
        if (states.contains("40001")) return Verdict.SERIALIZATION_FAILURE;
        if (states.contains("40P01")) return Verdict.DEADLOCK;
        if (states.contains("55P03") || states.contains("57014")) return Verdict.LOCK_TIMEOUT;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockException || cause instanceof StaleStateException) {
                return Verdict.VERSION_CONFLICT;
            }
        }
        return null;
    }

    /** Повторяются только транзиентные отказы; чужое решение и истёкшее ожидание — нет. */
    static boolean retryable(Verdict verdict) {
        return verdict == Verdict.CONCURRENT_INSERT || verdict == Verdict.SERIALIZATION_FAILURE
            || verdict == Verdict.DEADLOCK;
    }

    private static List<String> sqlStates(Throwable failure) {
        List<String> states = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) states.add(sql.getSQLState());
        }
        return states;
    }

    // === операции внутри одной транзакции ===

    @SuppressWarnings("unchecked")
    private <T extends BaseEntity> Result<T> setInTransaction(RegisterDescriptor descriptor,
            Map<String, ?> dimensions, Map<String, ?> resources, LocalDateTime period, Long expectedVersion) {
        permissions.requireSet(descriptor);
        Map<String, Object> key = resolveKey(descriptor, dimensions, period);
        Map<String, Object> values = resolveResources(descriptor, resources);
        T row = lockedRow(descriptor, key);
        if (row == null) {
            if (expectedVersion != null) return new Result<>(Verdict.ABSENT, null);
            return create((Class<T>) descriptor.entityType(), descriptor, key, values);
        }
        if (expectedVersion != null && !expectedVersion.equals(row.getVersion())) {
            return new Result<>(Verdict.VERSION_CONFLICT, row);
        }
        return update((Class<T>) descriptor.entityType(), descriptor, row, values);
    }

    @SuppressWarnings("unchecked")
    private <T extends BaseEntity> Result<T> deleteInTransaction(RegisterDescriptor descriptor,
            Map<String, ?> dimensions, LocalDateTime period, Long expectedVersion) {
        permissions.requireDelete(descriptor);
        Class<T> type = (Class<T>) descriptor.entityType();
        Map<String, Object> key = resolveKey(descriptor, dimensions, period);
        T row = lockedRow(descriptor, key);
        if (row == null) return new Result<>(Verdict.ABSENT, null);
        if (expectedVersion == null) return new Result<>(Verdict.VERSION_REQUIRED, row);
        if (!expectedVersion.equals(row.getVersion())) return new Result<>(Verdict.VERSION_CONFLICT, row);
        policies.requireDelete(row);
        Long id = row.getId();
        EventContext context = events.contextFor(type, id, EventSource.SYSTEM, operation("delete", descriptor));
        events.publishDeleting(row, context);
        lifecycles.beforeDelete(type, row, context);
        List<Map<String, String>> changes = keyChanges(key, context);
        policies.recordCanonicalDelete(row);
        entityManager.remove(row);
        entityManager.flush();
        audit(descriptor, id, "DELETE", changes, context);
        events.publishDeleted(row, context);
        return new Result<>(Verdict.DELETED, row);
    }

    private <T extends BaseEntity> Result<T> create(Class<T> type, RegisterDescriptor descriptor,
            Map<String, Object> key, Map<String, Object> values) {
        T row = newInstance(type);
        key.forEach((name, value) -> RegisterRowState.assign(type, row, name, value));
        values.forEach((name, value) -> RegisterRowState.assign(type, row, name, value));
        policies.requireUpdate(row);
        EventContext context = events.contextFor(type, null, EventSource.SYSTEM, operation("set", descriptor));
        events.publishSaving(row, context);
        lifecycles.beforeSave(type, row, context);
        validate(descriptor, row);
        policies.requireUpdate(row);
        policies.recordCanonicalWrite(row);
        entityManager.persist(row);
        entityManager.flush();
        Long id = row.getId();
        // Созданная строка: и ключ, и ресурсы — новые значения; old остаётся пустым (как в ADR-0021).
        List<Map<String, String>> changes = new ArrayList<>(assigned(key, context));
        changes.addAll(assigned(values, context));
        audit(descriptor, id, "CREATE", changes, context);
        events.publishSaved(row, context);
        events.publishChanged(row, context);
        lifecycles.onSave(type, row, context);
        entityManager.flush();
        return new Result<>(Verdict.CREATED, row);
    }

    private <T extends BaseEntity> Result<T> update(Class<T> type, RegisterDescriptor descriptor,
            T row, Map<String, Object> values) {
        Map<String, Object> before = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object current = RegisterRowState.value(type, row, entry.getKey());
            before.put(entry.getKey(), current);
            if (!sameValue(entry.getValue(), current)) changed = true;
        }
        if (!changed) return new Result<>(Verdict.UNCHANGED, row);
        policies.requireUpdate(row);
        T original = RegisterRowState.snapshot(type, row);
        EventContext context = events.contextFor(type, row.getId(), EventSource.SYSTEM, operation("set", descriptor));
        values.forEach((name, value) -> RegisterRowState.assign(type, row, name, value));
        events.publishSaving(row, context);
        lifecycles.beforeSave(type, row, context);
        lifecycles.beforeUpdate(type, original, row, context);
        validate(descriptor, row);
        policies.requireUpdate(row);
        policies.recordCanonicalWrite(row);
        entityManager.flush();
        List<Map<String, String>> changes = new ArrayList<>();
        values.forEach((name, value) -> {
            Object current = before.get(name);
            if (!sameValue(value, current)) changes.add(change(name, current, value, context));
        });
        audit(descriptor, row.getId(), "UPDATE", changes, context);
        events.publishSaved(row, context);
        events.publishChanged(row, context);
        lifecycles.onSave(type, row, context);
        entityManager.flush();
        return new Result<>(Verdict.UPDATED, row);
    }

    // === ключ, ресурсы, строка ===

    private Map<String, Object> resolveKey(RegisterDescriptor descriptor, Map<String, ?> dimensions,
            LocalDateTime period) {
        if (dimensions == null
                || !dimensions.keySet().equals(new LinkedHashSet<>(descriptor.dimensionNames()))) {
            throw new ValidationException(
                "KEY_WRITE_KEY: укажите все измерения регистра без дополнительных полей.");
        }
        if (descriptor.periodic() != (period != null)) {
            throw new ValidationException("KEY_WRITE_KEY: укажите период записи регистра.");
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (RegisterDescriptor.FieldDescriptor field : descriptor.dimensions()) {
            resolved.put(field.name(), resolveValue(field, dimensions.get(field.name()), "измерения"));
        }
        descriptor.period().ifPresent(field ->
            resolved.put(field.name(), descriptor.periodicity().normalize(period)));
        return resolved;
    }

    private Map<String, Object> resolveResources(RegisterDescriptor descriptor, Map<String, ?> resources) {
        if (resources == null || resources.isEmpty()) return Map.of();
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : resources.entrySet()) {
            RegisterDescriptor.FieldDescriptor field = descriptor.resources().stream()
                .filter(candidate -> candidate.name().equals(entry.getKey())).findFirst()
                .orElseThrow(() -> new ValidationException("KEY_WRITE_RESOURCE_TYPE: поле «"
                    + entry.getKey() + "» не объявлено ресурсом регистра."));
            resolved.put(field.name(), resolveValue(field, entry.getValue(), "ресурса"));
        }
        return resolved;
    }

    private Object resolveValue(RegisterDescriptor.FieldDescriptor field, Object value, String role) {
        if (value == null) {
            throw new ValidationException("KEY_WRITE_KEY: не заполнено поле " + role + " «"
                + field.name() + "».");
        }
        if (field.mapping() == RegisterDescriptor.Mapping.TO_ONE) {
            if (!(value instanceof Long referenceId) || referenceId <= 0) {
                throw new ValidationException("KEY_WRITE_KEY: поле " + role + " «" + field.name()
                    + "» требует ID ссылки.");
            }
            return reads.detail(field.javaType(), referenceId).orElseThrow(RegisterKeyWrites::unavailable);
        }
        if (field.mapping() != RegisterDescriptor.Mapping.BASIC || !boxed(field.javaType()).isInstance(value)) {
            throw new ValidationException("KEY_WRITE_VALUE_TYPE: неверный тип поля " + role + " «"
                + field.name() + "».");
        }
        return value;
    }

    /**
     * Строка по полному ключу: canonical чтение (экспозиция, C5/RLS) и пессимистическая блокировка.
     *
     * <p>Блокировать отсутствующую строку нечем — там арбитром остаётся физический UNIQUE. После
     * блокировки чтение повторяется: строка могла исчезнуть между первым чтением и lock'ом, а
     * повторное чтение заново применяет политику чтения.</p>
     */
    private <T extends BaseEntity> T lockedRow(RegisterDescriptor descriptor, Map<String, Object> key) {
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) descriptor.entityType();
        Sort sort = Sort.by(descriptor.dimensionNames().toArray(String[]::new));
        Specification<T> byKey = (root, query, builder) -> {
            var predicate = builder.conjunction();
            for (Map.Entry<String, Object> entry : key.entrySet()) {
                if (entry.getValue() instanceof IdentifiableEntity<?> reference) {
                    predicate = builder.and(predicate,
                        builder.equal(root.get(entry.getKey()).get("id"), reference.getId()));
                } else {
                    predicate = builder.and(predicate, builder.equal(root.get(entry.getKey()), entry.getValue()));
                }
            }
            return predicate;
        };
        Optional<T> found = reads.first(type, byKey, sort);
        if (found.isEmpty()) return null;
        entityManager.refresh(found.get(), LockModeType.PESSIMISTIC_WRITE,
            Map.of(LOCK_TIMEOUT_HINT, LOCK_TIMEOUT_MILLIS));
        return reads.first(type, byKey, sort).orElse(null);
    }

    private void validate(RegisterDescriptor descriptor, Object row) {
        var violations = validator.validate(row);
        if (violations.isEmpty()) return;
        throw new ValidationException(violations.stream()
            .map(value -> value.getPropertyPath() + ": " + value.getMessage()).sorted()
            .collect(java.util.stream.Collectors.joining("; ")));
    }

    @SuppressWarnings("unchecked")
    private static <T extends BaseEntity> T newInstance(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Регистр " + type.getName()
                + " должен иметь конструктор без аргументов", failure);
        }
    }

    // === аудит ===

    private void audit(RegisterDescriptor descriptor, Long id, String verb,
            List<Map<String, String>> changes, EventContext context) {
        if (audit.isEmpty() || changes.isEmpty() || id == null) return; // та же необязательность, что у остальных canonical write
        try {
            audit.orElseThrow().acceptFieldChangeDurable(new FieldChangeRecord(Instant.now(), verb,
                descriptor.entityType().getSimpleName(), id.toString(),
                currentUser.requireAuthenticatedUsername(), context.correlationId(), changes.size(),
                new ObjectMapper().writeValueAsString(changes)));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Не удалось записать аудит записи регистра по ключу", failure);
        }
    }

    /**
     * Совпадение запрошенного значения с текущим. Числа сравниваются по величине, а не по
     * масштабу: {@code numeric(19,4)} возвращает {@code 100.0000} для записанного {@code 100}.
     * Hibernate считает такие значения равными в своём dirty check (compareTo), поэтому
     * повтор с тем же ресурсом не должен выдаваться за UPDATE с версией и аудитом.
     */
    private static boolean sameValue(Object requested, Object current) {
        if (requested == current) return true;
        if (requested == null || current == null) return false;
        if (requested instanceof BigDecimal left && current instanceof BigDecimal right) {
            return left.compareTo(right) == 0;
        }
        return Objects.equals(requested, current);
    }

    private static List<Map<String, String>> keyChanges(Map<String, Object> key, EventContext context) {
        List<Map<String, String>> changes = new ArrayList<>();
        key.forEach((name, value) -> changes.add(change(name, value, null, context)));
        return changes;
    }

    private static List<Map<String, String>> assigned(Map<String, Object> values, EventContext context) {
        List<Map<String, String>> changes = new ArrayList<>();
        values.forEach((name, value) -> changes.add(change(name, null, value, context)));
        return changes;
    }

    private static Map<String, String> change(String name, Object oldValue, Object newValue,
            EventContext context) {
        return Map.of("field", name,
            "old", oldValue == null ? "" : String.valueOf(RegisterKeyWriteGuardInstaller.scalar(oldValue)),
            "new", newValue == null ? "" : String.valueOf(RegisterKeyWriteGuardInstaller.scalar(newValue)),
            "operation", context.operationName());
    }

    private static String operation(String verb, RegisterDescriptor descriptor) {
        return verb + "-register-key:" + descriptor.entityName();
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
        throw new IllegalArgumentException("Unsupported register field type: " + type);
    }

    private static ValidationException unavailable() {
        return new ValidationException(
            "Запись или выбранный объект недоступны. Перезагрузите список и проверьте права.");
    }
}

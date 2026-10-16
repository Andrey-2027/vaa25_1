package org.ipro.register;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.ValidationException;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.register.persistence.RecorderOrderClock;
import org.ipro.rls.RlsFilterActivator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Internal transaction-participating recorder operations; callers supply checked owner authority. */
final class RecorderSetWriter {
    private static final String LOCK_TIMEOUT_HINT = "jakarta.persistence.lock.timeout";
    /** Имена операций строки набора: по ним правило различает путь, записавший строку. */
    private static final String SET_OPERATION = "set-register-recorder-set";
    private static final String DELETE_OPERATION = "delete-register-recorder-set";

    private final RegisterRegistry registers;
    private final RecorderTypeRegistry recorderTypes;
    private final Validator validator;
    /**
     * ADR-0025: реестр — обязательный участник писателя набора, а не необязательное удобство.
     * Конструктора без него нет намеренно: «забыли передать» должно быть ошибкой сборки, а не
     * тихим возвратом к пути, который хуки не вызывает.
     */
    private final EntityLifecycleRegistry lifecycles;
    private final int lockTimeoutMillis;
    private final RlsFilterActivator rlsFilterActivator;
    private final RecorderSetWriteAuthorizer writeAuthorizer;

    RecorderSetWriter(RegisterRegistry registers, RecorderTypeRegistry recorderTypes,
                      Validator validator, EntityLifecycleRegistry lifecycles, int lockTimeoutMillis) {
        this(registers, recorderTypes, validator, lifecycles, lockTimeoutMillis, null, request -> { });
    }

    RecorderSetWriter(RegisterRegistry registers, RecorderTypeRegistry recorderTypes,
                      Validator validator, EntityLifecycleRegistry lifecycles, int lockTimeoutMillis,
                      RlsFilterActivator rlsFilterActivator) {
        this(registers, recorderTypes, validator, lifecycles, lockTimeoutMillis, rlsFilterActivator,
            request -> { });
    }

    RecorderSetWriter(RegisterRegistry registers, RecorderTypeRegistry recorderTypes,
                      Validator validator, EntityLifecycleRegistry lifecycles, int lockTimeoutMillis,
                      RlsFilterActivator rlsFilterActivator,
                      RecorderSetWriteAuthorizer writeAuthorizer) {
        this.registers = Objects.requireNonNull(registers, "registers");
        this.recorderTypes = Objects.requireNonNull(recorderTypes, "recorderTypes");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
        if (lockTimeoutMillis < 1) throw new IllegalArgumentException("lockTimeoutMillis must be positive");
        this.lockTimeoutMillis = lockTimeoutMillis;
        this.rlsFilterActivator = rlsFilterActivator;
        this.writeAuthorizer = Objects.requireNonNull(writeAuthorizer, "writeAuthorizer");
    }

    /**
     * Atomically replaces one recorder's complete row set in the caller's transaction.
     * This package-private operation deliberately does not provide an application write API.
     */
    <T extends BaseEntity & RecordedRegisterEntry> Result replaceForRecorder(
            EntityManager entityManager, Class<T> registerType, RecorderRef recorder,
            Long expectedDocumentVersion, List<? extends T> proposedRows) {
        return replaceForRecorder(entityManager, registerType, recorder,
            expectedDocumentVersion, proposedRows, true);
    }

    <T extends BaseEntity & RecordedRegisterEntry> Result replaceForRecorder(
            EntityManager entityManager, Class<T> registerType, RecorderRef recorder,
            Long expectedDocumentVersion, List<? extends T> proposedRows,
            boolean incrementDocumentVersion) {
        Objects.requireNonNull(entityManager, "entityManager");
        Objects.requireNonNull(registerType, "registerType");
        Objects.requireNonNull(recorder, "recorder");
        Objects.requireNonNull(expectedDocumentVersion, "expectedDocumentVersion");
        Objects.requireNonNull(proposedRows, "proposedRows");
        if (!entityManager.isJoinedToTransaction()) {
            throw new IllegalStateException("Recorder-set replacement must join the caller's transaction");
        }
        if (expectedDocumentVersion < 0) {
            throw new IllegalArgumentException("expectedDocumentVersion must be non-negative");
        }

        RegisterDescriptor descriptor = registers.require(registerType);
        if (!descriptor.recorded()) {
            throw new IllegalArgumentException("REGISTER_NOT_RECORDED: " + registerType.getName());
        }
        Class<?> recorderType = recorderTypes.resolveType(recorder);
        if (!descriptor.recorderTypes().contains(recorderType)
                || !descriptor.recorderTypeCodes().contains(recorder.getTypeCode())) {
            throw new IllegalArgumentException("RECORDER_TYPE_NOT_ALLOWED: " + recorder.getTypeCode()
                + " for " + registerType.getName());
        }

        List<T> newRows = prepareRows(registerType, recorder, proposedRows);
        Map<String, Object> lockHints = Map.of(LOCK_TIMEOUT_HINT, lockTimeoutMillis);
        Object documentValue = entityManager.find(recorderType, recorder.getId(),
            LockModeType.PESSIMISTIC_WRITE, lockHints);
        if (!(documentValue instanceof BaseEntity document)) {
            if (documentValue == null) throw new EntityNotFoundException("RECORDER_DOCUMENT_NOT_FOUND");
            throw new IllegalStateException("RECORDER_DOCUMENT_VERSION_UNSUPPORTED: " + recorderType.getName());
        }
        Long actualVersion = document.getVersion();
        if (!expectedDocumentVersion.equals(actualVersion)) {
            throw new OptimisticLockException("RECORDER_VERSION_CONFLICT: expected="
                + expectedDocumentVersion + ", actual=" + actualVersion);
        }

        RecorderOrderClock clock = lockRegister(entityManager, registerType);
        long order = clock.advance();
        newRows.forEach(row -> row.setRecordingOrder(order));
        // ADR-0025: правило строки исполняется и на этом пути. Хук идёт после подготовки строки
        // (номер, порядок записи, нормализация периода) и до Bean Validation — тот же порядок,
        // что у записи по ключу, поэтому валидация видит последствия мутации из хука.
        invokeBeforeSave(registerType, descriptor, newRows);
        validateProposed(newRows);

        List<T> oldRows = loadCompleteSet(entityManager, registerType, recorder, false);
        validateOldRows(oldRows, recorder);
        writeAuthorizer.authorize(new RecorderSetWriteRequest(registerType, document, recorder,
            oldRows, newRows));

        // The document lock serializes writers for this recorder. A force-incrementing
        // pessimistic lock closes the stale-payload window for document-only and register-only saves.
        if (incrementDocumentVersion) {
            entityManager.lock(document, LockModeType.PESSIMISTIC_FORCE_INCREMENT, lockHints);
        }
        List<T> affectedRows = new ArrayList<>(oldRows);
        affectedRows.addAll(newRows);
        try (RecorderSetWriteContext.Scope ignored = RecorderSetWriteContext.openRows(registerType, recorder, affectedRows)) {
            invokeBeforeDelete(registerType, descriptor, oldRows);
            oldRows.forEach(entityManager::remove);
            entityManager.flush();
            newRows.forEach(entityManager::persist);
            entityManager.flush();
            invokeOnSave(registerType, descriptor, newRows);
        }
        Long nextVersion = document.getVersion();
        if (incrementDocumentVersion) {
            try {
                nextVersion = Math.addExact(expectedDocumentVersion, 1L);
            } catch (ArithmeticException exhausted) {
                throw new IllegalStateException("RECORDER_VERSION_EXHAUSTED: " + recorder, exhausted);
            }
        }
        return new Result(oldRows.size(), newRows.size(), order, nextVersion);
    }

    /**
     * Closed document-lifecycle cleanup. The caller must already hold the recorder document
     * lock and must have established delete authority for that root.
     */
    int deleteForRecorder(EntityManager entityManager, Class<?> registerType, RecorderRef recorder) {
        Objects.requireNonNull(entityManager, "entityManager");
        Objects.requireNonNull(registerType, "registerType");
        Objects.requireNonNull(recorder, "recorder");
        if (!entityManager.isJoinedToTransaction()) {
            throw new IllegalStateException("Recorder-set cleanup must join the root delete transaction");
        }
        RegisterDescriptor descriptor = registers.require(registerType);
        if (!descriptor.recorded()) {
            throw new IllegalArgumentException("REGISTER_NOT_RECORDED: " + registerType.getName());
        }
        Class<?> recorderType = recorderTypes.resolveType(recorder);
        if (!descriptor.recorderTypes().contains(recorderType)
                || !descriptor.recorderTypeCodes().contains(recorder.getTypeCode())) {
            throw new IllegalArgumentException("RECORDER_TYPE_NOT_ALLOWED: " + recorder.getTypeCode()
                + " for " + registerType.getName());
        }
        lockRegister(entityManager, registerType);
        return deleteRows(entityManager, registerType, recorder);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int deleteRows(EntityManager entityManager, Class<?> registerType, RecorderRef recorder) {
        return deleteTypedRows(entityManager, (Class) registerType, recorder);
    }

    private <T extends BaseEntity & RecordedRegisterEntry> int deleteTypedRows(
            EntityManager entityManager, Class<T> registerType, RecorderRef recorder) {
        List<T> rows = loadCompleteSet(entityManager, registerType, recorder, true);
        if (rows.isEmpty()) return 0;
        for (RecordedRegisterEntry row : rows) {
            if (!recorder.equals(row.getRecorder())) {
                throw new IllegalStateException("RECORDER_SET_CORRUPT: cleanup query escaped its recorder scope");
            }
        }
        BaseEntity document = (BaseEntity) entityManager.getReference(recorderTypes.resolveType(recorder), recorder.getId());
        writeAuthorizer.authorize(new RecorderSetWriteRequest(registerType, document, recorder, rows, List.of()));
        try (RecorderSetWriteContext.Scope ignored = RecorderSetWriteContext.openRows(registerType, recorder, rows)) {
            invokeBeforeDelete(registerType, registers.require(registerType), rows);
            rows.forEach(entityManager::remove);
            entityManager.flush();
        }
        return rows.size();
    }

    /** Append preserves all existing ids, line numbers and recording order. */
    <T extends BaseEntity & RecordedRegisterEntry> List<T> addForRecorder(
            EntityManager entityManager, Class<T> registerType, RecorderRef recorder,
            BaseEntity document, List<? extends T> proposedRows) {
        requireOwner(entityManager, registerType, recorder, document);
        RegisterDescriptor descriptor = registers.require(registerType);
        RecorderOrderClock clock = lockRegister(entityManager, registerType);
        List<T> existing = loadCompleteSet(entityManager, registerType, recorder, false);
        validateOldRows(existing, recorder);
        int lastLine = 0;
        Long order = null;
        for (T row : existing) {
            if (row.getLineNumber() <= lastLine || row.getRecordingOrder() <= 0
                    || (order != null && !order.equals(row.getRecordingOrder()))) {
                throw new IllegalStateException("RECORDER_SET_CORRUPT: invalid line number or mixed recording orders");
            }
            lastLine = row.getLineNumber();
            order = row.getRecordingOrder();
        }
        List<T> rows = prepareRows(registerType, recorder, proposedRows, lastLine, true);
        if (rows.isEmpty()) return rows;
        long recordingOrder = order == null ? clock.advance() : order;
        rows.forEach(row -> row.setRecordingOrder(recordingOrder));
        // Существующие строки набора здесь не касаются: хук получают ровно добавляемые строки.
        invokeBeforeSave(registerType, descriptor, rows);
        validateProposed(rows);
        writeAuthorizer.authorize(new RecorderSetWriteRequest(registerType, document, recorder, List.of(), rows));
        try (RecorderSetWriteContext.Scope ignored = RecorderSetWriteContext.openRows(registerType, recorder, rows)) {
            rows.forEach(entityManager::persist);
            entityManager.flush();
            invokeOnSave(registerType, descriptor, rows);
        }
        return rows;
    }

    <T extends BaseEntity & RecordedRegisterEntry> int clearForRecorder(
            EntityManager entityManager, Class<T> registerType, RecorderRef recorder, BaseEntity document) {
        requireOwner(entityManager, registerType, recorder, document);
        RegisterDescriptor descriptor = registers.require(registerType);
        lockRegister(entityManager, registerType);
        List<T> rows = loadCompleteSet(entityManager, registerType, recorder, false);
        validateOldRows(rows, recorder);
        writeAuthorizer.authorize(new RecorderSetWriteRequest(registerType, document, recorder, rows, List.of()));
        try (RecorderSetWriteContext.Scope ignored = RecorderSetWriteContext.openRows(registerType, recorder, rows)) {
            invokeBeforeDelete(registerType, descriptor, rows);
            rows.forEach(entityManager::remove);
            entityManager.flush();
        }
        return rows.size();
    }

    <T extends BaseEntity & RecordedRegisterEntry> void deleteForRecorder(
            EntityManager entityManager, Class<T> registerType, RecorderRef recorder,
            BaseEntity document, Long rowId) {
        requireOwner(entityManager, registerType, recorder, document);
        Objects.requireNonNull(rowId, "rowId");
        lockRegister(entityManager, registerType);
        // Read only this recorder's set: foreign ids never trigger an unrestricted entity find.
        T row = loadCompleteSet(entityManager, registerType, recorder, false).stream()
            .filter(value -> rowId.equals(value.getId())).findFirst()
            .orElseThrow(() -> new EntityNotFoundException("Запись регистра отсутствует или недоступна"));
        writeAuthorizer.authorize(new RecorderSetWriteRequest(registerType, document, recorder,
            List.of(row), List.of()));
        try (RecorderSetWriteContext.Scope ignored = RecorderSetWriteContext.openRows(registerType, recorder, List.of(row))) {
            invokeBeforeDelete(registerType, registers.require(registerType), List.of(row));
            entityManager.remove(row);
            entityManager.flush();
        }
    }

    private void requireOwner(EntityManager entityManager, Class<?> registerType,
                              RecorderRef recorder, BaseEntity document) {
        if (!entityManager.isJoinedToTransaction() || !entityManager.contains(document)
                || !recorder.getId().equals(document.getId())
                || !recorderTypes.resolveType(recorder).isInstance(document)) {
            throw new IllegalStateException("REGISTER_OWNER_WRITE_CONTEXT_REQUIRED");
        }
        RegisterDescriptor descriptor = registers.require(registerType);
        if (!descriptor.recorded() || !descriptor.recorderTypes().contains(recorderTypes.resolveType(recorder))) {
            throw new IllegalArgumentException("RECORDER_TYPE_NOT_ALLOWED: " + registerType.getName());
        }
        entityManager.lock(document, LockModeType.PESSIMISTIC_WRITE,
            Map.of(LOCK_TIMEOUT_HINT, lockTimeoutMillis));
    }

    /** Shared mutex; acquiring it does not allocate an order or change existing rows. */
    private RecorderOrderClock lockRegister(EntityManager entityManager, Class<?> registerType) {
        return RegisterWriteMutex.lock(entityManager, registerType, lockTimeoutMillis);
    }

    private <T extends BaseEntity & RecordedRegisterEntry> List<T> prepareRows(
            Class<T> registerType, RecorderRef recorder, List<? extends T> proposedRows) {
        return prepareRows(registerType, recorder, proposedRows, 0, false);
    }

    private <T extends BaseEntity & RecordedRegisterEntry> List<T> prepareRows(
            Class<T> registerType, RecorderRef recorder, List<? extends T> proposedRows,
            int lastLine, boolean assignRecorder) {
        List<T> rows = new ArrayList<>(proposedRows.size());
        Set<T> uniqueInstances = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int index = 0; index < proposedRows.size(); index++) {
            T row = Objects.requireNonNull(proposedRows.get(index), "proposedRows contains null");
            if (!registerType.isInstance(row) || row.getClass() != registerType) {
                throw new IllegalArgumentException("RECORDER_ROW_TYPE_MISMATCH: expected "
                    + registerType.getName() + ", found " + row.getClass().getName());
            }
            if (row.getId() != null) {
                throw new IllegalArgumentException("RECORDER_ROW_ALREADY_PERSISTED: replacement rows must be new instances");
            }
            if (!uniqueInstances.add(row)) {
                throw new IllegalArgumentException("RECORDER_ROW_REPEATED: each new row must be a distinct instance");
            }
            if (assignRecorder && row.getRecorder() == null) row.setRecorder(recorder);
            if (row.getRecorder() == null || !recorder.equals(row.getRecorder())) {
                throw new IllegalArgumentException("RECORDER_ROW_SCOPE_MISMATCH: row does not belong to " + recorder);
            }
            row.setLineNumber(Math.addExact(lastLine, Math.addExact(index, 1)));
            // Нормализация периода — правило платформы (S2): хук и валидация обязаны видеть то же
            // значение ключа, что уйдёт в базу. Порядок записи присваивает вызывающий под замком.
            RegisterPeriodListener.normalize(row);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    /**
     * Bean Validation предложенных строк после хуков (ADR-0025).
     *
     * <p>Порядок «нормализация → хук → валидация» — тот же, что у записи по ключу
     * ({@code RegisterKeyWrites}, {@code RegisterKeyChanges}), и он не случаен: хук, дописавший
     * значение, обязан быть проверен теми же правилами, а не пройти мимо них. Обратный порядок
     * отверг бы законную производную мутацию и заодно сделал бы два пути несогласованными.</p>
     */
    private <T extends BaseEntity & RecordedRegisterEntry> void validateProposed(List<T> rows) {
        for (T row : rows) {
            Set<ConstraintViolation<T>> violations = validator.validate(row);
            if (!violations.isEmpty()) {
                String details = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
                throw new ValidationException("RECORDER_SET_INVALID: " + details);
            }
        }
    }

    // === lifecycle строки (ADR-0025) ===

    /**
     * Хуки строки на пути набора владельца.
     *
     * <p>Контракт тот же, что у записи по ключу: {@code beforeSave} — veto-проверка до валидации,
     * {@code onSave} — реакция после {@code persist} и flush, {@code beforeDelete} — veto-проверка
     * до удаления строки. {@code beforeUpdate} здесь не вызывается осознанно и это проверяемое
     * свойство пути: строки набора не обновляются, а заменяются новыми экземплярами (ADR-0025),
     * поэтому «хук не вызван» отличимо от «хук забыли» — тест на отсутствие вызова есть.</p>
     *
     * <p>Контекст — контекст строки, а не владельца: имя операции называет путь
     * ({@code …-register-recorder-set:…} против {@code …-register-key:…}), поэтому правило может
     * различать источники, а получить право записи владельца хук не может — токен сверяется по
     * тождеству сущности документа.</p>
     */
    private <T extends BaseEntity & RecordedRegisterEntry> void invokeBeforeSave(
            Class<T> registerType, RegisterDescriptor descriptor, List<T> rows) {
        for (T row : rows) {
            lifecycles.beforeSave(registerType, row, rowContext(descriptor, SET_OPERATION, row.getId()));
        }
    }

    private <T extends BaseEntity & RecordedRegisterEntry> void invokeOnSave(
            Class<T> registerType, RegisterDescriptor descriptor, List<T> rows) {
        for (T row : rows) {
            lifecycles.onSave(registerType, row, rowContext(descriptor, SET_OPERATION, row.getId()));
        }
    }

    private <T extends BaseEntity & RecordedRegisterEntry> void invokeBeforeDelete(
            Class<T> registerType, RegisterDescriptor descriptor, List<T> rows) {
        for (T row : rows) {
            lifecycles.beforeDelete(registerType, row, rowContext(descriptor, DELETE_OPERATION, row.getId()));
        }
    }

    private static EventContext rowContext(RegisterDescriptor descriptor, String verb, Object rowId) {
        return EventContext.forEntity(descriptor.entityType(), rowId, EventSource.SYSTEM,
            verb + ":" + descriptor.entityName());
    }

    private <T extends BaseEntity & RecordedRegisterEntry> List<T> loadCompleteSet(
            EntityManager entityManager, Class<T> registerType, RecorderRef recorder,
            boolean cleanup) {
        java.util.function.Supplier<List<T>> query = () -> queryCompleteSet(entityManager, registerType, recorder);
        if (rlsFilterActivator == null) return query.get();
        String tableName = tableName(registerType);
        return cleanup
            ? rlsFilterActivator.withDependentRowCleanup(entityManager, tableName, query)
            : rlsFilterActivator.withRecorderSetReplacementRead(entityManager, tableName, query);
    }

    private <T extends BaseEntity & RecordedRegisterEntry> List<T> queryCompleteSet(
            EntityManager entityManager, Class<T> registerType, RecorderRef recorder) {
        var builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = builder.createQuery(registerType);
        Root<T> root = query.from(registerType);
        var ref = root.get("recorder");
        query.select(root).where(
            builder.equal(ref.get("typeCode"), recorder.getTypeCode()),
            builder.equal(ref.get("id"), recorder.getId())
        ).orderBy(builder.asc(root.get("lineNumber")));
        // Field-based validation and RLS snapshots must inspect the initialized implementation,
        // not a proxy shell whose persistent fields are still null.
        return entityManager.createQuery(query).getResultList().stream()
            .map(row -> registerType.cast(org.hibernate.Hibernate.unproxy(row))).toList();
    }

    private static String tableName(Class<?> registerType) {
        jakarta.persistence.Table table = registerType.getAnnotation(jakarta.persistence.Table.class);
        if (table == null || table.name().isBlank()) {
            throw new IllegalStateException("RECORDER_TABLE_NAME_REQUIRED: " + registerType.getName());
        }
        return table.name();
    }

    private <T extends BaseEntity & RecordedRegisterEntry> void validateOldRows(List<T> oldRows,
                                                                                 RecorderRef recorder) {
        for (T row : oldRows) {
            if (row.getRecorder() == null || !recorder.equals(row.getRecorder())) {
                throw new IllegalStateException("RECORDER_SET_CORRUPT: loaded row is outside the recorder scope");
            }
            Set<ConstraintViolation<T>> violations = validator.validate(row);
            if (!violations.isEmpty()) {
                String details = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
                throw new ValidationException("RECORDER_EXISTING_SET_INVALID: " + details);
            }
        }
    }

    record Result(int removedRows, int insertedRows, long recordingOrder, Long documentVersion) { }
}

package org.ipro.register;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import org.hibernate.Session;
import org.ipro.crud.AggregateSaveLifecycleScope;
import org.ipro.crud.BaseEntity;
import org.ipro.data.CanonicalSaveLifecycleScope;
import org.ipro.lifecycle.EntitySaveContext;
import org.ipro.rls.RlsFilterActivator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Application procedures for recorder-owned rows, available only in canonical onSave.
 * Each procedure joins the owner's transaction; a failed procedure marks that transaction
 * for rollback even when application code catches the exception.
 */
public final class RegisterRecords {
    private static final Map<String, Object> LOCK_HINTS = Map.of("jakarta.persistence.lock.timeout", 1_000);
    private final EntityManager entityManager;
    private final RecorderTypeRegistry recorderTypes;
    private final RecorderSetWriter writer;
    private final TransactionTemplate transactions;

    public RegisterRecords(EntityManager entityManager, RegisterRegistry registers,
            RecorderTypeRegistry recorderTypes, Validator validator, RlsFilterActivator filters,
            RecorderSetWriteAuthorizer authorizer, PlatformTransactionManager transactionManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.recorderTypes = Objects.requireNonNull(recorderTypes, "recorderTypes");
        writer = new RecorderSetWriter(registers, recorderTypes, validator, 1_000,
            Objects.requireNonNull(filters, "filters"), authorizer);
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    /** The context must be the current callback for a persisted, managed recorder document. */
    public ForRecorder forRecorder(EntitySaveContext<?> context) {
        Objects.requireNonNull(context, "context");
        Object token = currentToken(context);
        if (token == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !entityManager.isJoinedToTransaction()
                || !(context.entity() instanceof BaseEntity document)
                || document.getId() == null || !entityManager.contains(document)
                || !document.getId().equals(context.entityId())) {
            throw new IllegalStateException("REGISTER_OWNER_WRITE_CONTEXT_REQUIRED: use RegisterRecords in canonical onSave");
        }
        Class<?> rootType = org.hibernate.Hibernate.getClass(document);
        if (context.eventContext().aggregateType() != rootType) {
            throw new IllegalStateException("REGISTER_OWNER_CONTEXT_TYPE_MISMATCH");
        }
        RecorderRef recorder = recorderTypes.referenceFor(rootType, document.getId());
        return new ForRecorder(context, token, document, recorder, entityManager.unwrap(Session.class));
    }

    private static Object currentToken(EntitySaveContext<?> context) {
        Object aggregate = AggregateSaveLifecycleScope.tokenFor(context);
        return aggregate == null ? CanonicalSaveLifecycleScope.tokenFor(context) : aggregate;
    }

    /** A callback-bound handle; it cannot be constructed or reused after onSave or in another transaction. */
    public final class ForRecorder {
        private final EntitySaveContext<?> context;
        private final Object token;
        private final BaseEntity document;
        private final RecorderRef recorder;
        private final Session session;

        private ForRecorder(EntitySaveContext<?> context, Object token, BaseEntity document,
                            RecorderRef recorder, Session session) {
            this.context = context;
            this.token = token;
            this.document = document;
            this.recorder = recorder;
            this.session = session;
        }

        public <T extends BaseEntity & RecordedRegisterEntry> int clear(Class<T> registerType) {
            return execute(() -> {
                advanceOwnerVersionIfNeeded();
                return writer.clearForRecorder(entityManager, registerType, recorder, document);
            });
        }

        public <T extends BaseEntity & RecordedRegisterEntry> List<T> add(Class<T> registerType, List<? extends T> rows) {
            return execute(() -> {
                List<? extends T> proposed = List.copyOf(Objects.requireNonNull(rows, "rows"));
                if (!proposed.isEmpty()) advanceOwnerVersionIfNeeded();
                return writer.addForRecorder(entityManager, registerType, recorder, document, proposed);
            });
        }

        public <T extends BaseEntity & RecordedRegisterEntry> void delete(Class<T> registerType, Long rowId) {
            execute(() -> {
                advanceOwnerVersionIfNeeded();
                writer.deleteForRecorder(entityManager, registerType, recorder, document, rowId);
                return null;
            });
        }

        private void advanceOwnerVersionIfNeeded() {
            entityManager.flush();
            entityManager.lock(document, LockModeType.PESSIMISTIC_WRITE, LOCK_HINTS);
            if (AggregateSaveLifecycleScope.requiresVersionAdvance(context, document.getVersion())
                    || CanonicalSaveLifecycleScope.requiresVersionAdvance(context, document.getVersion())) {
                entityManager.lock(document, LockModeType.PESSIMISTIC_FORCE_INCREMENT, LOCK_HINTS);
            }
        }

        private <R> R execute(Supplier<R> work) {
            return transactions.execute(status -> {
                if (currentToken(context) != token || !entityManager.isJoinedToTransaction()
                        || entityManager.unwrap(Session.class) != session || !entityManager.contains(document)) {
                    throw new IllegalStateException("REGISTER_OWNER_WRITE_CONTEXT_EXPIRED");
                }
                return work.get();
            });
        }
    }
}

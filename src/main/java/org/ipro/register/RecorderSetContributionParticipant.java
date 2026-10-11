package org.ipro.register;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import org.ipro.crud.AggregateSaveContribution;
import org.ipro.crud.AggregateSaveContributionParticipant;
import org.ipro.crud.AggregateSaveParticipantContext;
import org.ipro.crud.BaseEntity;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.rls.RlsFilterActivator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bridges aggregate save contributions to the internal recorder-set writer. */
public final class RecorderSetContributionParticipant implements AggregateSaveContributionParticipant {
    private static final int LOCK_TIMEOUT_MILLIS = 1_000;

    private final EntityManager entityManager;
    private final RegisterRegistry registers;
    private final RecorderTypeRegistry recorderTypes;
    private final RecorderSetWriter writer;

    public RecorderSetContributionParticipant(EntityManager entityManager,
                                              RegisterRegistry registers,
                                              RecorderTypeRegistry recorderTypes,
                                              Validator validator,
                                              RlsFilterActivator rlsFilterActivator,
                                              RecorderSetWriteAuthorizer writeAuthorizer) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.registers = Objects.requireNonNull(registers, "registers");
        this.recorderTypes = Objects.requireNonNull(recorderTypes, "recorderTypes");
        this.writer = new RecorderSetWriter(registers, recorderTypes,
            Objects.requireNonNull(validator, "validator"), LOCK_TIMEOUT_MILLIS,
            Objects.requireNonNull(rlsFilterActivator, "rlsFilterActivator"),
            Objects.requireNonNull(writeAuthorizer, "writeAuthorizer"));
    }

    @Override
    public boolean supports(Class<?> rootType, AggregateSaveContribution contribution) {
        if (!(contribution instanceof RecorderSetContribution set)) return false;
        return registers.find(set.registerType()).filter(RegisterDescriptor::recorded)
            .filter(descriptor -> descriptor.recorderTypes().contains(rootType)).isPresent();
    }

    @Override
    public void apply(IdentifiableEntity<Long> savedRoot, AggregateSaveContribution contribution,
                      AggregateSaveParticipantContext context) {
        Objects.requireNonNull(savedRoot, "savedRoot");
        Objects.requireNonNull(context, "context");
        if (!(contribution instanceof RecorderSetContribution set)) {
            throw new IllegalArgumentException("RECORDER_CONTRIBUTION_TYPE_UNSUPPORTED");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !entityManager.isJoinedToTransaction()) {
            throw new IllegalStateException("Recorder replacement must join the aggregate save transaction");
        }
        if (!(savedRoot instanceof BaseEntity document) || document.getId() == null) {
            throw new IllegalArgumentException("RECORDER_SAVE_ROOT_INVALID: " + savedRoot.getClass().getName());
        }
        Class<?> rootType = savedRoot instanceof org.hibernate.proxy.HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass() : savedRoot.getClass();
        RegisterDescriptor descriptor = registers.require(set.registerType());
        if (!descriptor.recorded() || !descriptor.recorderTypes().contains(rootType)) {
            throw new IllegalArgumentException("RECORDER_TYPE_NOT_ALLOWED: " + rootType.getName()
                + " for " + set.registerType().getName());
        }

        entityManager.flush();
        Long currentVersion = document.getVersion() == null ? 0L : document.getVersion();
        if (!context.creating() && !context.versionAdvanceRequested()
                && context.originalVersion().equals(currentVersion)) {
            entityManager.lock(document, LockModeType.PESSIMISTIC_FORCE_INCREMENT,
                Map.of("jakarta.persistence.lock.timeout", LOCK_TIMEOUT_MILLIS));
            entityManager.flush();
            currentVersion = document.getVersion() == null ? 0L : document.getVersion();
        }

        RecorderRef recorder = recorderTypes.referenceFor(rootType, document.getId());
        replace(set, recorder, currentVersion);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void replace(RecorderSetContribution set, RecorderRef recorder, Long expectedVersion) {
        writer.replaceForRecorder(entityManager, (Class) set.registerType(), recorder,
            expectedVersion, (List) set.rows(), false);
    }
}

package org.ipro.register;

import jakarta.persistence.EntityManager;
import org.hibernate.proxy.HibernateProxy;
import org.ipro.crud.BaseEntity;
import org.ipro.data.CanonicalDeleteParticipant;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.rls.RlsFilterActivator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/** Registry-driven, closed cleanup of recorder-owned rows during canonical document deletion. */
public final class RecorderSetDeleteParticipant implements CanonicalDeleteParticipant {
    private static final int LOCK_TIMEOUT_MILLIS = 1_000;

    private final EntityManager entityManager;
    private final RegisterRegistry registers;
    private final RecorderTypeRegistry recorderTypes;
    private final RecorderSetWriter writer;
    private final RlsFilterActivator rlsFilterActivator;

    public RecorderSetDeleteParticipant(EntityManager entityManager,
                                        RegisterRegistry registers,
                                        RecorderTypeRegistry recorderTypes,
                                        jakarta.validation.Validator validator,
                                        RlsFilterActivator rlsFilterActivator) {
        this(entityManager, registers, recorderTypes, validator, rlsFilterActivator, request -> { });
    }

    public RecorderSetDeleteParticipant(EntityManager entityManager, RegisterRegistry registers,
            RecorderTypeRegistry recorderTypes, jakarta.validation.Validator validator,
            RlsFilterActivator rlsFilterActivator, RecorderSetWriteAuthorizer writeAuthorizer) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.registers = Objects.requireNonNull(registers, "registers");
        this.recorderTypes = Objects.requireNonNull(recorderTypes, "recorderTypes");
        this.writer = new RecorderSetWriter(registers, recorderTypes,
            Objects.requireNonNull(validator, "validator"), LOCK_TIMEOUT_MILLIS,
            Objects.requireNonNull(rlsFilterActivator, "rlsFilterActivator"),
            Objects.requireNonNull(writeAuthorizer, "writeAuthorizer"));
        this.rlsFilterActivator = rlsFilterActivator;
    }

    @Override
    public boolean supports(Class<?> rootType) {
        Objects.requireNonNull(rootType, "rootType");
        return registers.descriptors().stream().anyMatch(descriptor -> descriptor.recorded()
            && descriptor.recorderTypes().contains(rootType));
    }

    @Override
    public void beforeDelete(IdentifiableEntity<Long> lockedRoot) {
        Objects.requireNonNull(lockedRoot, "lockedRoot");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !entityManager.isJoinedToTransaction()) {
            throw new IllegalStateException("Recorder cleanup must join canonical root deletion transaction");
        }
        Class<?> rootType = lockedRoot instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass() : lockedRoot.getClass();
        if (!(lockedRoot instanceof BaseEntity document) || document.getId() == null
                || !supports(rootType)) {
            throw new IllegalArgumentException("RECORDER_DELETE_ROOT_INVALID: " + rootType.getName());
        }
        RecorderRef recorder = recorderTypes.referenceFor(rootType, document.getId());
        for (RegisterDescriptor descriptor : registers.descriptors()) {
            if (descriptor.recorded() && descriptor.recorderTypes().contains(rootType)) {
                writer.deleteForRecorder(entityManager, descriptor.entityType(), recorder);
            }
        }
    }
}

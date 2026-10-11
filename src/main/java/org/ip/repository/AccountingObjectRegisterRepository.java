package org.ip.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.ip.model.AccountingType;
import org.ip.model.Nomenclature;
import org.ip.model.Workshop;
import org.ipro.register.RecorderRef;
import org.ipro.rls.RlsFilterActivator;
import org.springframework.stereotype.Repository;

import java.util.Map;

/** Persistence operations used after the accounting command's C5/reference preflight. */
@Repository
public class AccountingObjectRegisterRepository {
    private final EntityManager entityManager;
    private final RlsFilterActivator filters;

    public AccountingObjectRegisterRepository(EntityManager entityManager, RlsFilterActivator filters) {
        this.entityManager = entityManager;
        this.filters = filters;
    }

    public void lockWorkshop(Long id) {
        filters.ensureRlsEnabled(entityManager);
        entityManager.find(Workshop.class, id, LockModeType.PESSIMISTIC_WRITE,
            Map.of("jakarta.persistence.lock.timeout", 3_000));
    }

    public Nomenclature nomenclatureReference(Long id) {
        return entityManager.getReference(Nomenclature.class, id);
    }

    public AccountingType accountingTypeReference(Long id) {
        return entityManager.getReference(AccountingType.class, id);
    }

    public boolean hasOtherInstallation(Long workshopId, Long nomenclatureId, RecorderRef own) {
        filters.ensureRlsEnabled(entityManager);
        // This register has whole-type C5 access and no filterable row dimensions.
        // Re-saving an existing installation retains its own history exception.
        Long ownCount = entityManager.createQuery("""
            select count(r) from NomenclatureAccountingObject r
            where r.workshop.id = :workshop and r.nomenclature.id = :nomenclature
              and r.recorder.typeCode = :type and r.recorder.id = :recorder
            """, Long.class)
            .setParameter("workshop", workshopId).setParameter("nomenclature", nomenclatureId)
            .setParameter("type", own.getTypeCode()).setParameter("recorder", own.getId()).getSingleResult();
        if (ownCount != 0) return false;
        return entityManager.createQuery("""
            select count(r) from NomenclatureAccountingObject r
            where r.workshop.id = :workshop and r.nomenclature.id = :nomenclature
              and not (r.recorder.typeCode = :type and r.recorder.id = :recorder)
            """, Long.class)
            .setParameter("workshop", workshopId).setParameter("nomenclature", nomenclatureId)
            .setParameter("type", own.getTypeCode()).setParameter("recorder", own.getId())
            .getSingleResult() != 0;
    }
}

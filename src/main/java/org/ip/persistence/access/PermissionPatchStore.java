package org.ip.persistence.access;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.RlsFilterActivator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JPA access for one subject's physical grant rows; called only inside a patch transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class PermissionPatchStore {

    private final EntityManager em;
    private final RlsFilterActivator filters;

    public PermissionPatchStore(EntityManager em, RlsFilterActivator filters) {
        this.em = em;
        this.filters = filters;
    }

    public List<AccessGrant> rows(AccessGrant.SubjectType type, String subjectKey,
                                  String dimension, Long valueId) {
        var result = em.createQuery("select g from AccessGrant g where g.subjectType=:type and g.subjectKey=:key "
                + "and g.dimension=:dimension and ((:value is null and g.dimensionValueId is null) or g.dimensionValueId=:value)",
                AccessGrant.class)
            .setParameter("type", type).setParameter("key", subjectKey)
            .setParameter("dimension", dimension).setParameter("value", valueId).getResultList();
        // Clear stale first-level-cache state after waiting for another transaction's subject lock.
        result.forEach(em::refresh);
        return result.stream().filter(grant -> Objects.equals(grant.getDimensionValueId(), valueId)).toList();
    }

    public boolean scopeValueExists(String scopeId, Class<?> valueType, long valueId) {
        return filters.withDimensionAdministration(em, scopeId, () -> {
            var cb = em.getCriteriaBuilder();
            var query = cb.createQuery(Long.class);
            var root = query.from(valueType);
            query.select(cb.count(root)).where(cb.equal(root.get("id"), valueId));
            return em.createQuery(query).getSingleResult() == 1;
        });
    }
}

package org.ip.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.ip.metadata.ReferenceIndex;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ReferenceCheckService {

    private final ReferenceIndex referenceIndex;

    @PersistenceContext
    private EntityManager entityManager;

    public ReferenceCheckService(ReferenceIndex referenceIndex) {
        this.referenceIndex = referenceIndex;
    }

    public void checkNoReferences(Class<?> targetClass, Object id) {
        List<ReferenceIndex.ReverseReference> refs = referenceIndex.getReverseReferences(targetClass);
        if (refs.isEmpty()) {
            return;
        }

        List<String> blockers = new ArrayList<>();
        for (ReferenceIndex.ReverseReference ref : refs) {
            long count = countReferencing(ref, id);
            if (count > 0) {
                blockers.add(ref.describe(count));
            }
        }

        if (!blockers.isEmpty()) {
            throw new ValidationException(
                "Невозможно удалить: на запись есть ссылки —\n" + String.join("\n", blockers));
        }
    }

    private long countReferencing(ReferenceIndex.ReverseReference ref, Object id) {
        String jpql = "select count(r) from " + ref.referencingClass().getSimpleName() +
            " r where r." + ref.fieldName() + ".id = :id";
        return entityManager.createQuery(jpql, Long.class)
            .setParameter("id", id)
            .getSingleResult();
    }
}

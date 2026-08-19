package org.ip.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.rls.RlsFilterActivator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ReferenceCheckService {

    private final ReferenceIndex referenceIndex;
    private final RlsFilterActivator rlsFilterActivator;

    @PersistenceContext
    private EntityManager entityManager;

    public ReferenceCheckService(ReferenceIndex referenceIndex, RlsFilterActivator rlsFilterActivator) {
        this.referenceIndex = referenceIndex;
        this.rlsFilterActivator = rlsFilterActivator;
    }

    /**
     * Умышленно без RLS текущего пользователя (см. RlsFilterActivator.withRlsDisabled) —
     * это проверка ссылочной целостности БД, а не выборка данных для показа пользователю:
     * она должна видеть ВСЕ ссылающиеся записи, включая те, что под недоступными
     * пользователю измерениями (например, PrdSpec под чужим Journal), иначе можно
     * удалить запись, оставив на неё невидимые пользователю "битые" ссылки.
     */
    public void checkNoReferences(Class<?> targetClass, Object id) {
        List<ReferenceIndex.ReverseReference> refs = referenceIndex.getReverseReferences(targetClass);
        if (refs.isEmpty()) {
            return;
        }

        List<String> blockers = rlsFilterActivator.withRlsDisabled(entityManager, () -> {
            List<String> found = new ArrayList<>();
            for (ReferenceIndex.ReverseReference ref : refs) {
                long count = countReferencing(ref, id);
                if (count > 0) {
                    found.add(ref.describe(count));
                }
            }
            return found;
        });

        if (!blockers.isEmpty()) {
            throw new ValidationException(
                "Невозможно удалить: на запись есть ссылки —\n" + String.join("\n", blockers));
        }
    }

    private long countReferencing(ReferenceIndex.ReverseReference ref, Object id) {
        // columnRef: ссылка колонкой-идентификатором (SettingValue.entityRefId), не ассоциацией
        String fieldPath = ref.columnRef() ? ref.fieldName() : ref.fieldName() + ".id";
        String jpql = "select count(r) from " + ref.referencingClass().getSimpleName() +
            " r where r." + fieldPath + " = :id";
        return entityManager.createQuery(jpql, Long.class)
            .setParameter("id", id)
            .getSingleResult();
    }
}


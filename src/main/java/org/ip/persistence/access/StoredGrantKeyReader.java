package org.ip.persistence.access;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Component;

/** Read-only projection of stored grant keys; keeps JPA out of the application catalog service. */
@Component
public class StoredGrantKeyReader {

    private final EntityManager entityManager;

    public StoredGrantKeyReader(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** One distinct-key projection; full grants, values and subjects are not loaded here. */
    public List<String> distinctKeys() {
        return entityManager.createQuery("select distinct g.dimension from AccessGrant g", String.class)
            .getResultList();
    }
}

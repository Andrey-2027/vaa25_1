package org.ip.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditService {

    @PersistenceContext
    private EntityManager entityManager;

    public <T> List<Number> getRevisions(Class<T> entityClass, Object entityId) {
        AuditReader auditReader = AuditReaderFactory.get(entityManager);
        return auditReader.getRevisions(entityClass, entityId);
    }

    public <T> T findRevision(Class<T> entityClass, Object entityId, Number revision) {
        AuditReader auditReader = AuditReaderFactory.get(entityManager);
        return auditReader.find(entityClass, entityId, revision);
    }

    public <T> List<T> findAllRevisions(Class<T> entityClass, Object entityId) {
        AuditReader auditReader = AuditReaderFactory.get(entityManager);
        List<Number> revisions = getRevisions(entityClass, entityId);

        return revisions.stream()
                .map(rev -> auditReader.find(entityClass, entityId, rev))
                .toList();
    }

    public <T> AuditHistoryEntry<T> getRevisionWithMetadata(Class<T> entityClass, Object entityId, Number revision) {
        AuditReader auditReader = AuditReaderFactory.get(entityManager);
        T entity = auditReader.find(entityClass, entityId, revision);
        var revisionDate = auditReader.getRevisionDate(revision);

        return new AuditHistoryEntry<>(revision, revisionDate, entity);
    }

    public <T> List<AuditHistoryEntry<T>> getFullHistory(Class<T> entityClass, Object entityId) {
        AuditReader auditReader = AuditReaderFactory.get(entityManager);
        List<Number> revisions = getRevisions(entityClass, entityId);

        return revisions.stream()
                .map(rev -> {
                    T entity = auditReader.find(entityClass, entityId, rev);
                    var revisionDate = auditReader.getRevisionDate(rev);
                    return new AuditHistoryEntry<>(rev, revisionDate, entity);
                })
                .toList();
    }

    public static class AuditHistoryEntry<T> {
        private final Number revision;
        private final java.util.Date revisionDate;
        private final T entity;

        public AuditHistoryEntry(Number revision, java.util.Date revisionDate, T entity) {
            this.revision = revision;
            this.revisionDate = revisionDate;
            this.entity = entity;
        }

        public Number getRevision() {
            return revision;
        }

        public java.util.Date getRevisionDate() {
            return revisionDate;
        }

        public T getEntity() {
            return entity;
        }
    }
}

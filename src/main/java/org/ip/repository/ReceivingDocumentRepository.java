package org.ip.repository;

import org.ip.model.ReceivingDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface ReceivingDocumentRepository extends JpaRepository<ReceivingDocument, Long>, JpaSpecificationExecutor<ReceivingDocument> {
}

package org.ip.repository;

import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReceivingDocumentItemRepository extends JpaRepository<ReceivingDocumentItem, Long> {

    List<ReceivingDocumentItem> findByDocumentOrderByLineNumber(ReceivingDocument document);
}

package org.ip.repository;

import org.ip.model.ReceivingDocumentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReceivingDocumentItemRepository extends JpaRepository<ReceivingDocumentItem, Long> {
}

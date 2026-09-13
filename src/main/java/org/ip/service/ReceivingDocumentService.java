package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.ReceivingDocument;
import org.ip.repository.ReceivingDocumentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.ipro.crud.AbstractBaseService;

/**
 * C4.4: in-memory {@code findAll()} search по номеру удалён (ADR-0007 §7, план п.8) —
 * поиск идёт через canonical engine по metadata-колонкам. Принятое расхождение: раньше
 * искали только по {@code number}, теперь — по составу колонок формы списка.
 */
@Service
public class ReceivingDocumentService extends AbstractBaseService<ReceivingDocument, Long> {

    public ReceivingDocumentService(ReceivingDocumentRepository repository,
                                    Validator validator) {
        super(repository, validator);
    }

    @Override
    public Page<ReceivingDocument> findAll(Specification<ReceivingDocument> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }
}

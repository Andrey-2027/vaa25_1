package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.ReceivingDocument;
import org.ip.repository.ReceivingDocumentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReceivingDocumentService extends AbstractBaseService<ReceivingDocument, Long> {

    private final ReceivingDocumentRepository documentRepository;

    public ReceivingDocumentService(ReceivingDocumentRepository repository, Validator validator) {
        super(repository, validator);
        this.documentRepository = repository;
    }

    @Override
    public List<ReceivingDocument> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        return documentRepository.findAll().stream()
                .filter(d -> d.getNumber().toLowerCase().contains(term.toLowerCase()))
                .toList();
    }

    @Override
    public ReceivingDocument save(ReceivingDocument entity) {
        validate(entity);
        validateDocument(entity);
        return repository.save(entity);
    }

    @Override
    public ReceivingDocument create(ReceivingDocument entity) {
        validate(entity);
        validateDocument(entity);
        return repository.save(entity);
    }

    @Override
    public ReceivingDocument update(ReceivingDocument entity) {
        validate(entity);
        validateDocument(entity);
        return repository.save(entity);
    }

    @Override
    public Page<ReceivingDocument> findAll(Specification<ReceivingDocument> spec, Pageable pageable) {
        return documentRepository.findAll(spec, pageable);
    }

    private void validateDocument(ReceivingDocument document) {
        if (document.getReceivingWorkshop() != null &&
            document.getTransferringWorkshop() != null &&
            document.getReceivingWorkshop().getId().equals(document.getTransferringWorkshop().getId())) {
            throw new ValidationException("Цех-приемщик и цех-сдатчик не могут быть одинаковыми");
        }

        if (document.getItems().isEmpty()) {
            throw new ValidationException("Документ должен содержать хотя бы одну позицию");
        }
    }
}
package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.ReceivingDocument;
import org.ip.repository.ReceivingDocumentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;
import org.ipro.crud.AbstractBaseService;

@Service
public class ReceivingDocumentService extends AbstractBaseService<ReceivingDocument, Long> {

    private final ReceivingDocumentRepository documentRepository;
    public ReceivingDocumentService(ReceivingDocumentRepository repository,
                                    Validator validator) {
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
    public Page<ReceivingDocument> findAll(Specification<ReceivingDocument> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }

}

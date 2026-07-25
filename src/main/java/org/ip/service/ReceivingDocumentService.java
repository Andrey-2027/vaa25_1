package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.ReceivingDocument;
import org.ip.repository.ReceivingDocumentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ReceivingDocumentService extends AbstractBaseService<ReceivingDocument, Long> {

    private final ReceivingDocumentRepository documentRepository;
    private final ReceivingDocumentItemService itemService;

    public ReceivingDocumentService(ReceivingDocumentRepository repository,
                                    Validator validator,
                                    ReceivingDocumentItemService itemService) {
        super(repository, validator);
        this.documentRepository = repository;
        this.itemService = itemService;
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

    /**
     * Каскадное удаление строк табличной части перед удалением шапки.
     *
     * Коллекция items больше не хранится на ReceivingDocument как @OneToMany
     * (см. миграцию на @TableSections), поэтому orphanRemoval JPA больше не сработает
     * автоматически — каскад теперь явный, на уровне сервиса родителя, как и планировали:
     * сначала обнуляем строки через TableSectionService.replaceAll(doc, List.of()),
     * потом удаляем саму шапку.
     */
    @Override
    public void delete(Long id) {
        Optional<ReceivingDocument> existing = findById(id);
        existing.ifPresent(doc -> itemService.replaceAll(doc, List.of()));
        super.delete(id);
    }

    @Override
    public Page<ReceivingDocument> findAll(Specification<ReceivingDocument> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }

    /**
     * Проверка "документ должен содержать хотя бы одну позицию" теперь на уровне
     * @TableSectionMetadata(minRows = 1) и выполняется ItemForm.validateTableSections()
     * ДО вызова save() — сюда, в validateDocument(), она больше не входит.
     */
    private void validateDocument(ReceivingDocument document) {
        if (document.getReceivingWorkshop() != null &&
            document.getTransferringWorkshop() != null &&
            document.getReceivingWorkshop().getId().equals(document.getTransferringWorkshop().getId())) {
            throw new ValidationException("Цех-приемщик и цех-сдатчик не могут быть одинаковыми");
        }
    }
}

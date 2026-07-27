package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.ip.repository.PrdSpecRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Service
public class PrdSpecService extends AbstractBaseService<PrdSpec, Long> {

    private final PrdSpecRepository prdSpecRepository;

    public PrdSpecService(PrdSpecRepository repository, Validator validator) {
        super(repository, validator);
        this.prdSpecRepository = repository;
    }

    @Override
    public List<PrdSpec> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        return prdSpecRepository.searchByTerm(term, PageRequest.of(0, 100));
    }

    /**
     * Поиск спецификаций по журналу с поддержкой фильтров (Specification) и пагинации.
     * Используется в кастомном View с ComboBox для выбора журнала.
     */
    public Page<PrdSpec> findByJournal(Journal journal, Specification<PrdSpec> spec, Pageable pageable) {
        // Комбинируем условие journal с переданной спецификацией (фильтры из FilterGrid)
        Specification<PrdSpec> journalSpec = (root, query, cb) ->
            cb.equal(root.get("journal"), journal);

        Specification<PrdSpec> combined = spec != null
            ? journalSpec.and(spec)
            : journalSpec;

        return prdSpecRepository.findAll(combined, pageable);
    }

    /**
     * Переопределяем findAll для поддержки fetch paths (колонки через точку в гриде).
     */
    @Override
    public Page<PrdSpec> findAll(Specification<PrdSpec> spec, Pageable pageable, Collection<String> fetchPaths) {
        return super.findAll(spec, pageable, fetchPaths);
    }
}

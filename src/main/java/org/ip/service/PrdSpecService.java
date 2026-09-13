package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.ip.repository.PrdSpecRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Collection;
import org.ipro.crud.AbstractBaseService;
import org.ipro.data.SearchRead;

@Service
public class PrdSpecService extends AbstractBaseService<PrdSpec, Long> {

    public PrdSpecService(PrdSpecRepository repository, Validator validator) {
        super(repository, validator);
    }

    @Override
    public java.util.List<PrdSpec> search(String term) {
        return search(term, SearchRead.defaultPage()).getContent();
    }

    @Override
    public Page<PrdSpec> search(String term, Pageable pageable) {
        return searchWithFields(term, pageable, "codeSpec", "draft");
    }

    /**
     * Поиск спецификаций по журналу с поддержкой фильтров (Specification) и пагинации.
     * Используется в кастомном View с ComboBox для выбора журнала.
     */
    public Page<PrdSpec> findByJournal(Journal journal, Specification<PrdSpec> spec,
                                       Pageable pageable, Collection<String> fetchPaths) {
        Specification<PrdSpec> journalSpec = (root, query, cb) ->
            cb.equal(root.get("journal"), journal);

        Specification<PrdSpec> combined = spec != null
            ? journalSpec.and(spec)
            : journalSpec;

        return this.findAll(combined, pageable, fetchPaths);
    }

    /**
     * Переопределяем findAll для поддержки fetch paths (колонки через точку в гриде).
     */
    @Override
    public Page<PrdSpec> findAll(Specification<PrdSpec> spec, Pageable pageable, Collection<String> fetchPaths) {
        return super.findAll(spec, pageable, fetchPaths);
    }
}

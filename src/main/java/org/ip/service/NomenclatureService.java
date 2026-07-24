package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Nomenclature;
import org.ip.repository.NomenclatureRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NomenclatureService extends AbstractBaseService<Nomenclature, Long> {

    private final NomenclatureRepository nomenclatureRepository;

    public NomenclatureService(NomenclatureRepository repository, Validator validator) {
        super(repository, validator);
        this.nomenclatureRepository = repository;
    }

    @Override
    public List<Nomenclature> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        return nomenclatureRepository.searchByTerm(term, PageRequest.of(0, 100));
    }

    @Override
    public Page<Nomenclature> search(String term, Pageable pageable) {
        if (term == null || term.isEmpty()) {
            return findAll(pageable);
        }
        return nomenclatureRepository.findWithFilter(term, pageable);
    }

    @Override
    public Page<Nomenclature> findAll(Specification<Nomenclature> spec, Pageable pageable) {
        return nomenclatureRepository.findAll(spec, pageable);
    }
}

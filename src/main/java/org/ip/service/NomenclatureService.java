package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Nomenclature;
import org.ip.repository.NomenclatureRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.ipro.crud.AbstractBaseService;

/**
 * C4.4 pilot: list/search по номенклатуре идут через canonical engine (ADR-0007 §7),
 * без repository {@code searchByTerm}/{@code findWithFilter}. Свой Paged-search остаётся
 * только как {@code findAll(Specification, Pageable)} — то есть как обычный list-запрос.
 */
@Service
public class NomenclatureService extends AbstractBaseService<Nomenclature, Long> {

    public NomenclatureService(NomenclatureRepository repository, Validator validator) {
        super(repository, validator);
    }

    @Override
    public Page<Nomenclature> findAll(Specification<Nomenclature> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }
}

package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.UnitOfMeasurementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;
import org.ipro.crud.AbstractBaseService;
import org.ipro.data.SearchRead;

@Service
public class UnitOfMeasurementService extends AbstractBaseService<UnitOfMeasurement, Long> {

    private final UnitOfMeasurementRepository unitRepository;

    public UnitOfMeasurementService(UnitOfMeasurementRepository repository, Validator validator) {
        super(repository, validator);
        this.unitRepository = repository;
    }

    @Override
    public List<UnitOfMeasurement> search(String term) {
        return search(term, SearchRead.defaultPage()).getContent();
    }

    @Override
    public Page<UnitOfMeasurement> search(String term, Pageable pageable) {
        return searchWithFields(term, pageable, "shortCode");
    }

    @Override
    public Page<UnitOfMeasurement> findAll(Specification<UnitOfMeasurement> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }
}

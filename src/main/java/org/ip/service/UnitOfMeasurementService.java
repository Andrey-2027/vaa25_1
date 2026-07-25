package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.UnitOfMeasurementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UnitOfMeasurementService extends AbstractBaseService<UnitOfMeasurement, Long> {

    private final UnitOfMeasurementRepository unitRepository;

    public UnitOfMeasurementService(UnitOfMeasurementRepository repository, Validator validator) {
        super(repository, validator);
        this.unitRepository = repository;
    }

    @Override
    public List<UnitOfMeasurement> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        return unitRepository.searchByTerm(term, PageRequest.of(0, 100));
    }

    @Override
    public Page<UnitOfMeasurement> findAll(Specification<UnitOfMeasurement> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }
}

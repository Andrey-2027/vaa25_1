package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Workshop;
import org.ip.repository.WorkshopRepository;
import org.ip.spec.WorkshopSpecs;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WorkshopService extends AbstractBaseService<Workshop, Long> {

    private final WorkshopRepository workshopRepository;

    public WorkshopService(WorkshopRepository repository, Validator validator) {
        super(repository, validator);
        this.workshopRepository = repository;
    }

    @Override
    public List<Workshop> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        return workshopRepository.searchByTerm(term, org.springframework.data.domain.PageRequest.of(0, 100));
    }

    @Override
    public Page<Workshop> findAll(Specification<Workshop> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }
}
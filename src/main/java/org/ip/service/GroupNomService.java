package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.GroupNom;
import org.ip.repository.GroupNomRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import org.ipro.crud.AbstractBaseService;

@Service
public class GroupNomService extends AbstractBaseService<GroupNom, Long> {

    private final GroupNomRepository groupNomRepository;

    public GroupNomService(GroupNomRepository repository, Validator validator) {
        super(repository, validator);
        this.groupNomRepository = repository;
    }

    @Override
    public Page<GroupNom> findAll(Specification<GroupNom> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }
}

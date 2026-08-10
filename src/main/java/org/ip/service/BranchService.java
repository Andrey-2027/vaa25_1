package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Branch;
import org.ip.repository.BranchRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BranchService extends AbstractBaseService<Branch, Long> {

    private final BranchRepository branchRepository;

    public BranchService(BranchRepository repository, Validator validator) {
        super(repository, validator);
        this.branchRepository = repository;
    }

    @Override
    public List<Branch> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        return branchRepository.searchByTerm(term, PageRequest.of(0, 100));
    }
}
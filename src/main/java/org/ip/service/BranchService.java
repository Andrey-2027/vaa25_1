package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Branch;
import org.ip.repository.BranchRepository;
import org.springframework.stereotype.Service;
import org.ipro.crud.AbstractBaseService;

/**
 * C4.4 pilot: search больше не реализуется через repository {@code searchByTerm} и
 * in-memory фильтр, а делегируется canonical search engine (ADR-0007 §7). Здесь не
 * остаётся ни query, ни special-семантики — только typed CRUD-хвост.
 */
@Service
public class BranchService extends AbstractBaseService<Branch, Long> {

    public BranchService(BranchRepository repository, Validator validator) {
        super(repository, validator);
    }
}

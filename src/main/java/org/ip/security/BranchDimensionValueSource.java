package org.ip.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.ipro.rls.RlsDimensionValueSource;
import org.ipro.rls.RlsFilterActivator;
import org.ip.model.Branch;
import org.ip.repository.BranchRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BranchDimensionValueSource implements RlsDimensionValueSource<Branch> {

    private final BranchRepository branchRepository;
    private final RlsFilterActivator rlsFilterActivator;

    @PersistenceContext
    private EntityManager entityManager;

    public BranchDimensionValueSource(BranchRepository branchRepository, RlsFilterActivator rlsFilterActivator) {
        this.branchRepository = branchRepository;
        this.rlsFilterActivator = rlsFilterActivator;
    }

    @Override
    public String dimension() {
        return "BRANCH";
    }

    @Override
    public List<Branch> allIgnoringRls() {
        return rlsFilterActivator.withRlsDisabled(entityManager, () -> branchRepository.findAll(Sort.by("code")));
    }

    @Override
    public Long idOf(Branch value) {
        return value.getId();
    }

    @Override
    public String displayCode(Branch value) {
        return value.getCode();
    }

    @Override
    public String displayName(Branch value) {
        return value.getName();
    }
}
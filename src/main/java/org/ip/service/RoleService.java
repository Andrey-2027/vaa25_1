package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Role;
import org.ip.repository.RoleRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RoleService extends AbstractBaseService<Role, Long> {

    private final RoleRepository repository;

    public RoleService(RoleRepository repository, Validator validator) {
        super(repository, validator);
        this.repository = repository;
    }

    @Override
    public List<Role> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        String lower = term.toLowerCase();
        return findAll().stream()
            .filter(r -> r.getName().toLowerCase().contains(lower))
            .toList();
    }
}

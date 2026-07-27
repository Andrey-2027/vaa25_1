package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Oper;
import org.ip.repository.OperRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OperService extends AbstractBaseService<Oper, Long> {

    private final OperRepository operRepository;

    public OperService(OperRepository repository, Validator validator) {
        super(repository, validator);
        this.operRepository = repository;
    }

    @Override
    public List<Oper> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        return operRepository.searchByTerm(term, PageRequest.of(0, 100));
    }
}

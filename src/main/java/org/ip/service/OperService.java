package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Oper;
import org.ip.repository.OperRepository;
import org.springframework.stereotype.Service;
import org.ipro.crud.AbstractBaseService;

/**
 * C4.4 pilot: search делегируется canonical engine (ADR-0007 §7).
 */
@Service
public class OperService extends AbstractBaseService<Oper, Long> {

    public OperService(OperRepository repository, Validator validator) {
        super(repository, validator);
    }
}

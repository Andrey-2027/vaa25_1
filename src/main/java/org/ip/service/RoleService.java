package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Role;
import org.ip.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.ipro.crud.AbstractBaseService;

/**
 * C4.4: in-memory {@code findAll().stream().filter(...)} search удалён — выдача теперь
 * bounded и серверная (ADR-0007 §7), а не «загрузить всю таблицу и отфильтровать».
 */
@Service
public class RoleService extends AbstractBaseService<Role, Long> {

    public RoleService(RoleRepository repository, Validator validator) {
        super(repository, validator);
    }
}

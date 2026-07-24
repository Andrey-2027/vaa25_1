package org.ip.service;

import org.ipro.crud.CrudService;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

public interface BaseService<T extends IdentifiableEntity, ID> extends CrudService<T> {
    T save(T entity);
    T create(T entity);
    T update(T entity);
    void delete(ID id);
    Optional<T> findById(ID id);
    List<T> findAll();
    Page<T> findAll(Pageable pageable);
    List<T> search(String term);
    Page<T> search(String term, Pageable pageable);

    default Page<T> findAll(Specification<T> spec, Pageable pageable) {
        throw new UnsupportedOperationException("findAll(Specification, Pageable) not implemented in " + getClass().getSimpleName());
    }
}

package org.ipro.data;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.Optional;

/**
 * MODULE_API контракт для чтений REST (F-REST-READ-3).
 * Клиент (REST-адаптер) не выбирает произвольный fetch scenario.
 */
public interface EntityReadAccess {

    /**
     * Постраничный список: сценарий API-LIST.
     * @param fixedFetchPaths полный фиксированный набор полных persistent source paths REST-операции.
     */
    <T> Page<T> list(Class<T> type, Specification<T> filter,
                     Pageable pageable, Collection<String> fixedFetchPaths);

    /**
     * Одна запись: сценарий API-DETAIL.
     * @param fixedFetchPaths полный фиксированный набор полных persistent source paths REST-операции.
     */
    <T> Optional<T> detail(Class<T> type, Object id,
                           Collection<String> fixedFetchPaths);
}

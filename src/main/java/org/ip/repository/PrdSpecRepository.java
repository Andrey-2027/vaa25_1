package org.ip.repository;

import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrdSpecRepository extends JpaRepository<PrdSpec, Long>, JpaSpecificationExecutor<PrdSpec> {

    Optional<PrdSpec> findByCodeSpec(String codeSpec);

    boolean existsByCodeSpec(String codeSpec);

    /**
     * Поиск спецификаций по журналу (для кастомного View с фильтром по журналу).
     */
    Page<PrdSpec> findByJournal(Journal journal, Pageable pageable);
}

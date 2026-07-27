package org.ip.repository;

import org.ip.model.Journal;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JournalRepository extends JpaRepository<Journal, Long>, JpaSpecificationExecutor<Journal> {

    Optional<Journal> findByCode(String code);

    boolean existsByCode(String code);

    @Query("SELECT j FROM Journal j WHERE LOWER(j.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(j.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    List<Journal> searchByTerm(@Param("term") String term, Pageable pageable);
}

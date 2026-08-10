package org.ip.repository;

import org.ip.model.Branch;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BranchRepository extends JpaRepository<Branch, Long>, JpaSpecificationExecutor<Branch> {

    Optional<Branch> findByCode(String code);

    boolean existsByCode(String code);

    @Query("SELECT b FROM Branch b WHERE LOWER(b.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(b.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    List<Branch> searchByTerm(@Param("term") String term, Pageable pageable);
}
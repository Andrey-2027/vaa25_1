package org.ip.repository;


import org.ip.model.Workshop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkshopRepository extends JpaRepository<Workshop, Long>, JpaSpecificationExecutor<Workshop> {

    Optional<Workshop> findByCode(String code);

    boolean existsByCode(String code);

    @Query("SELECT w FROM Workshop w WHERE LOWER(w.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(w.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    List<Workshop> searchByTerm(@Param("term") String term, org.springframework.data.domain.Pageable pageable);
}

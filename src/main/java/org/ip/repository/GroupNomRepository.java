package org.ip.repository;

import org.ip.model.GroupNom;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupNomRepository extends JpaRepository<GroupNom, Long>, JpaSpecificationExecutor<GroupNom> {

    Optional<GroupNom> findByCode(String code);

    boolean existsByCode(String code);

    @Query("SELECT g FROM GroupNom g WHERE LOWER(g.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(g.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    List<GroupNom> searchByTerm(@Param("term") String term, Pageable pageable);

    @Query("SELECT g FROM GroupNom g WHERE LOWER(g.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(g.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    Page<GroupNom> findWithFilter(@Param("term") String term, Pageable pageable);
}

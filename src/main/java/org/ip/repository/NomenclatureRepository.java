package org.ip.repository;

import org.ip.model.Nomenclature;
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
public interface NomenclatureRepository extends JpaRepository<Nomenclature, Long>, JpaSpecificationExecutor<Nomenclature> {

    Optional<Nomenclature> findByCode(String code);

    boolean existsByCode(String code);

    @Query("SELECT n FROM Nomenclature n WHERE LOWER(n.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(n.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    List<Nomenclature> searchByTerm(@Param("term") String term, Pageable pageable);

    @Query("SELECT n FROM Nomenclature n WHERE LOWER(n.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(n.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    Page<Nomenclature> findWithFilter(@Param("term") String term, Pageable pageable);
}

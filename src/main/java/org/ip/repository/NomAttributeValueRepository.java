package org.ip.repository;

import org.ip.model.AttributeType;
import org.ip.model.Nomenclature;
import org.ip.model.NomAttributeValue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NomAttributeValueRepository extends JpaRepository<NomAttributeValue, Long>,
        JpaSpecificationExecutor<NomAttributeValue> {

    @EntityGraph(attributePaths = {"attrType", "attrValue"})
    List<NomAttributeValue> findByNomenclatureOrderByAttrType(Nomenclature nomenclature);

    Optional<NomAttributeValue> findByNomenclatureAndAttrType(Nomenclature nomenclature, AttributeType attrType);

    void deleteByNomenclature(Nomenclature nomenclature);

    boolean existsByNomenclature(Nomenclature nomenclature);

    @Query("SELECT n FROM NomAttributeValue n WHERE LOWER(n.attrValue.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(n.attrValue.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    List<NomAttributeValue> searchByTerm(@Param("term") String term, Pageable pageable);

    @Query("SELECT n FROM NomAttributeValue n WHERE LOWER(n.attrValue.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(n.attrValue.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    Page<NomAttributeValue> findWithFilter(@Param("term") String term, Pageable pageable);
}
package org.ip.repository;

import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
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
public interface AttributeValueRepository extends JpaRepository<AttributeValue, Long>,
        JpaSpecificationExecutor<AttributeValue> {

    Optional<AttributeValue> findByAttrTypeAndCodeUp(AttributeType attrType, String codeUp);

    Optional<AttributeValue> findByAttrTypeAndRefId(AttributeType attrType, Long refId);

    List<AttributeValue> findByAttrTypeOrderByCode(AttributeType attrType);

    boolean existsByAttrType(AttributeType attrType);

    @Query("SELECT v FROM AttributeValue v WHERE LOWER(v.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(v.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    List<AttributeValue> searchByTerm(@Param("term") String term, Pageable pageable);

    @Query("SELECT v FROM AttributeValue v WHERE LOWER(v.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(v.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    Page<AttributeValue> findWithFilter(@Param("term") String term, Pageable pageable);
}
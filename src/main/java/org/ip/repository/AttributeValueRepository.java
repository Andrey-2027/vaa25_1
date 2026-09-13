package org.ip.repository;

import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
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

    /** Строка значения под запись: проверка цели переименования и запись атомарны. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from AttributeValue v where v.id = :id")
    Optional<AttributeValue> findByIdForUpdate(@Param("id") Long id);

    List<AttributeValue> findByAttrTypeOrderByCode(AttributeType attrType);

    boolean existsByAttrType(AttributeType attrType);
}

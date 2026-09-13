package org.ip.repository;

import org.ip.model.AttributeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AttributeTypeRepository extends JpaRepository<AttributeType, Long>,
        JpaSpecificationExecutor<AttributeType> {

    Optional<AttributeType> findByCode(String code);

    /** Строка типа под запись: проверка цели и создание значения атомарны. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AttributeType t where t.id = :id")
    Optional<AttributeType> findByIdForUpdate(@Param("id") Long id);

    boolean existsByCode(String code);
}

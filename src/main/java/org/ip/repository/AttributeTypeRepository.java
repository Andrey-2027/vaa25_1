package org.ip.repository;

import org.ip.model.AttributeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    @Query("SELECT a FROM AttributeType a WHERE LOWER(a.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(a.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    List<AttributeType> searchByTerm(@Param("term") String term, Pageable pageable);

    @Query("SELECT a FROM AttributeType a WHERE LOWER(a.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(a.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    Page<AttributeType> findWithFilter(@Param("term") String term, Pageable pageable);
}
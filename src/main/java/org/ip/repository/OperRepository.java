package org.ip.repository;

import org.ip.model.Oper;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OperRepository extends JpaRepository<Oper, Long>, JpaSpecificationExecutor<Oper> {

    Optional<Oper> findByCode(String code);

    boolean existsByCode(String code);

    @Query("SELECT o FROM Oper o WHERE LOWER(o.code) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(o.name) LIKE LOWER(CONCAT('%', :term, '%'))")
    List<Oper> searchByTerm(@Param("term") String term, Pageable pageable);
}

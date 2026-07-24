package org.ip.repository;

import org.ip.model.UnitOfMeasurement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UnitOfMeasurementRepository extends JpaRepository<UnitOfMeasurement, Long>, JpaSpecificationExecutor<UnitOfMeasurement> {

    Optional<UnitOfMeasurement> findByCode(String code);

    boolean existsByCode(String code);

    @Query("SELECT u FROM UnitOfMeasurement u WHERE LOWER(u.shortCode) LIKE LOWER(CONCAT('%', :term, '%'))")
    List<UnitOfMeasurement> searchByTerm(@Param("term") String term, Pageable pageable);
}

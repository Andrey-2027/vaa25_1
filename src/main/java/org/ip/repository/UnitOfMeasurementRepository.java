package org.ip.repository;

import org.ip.model.UnitOfMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;



@Repository
public interface UnitOfMeasurementRepository extends JpaRepository<UnitOfMeasurement, Long>, JpaSpecificationExecutor<UnitOfMeasurement> {





}

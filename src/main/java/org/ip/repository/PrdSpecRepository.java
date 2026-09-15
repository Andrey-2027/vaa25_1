package org.ip.repository;

import org.ip.model.PrdSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface PrdSpecRepository extends JpaRepository<PrdSpec, Long>, JpaSpecificationExecutor<PrdSpec> {
}

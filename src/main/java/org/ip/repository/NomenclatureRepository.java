package org.ip.repository;

import org.ip.model.Nomenclature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface NomenclatureRepository extends JpaRepository<Nomenclature, Long>, JpaSpecificationExecutor<Nomenclature> {
}

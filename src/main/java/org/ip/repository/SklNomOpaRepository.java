package org.ip.repository;

import org.ip.model.Nomenclature;
import org.ip.model.SklNomOpa;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SklNomOpaRepository extends JpaRepository<SklNomOpa, Long>,
        JpaSpecificationExecutor<SklNomOpa> {

    Optional<SklNomOpa> findByNomenclatureAndCanonical(Nomenclature nomenclature, String canonical);

    @EntityGraph(attributePaths = {"nomenclature"})
    List<SklNomOpa> findByNomenclature(Nomenclature nomenclature);
}

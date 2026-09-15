package org.ip.repository;

import org.ip.model.AttributeType;
import org.ip.model.Nomenclature;
import org.ip.model.NomSklAttribute;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NomSklAttributeRepository extends JpaRepository<NomSklAttribute, Long>,
        JpaSpecificationExecutor<NomSklAttribute> {

    @EntityGraph(attributePaths = {"attrType"})
    List<NomSklAttribute> findByNomenclature(Nomenclature nomenclature);

    Optional<NomSklAttribute> findByNomenclatureAndAttrType(Nomenclature nomenclature, AttributeType attrType);

    boolean existsByAttrType(AttributeType attrType);

    void deleteByNomenclature(Nomenclature nomenclature);
}

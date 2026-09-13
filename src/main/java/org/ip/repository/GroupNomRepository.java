package org.ip.repository;

import org.ip.model.GroupNom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupNomRepository extends JpaRepository<GroupNom, Long>, JpaSpecificationExecutor<GroupNom> {

    Optional<GroupNom> findByCode(String code);

    boolean existsByCode(String code);
}

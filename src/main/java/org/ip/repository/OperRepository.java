package org.ip.repository;

import org.ip.model.Oper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OperRepository extends JpaRepository<Oper, Long>, JpaSpecificationExecutor<Oper> {

    Optional<Oper> findByCode(String code);

    boolean existsByCode(String code);
}

package org.ip.repository;

import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecOper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrdSpecOperRepository extends JpaRepository<PrdSpecOper, Long>, JpaSpecificationExecutor<PrdSpecOper> {

    List<PrdSpecOper> findByPrdSpecOrderByOrder(PrdSpec prdSpec);
}

package org.ip.repository;

import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrdSpecMtrRepository extends JpaRepository<PrdSpecMtr, Long>, JpaSpecificationExecutor<PrdSpecMtr> {

    List<PrdSpecMtr> findByPrdSpec(PrdSpec prdSpec);

}

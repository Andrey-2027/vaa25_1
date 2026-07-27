package org.ip.service;

import jakarta.persistence.EntityGraph;
import jakarta.validation.Validator;
import org.ip.metadata.MetadataResolver;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.repository.PrdSpecMtrRepository;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PrdSpecMtrService extends AbstractTableSectionService<PrdSpecMtr, Long, PrdSpec> {

    private PrdSpecMtrRepository repository;

    public PrdSpecMtrService(PrdSpecMtrRepository repository, MetadataResolver metadataResolver) {
        super(repository, metadataResolver, PrdSpecMtr.class);
        this.repository = repository;
    }

    @Override
    public List<PrdSpecMtr> findByParent(PrdSpec parent) {
        List<PrdSpecMtr> rows = repository.findByPrdSpec(parent);
        return initializeLazyFields(rows);
    }

    @Override
    public PrdSpecMtr createNew(PrdSpec parent) {
        PrdSpecMtr row = new PrdSpecMtr();
        row.setPrdSpec(parent);
        return row;
    }
}

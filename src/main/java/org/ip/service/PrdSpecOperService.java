package org.ip.service;

import jakarta.validation.Validator;
import org.ip.metadata.MetadataResolver;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.PrdSpecOper;
import org.ip.repository.PrdSpecOperRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PrdSpecOperService extends AbstractTableSectionService<PrdSpecOper, Long, PrdSpec> {

    private PrdSpecOperRepository repository;

    public PrdSpecOperService(PrdSpecOperRepository repository, MetadataResolver metadataResolver) {
        super(repository, metadataResolver, PrdSpecOper.class);
        this.repository = repository;
    }

    @Override
    public PrdSpecOper createNew(PrdSpec parent) {
        PrdSpecOper row = new PrdSpecOper();
        row.setPrdSpec(parent);
        return row;
    }

    @Override
    public List<PrdSpecOper> findByParent(PrdSpec parent) {
        List<PrdSpecOper> rows = repository.findByPrdSpecOrderByOrder(parent);
        return initializeLazyFields(rows);
    }
}

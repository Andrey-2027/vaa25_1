package org.ip.service;

import org.ipro.metadata.MetadataResolver;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.repository.PrdSpecMtrRepository;
import org.springframework.stereotype.Service;

@Service
public class PrdSpecMtrService extends AbstractTableSectionService<PrdSpecMtr, Long, PrdSpec> {

    public PrdSpecMtrService(PrdSpecMtrRepository repository, MetadataResolver metadataResolver) {
        super(repository, metadataResolver, PrdSpecMtr.class);
    }
}

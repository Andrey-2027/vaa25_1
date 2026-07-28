package org.ip.service;

import org.ip.metadata.MetadataResolver;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecOper;
import org.ip.repository.PrdSpecOperRepository;
import org.springframework.stereotype.Service;

@Service
public class PrdSpecOperService extends AbstractTableSectionService<PrdSpecOper, Long, PrdSpec> {

    public PrdSpecOperService(PrdSpecOperRepository repository, MetadataResolver metadataResolver) {
        super(repository, metadataResolver, PrdSpecOper.class);
    }
}

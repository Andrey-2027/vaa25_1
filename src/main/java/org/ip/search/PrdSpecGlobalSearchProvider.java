package org.ip.search;

import org.ip.model.PrdSpec;
import org.ipro.search.GlobalSearchMatch;
import org.ipro.search.GlobalSearchProvider;
import org.ipro.search.GlobalSearchSource;
import org.ipro.search.JpaGlobalSearchProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Preserves the established specification result label without loading its nomenclature. */
@Component
public final class PrdSpecGlobalSearchProvider implements GlobalSearchProvider<PrdSpec> {

    private final JpaGlobalSearchProvider<PrdSpec> classifier =
        new JpaGlobalSearchProvider<>(PrdSpec.class);

    @Override
    public Class<PrdSpec> entityClass() {
        return PrdSpec.class;
    }

    @Override
    public Object idOf(PrdSpec entity) {
        return entity.getId();
    }

    @Override
    public String displayValue(PrdSpec entity, GlobalSearchSource source) {
        List<String> parts = new ArrayList<>(2);
        if (entity.getCodeSpec() != null) {
            parts.add(entity.getCodeSpec());
        }
        if (entity.getDraft() != null) {
            parts.add(entity.getDraft());
        }
        return String.join(" — ", parts);
    }

    @Override
    public GlobalSearchMatch classify(PrdSpec entity, GlobalSearchSource source, String term) {
        return classifier.classify(entity, source, term);
    }
}

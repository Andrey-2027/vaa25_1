package org.ipro.crud;

import org.ipro.metadata.TableSectionMetadataInfo;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Descriptor-bound adapter used by {@code ItemTable} for a standard owned section.
 *
 * <p>It replaces one application service/repository pair per row type. The adapter
 * contains no domain knowledge: all behaviour is delegated to
 * {@link GenericOwnedSectionService} and the resolved section descriptor.</p>
 */
public final class MetadataTableSectionService<
        R extends IdentifiableEntity, P extends IdentifiableEntity>
        implements TableSectionService<R, P> {

    private final GenericOwnedSectionService delegate;
    private final TableSectionMetadataInfo descriptor;

    public MetadataTableSectionService(GenericOwnedSectionService delegate,
                                       TableSectionMetadataInfo descriptor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
    }

    @Override
    public List<R> findByParent(P parent) {
        return delegate.findByParent(parent, descriptor);
    }

    @Override
    public List<R> findByParent(P parent, Collection<String> fetchPaths) {
        return delegate.findByParent(parent, descriptor, fetchPaths);
    }

    @Override
    public R createNew(P parent) {
        return delegate.createNew(parent, descriptor);
    }

    @Override
    public List<String> validateRows(P parent, List<R> rows) {
        return delegate.validateRows(parent, rows, descriptor);
    }

    @Override
    public void replaceAll(P parent, List<R> rows) {
        delegate.replaceAll(parent, rows, descriptor);
    }
}

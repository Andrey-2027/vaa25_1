package org.ipro.filter;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Декоратор resolver-а с вариантами значений для enum и ссылочных полей. */
public final class LookupFilterFieldResolver implements FilterFieldResolver {
    private final FilterFieldResolver delegate;
    private final Function<ResolvedFilterField, List<?>> optionsProvider;

    public LookupFilterFieldResolver(FilterFieldResolver delegate,
                                     Function<ResolvedFilterField, List<?>> optionsProvider) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.optionsProvider = optionsProvider == null ? ignored -> List.of() : optionsProvider;
    }

    @Override
    public List<ResolvedFilterField> fields() {
        return delegate.fields();
    }

    @Override
    public ResolvedFilterField resolve(String path) {
        return delegate.resolve(path);
    }

    @Override
    public List<?> valueOptions(ResolvedFilterField field) {
        if (field == null) return List.of();
        if (field.dataType() == FilterDataType.ENUM && field.javaType().isEnum()) {
            return List.of(field.javaType().getEnumConstants());
        }
        return List.copyOf(optionsProvider.apply(field));
    }
}

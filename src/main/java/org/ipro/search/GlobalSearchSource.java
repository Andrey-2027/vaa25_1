package org.ipro.search;

import java.util.List;
import java.util.Objects;

/**
 * Validated runtime descriptor of one global-search source.
 *
 * @param declarationOrder stable order from {@link GlobalSearchable#order()}
 * @param entityClass entity class
 * @param searchFields fields resolved from the shared canonical search policy
 * @param additionalPaths paths needed to classify/display loaded rows without lazy reads
 * @param idFieldName primary-key field used for deterministic ordering
 * @param groupTitle title displayed for the result group
 */
public record GlobalSearchSource(int declarationOrder,
                                 Class<?> entityClass,
                                 List<String> searchFields,
                                 List<String> additionalPaths,
                                 String idFieldName,
                                 String groupTitle) {
    public GlobalSearchSource {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        Objects.requireNonNull(searchFields, "searchFields cannot be null");
        Objects.requireNonNull(additionalPaths, "additionalPaths cannot be null");
        Objects.requireNonNull(idFieldName, "idFieldName cannot be null");
        Objects.requireNonNull(groupTitle, "groupTitle cannot be null");
        if (idFieldName.isBlank()) {
            throw new IllegalArgumentException("idFieldName cannot be blank");
        }
        searchFields = List.copyOf(searchFields);
        additionalPaths = List.copyOf(additionalPaths);
    }

    /** Compatibility constructor when no extra association paths are required. */
    public GlobalSearchSource(int declarationOrder, Class<?> entityClass,
                              List<String> searchFields, String idFieldName, String groupTitle) {
        this(declarationOrder, entityClass, searchFields, List.of(), idFieldName, groupTitle);
    }
}

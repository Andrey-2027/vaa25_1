package org.ipro.metadata;

/** Pure application-intent policy supplied by core to lower metadata consumers. */
@FunctionalInterface
public interface EntityMetadataApplicability {
    boolean isApplicable(Class<?> type);
}

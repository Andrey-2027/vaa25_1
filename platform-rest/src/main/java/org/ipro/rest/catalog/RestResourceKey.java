package org.ipro.rest.catalog;

import java.util.Objects;

/** Internal stable identity for one explicitly declared resource major. */
public record RestResourceKey(String resource, int major) implements Comparable<RestResourceKey> {

    public RestResourceKey {
        Objects.requireNonNull(resource, "resource must not be null");
        if (resource.isBlank()) throw new IllegalArgumentException("resource must not be blank");
        if (major < 1) throw new IllegalArgumentException("major must be positive");
    }

    @Override
    public int compareTo(RestResourceKey other) {
        int byResource = resource.compareTo(other.resource);
        return byResource != 0 ? byResource : Integer.compare(major, other.major);
    }
}

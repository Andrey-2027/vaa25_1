package org.ipro.rest.api;

/**
 * A declarative property path owned by the application contract.
 *
 * <p>The value is kept as written. {@link RestResourceBuilder#build()} checks only its syntax;
 * existence, mapping, type and access to each association belong to the later resource catalog.</p>
 */
public record RestPropertyPath(String value) {
}

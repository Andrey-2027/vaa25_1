package org.ipro.rest.web.security;

/** Canonical target of the versioned namespace; {@code id} is null for a LIST request. */
record RestRequestTarget(String resource, int major, String id) {

    boolean detail() {
        return id != null;
    }
}

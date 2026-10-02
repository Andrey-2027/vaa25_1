package org.ipro.rest.service;

/**
 * Semantic outcomes of server-side REST read pipeline (F-REST-READ-3 §6.2).
 */
public enum RestReadOutcome {
    UNAUTHENTICATED,
    RESOURCE_NOT_FOUND,
    FORBIDDEN,
    INVALID_REQUEST,
    DETAIL_NOT_FOUND,
    SUCCESS,
    SECURITY_UNAVAILABLE
}

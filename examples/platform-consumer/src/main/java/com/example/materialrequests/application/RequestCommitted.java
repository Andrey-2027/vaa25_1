package com.example.materialrequests.application;

import com.example.materialrequests.model.RequestStatus;

/** Immutable application fact published only by the platform after-commit callback. */
public record RequestCommitted(Long id, String number, RequestStatus status) {}

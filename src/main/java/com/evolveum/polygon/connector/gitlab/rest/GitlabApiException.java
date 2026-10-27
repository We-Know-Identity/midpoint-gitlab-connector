/*
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

/**
 * An API failure whose message intentionally excludes request/response bodies
 * and credentials.
 */
final class GitlabApiException extends RuntimeException {

    private final int statusCode;

    GitlabApiException(String method, String sanitizedPath, int statusCode) {
        super("GitLab API " + method + " " + sanitizedPath + " failed with HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    int statusCode() {
        return statusCode;
    }
}

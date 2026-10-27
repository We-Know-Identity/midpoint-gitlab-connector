/*
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.CREATED_AT;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.CREATED_BY;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.EXPIRES_AT;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.ID;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.USERNAME;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import org.json.JSONObject;

/**
 * Classifies direct GitLab memberships from provenance stored atomically on
 * the membership row. Nothing here is exposed through the ConnId schema.
 */
final class GitlabMembershipProvenance {

    enum Ownership {
        PACKAGE_CREATED,
        PRE_EXISTING_EXTERNAL,
        EXTERNAL_TAKEOVER,
        UNKNOWN_LEGACY,
        CONFLICTING
    }

    record IntegrationActor(String id, String username) {
    }

    record DirectMembership(
            String memberId,
            Integer accessLevel,
            String createdAt,
            String creatorId,
            String creatorUsername,
            boolean expiresAtPresent,
            boolean expiresAtNull) {

        static DirectMembership from(JSONObject value) {
            JSONObject creator = value.optJSONObject(CREATED_BY);
            return new DirectMembership(
                    identifier(value, ID),
                    integer(value, "access_level"),
                    string(value, CREATED_AT),
                    creator == null ? null : identifier(creator, ID),
                    creator == null ? null : string(creator, USERNAME),
                    value.has(EXPIRES_AT),
                    value.has(EXPIRES_AT) && value.isNull(EXPIRES_AT));
        }
    }

    private GitlabMembershipProvenance() {
    }

    static Ownership classify(
            DirectMembership membership,
            IntegrationActor actor,
            String expectedMemberId,
            int expectedAccessLevel) {

        if (membership.memberId() == null
                || membership.accessLevel() == null
                || membership.creatorId() == null
                || membership.creatorUsername() == null
                || !membership.expiresAtPresent()
                || !validInstant(membership.createdAt())) {
            return Ownership.UNKNOWN_LEGACY;
        }
        if (!expectedMemberId.equals(membership.memberId())) {
            return Ownership.CONFLICTING;
        }

        boolean idMatches = actor.id().equals(membership.creatorId());
        boolean usernameMatches = actor.username().equals(membership.creatorUsername());
        if (idMatches != usernameMatches) {
            return Ownership.CONFLICTING;
        }
        if (!idMatches) {
            return Ownership.PRE_EXISTING_EXTERNAL;
        }
        if (membership.accessLevel() != expectedAccessLevel || !membership.expiresAtNull()) {
            return Ownership.EXTERNAL_TAKEOVER;
        }
        return Ownership.PACKAGE_CREATED;
    }

    /**
     * Determines whether two incomplete API views still identify the same row
     * created by one POST. This is intentionally stricter than member ID: GitLab
     * keys membership URLs by user ID, so an external delete/recreate would have
     * the same URL but a different creation fingerprint.
     */
    static boolean sameCreationFingerprint(
            DirectMembership created,
            DirectMembership authoritative) {

        if (created.memberId() == null
                || created.accessLevel() == null
                || !validInstant(created.createdAt())
                || !created.expiresAtPresent()
                || authoritative.memberId() == null
                || authoritative.accessLevel() == null
                || !validInstant(authoritative.createdAt())
                || !authoritative.expiresAtPresent()) {
            return false;
        }
        return created.memberId().equals(authoritative.memberId())
                && created.accessLevel().equals(authoritative.accessLevel())
                && created.createdAt().equals(authoritative.createdAt())
                && created.expiresAtNull() == authoritative.expiresAtNull()
                && Objects.equals(created.creatorId(), authoritative.creatorId())
                && Objects.equals(created.creatorUsername(), authoritative.creatorUsername());
    }

    private static boolean validInstant(String value) {
        if (value == null) {
            return false;
        }
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static String identifier(JSONObject value, String name) {
        if (!value.has(name) || value.isNull(name)) {
            return null;
        }
        Object raw = value.get(name);
        if (!(raw instanceof Number) && !(raw instanceof String)) {
            return null;
        }
        String result = String.valueOf(raw);
        return result.isBlank() ? null : result;
    }

    private static String string(JSONObject value, String name) {
        if (!value.has(name) || value.isNull(name) || !(value.get(name) instanceof String result)) {
            return null;
        }
        return result.isBlank() ? null : result;
    }

    private static Integer integer(JSONObject value, String name) {
        if (!value.has(name) || value.isNull(name) || !(value.get(name) instanceof Number number)) {
            return null;
        }
        return number.intValue();
    }
}

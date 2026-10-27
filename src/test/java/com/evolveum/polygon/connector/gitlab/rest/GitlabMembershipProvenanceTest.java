/*
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import static com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.Ownership.CONFLICTING;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.Ownership.EXTERNAL_TAKEOVER;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.Ownership.PACKAGE_CREATED;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.Ownership.PRE_EXISTING_EXTERNAL;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.Ownership.UNKNOWN_LEGACY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.DirectMembership;
import com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.IntegrationActor;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class GitlabMembershipProvenanceTest {

    private static final String SYNTHETIC_MEMBER_ID = "7001";
    private static final String SYNTHETIC_ACTOR_ID = "9001";
    private static final IntegrationActor ACTOR =
            new IntegrationActor(SYNTHETIC_ACTOR_ID, "easyiga-midpoint");

    @Test
    void exactMembershipCreatedByIntegrationActorIsPackageOwned() {
        assertEquals(
                PACKAGE_CREATED,
                classify(membership(
                        SYNTHETIC_MEMBER_ID,
                        30,
                        SYNTHETIC_ACTOR_ID,
                        "easyiga-midpoint",
                        "2026-07-29T10:00:00Z",
                        JSONObject.NULL)));
    }

    @Test
    void exactMembershipCreatedByAnotherActorIsExternal() {
        assertEquals(
                PRE_EXISTING_EXTERNAL,
                classify(membership(
                        SYNTHETIC_MEMBER_ID,
                        30,
                        "8001",
                        "external-admin",
                        "2026-07-29T10:00:00Z",
                        JSONObject.NULL)));
    }

    @Test
    void strongerOrLowerPackageOriginMembershipIsExternalTakeover() {
        assertEquals(
                EXTERNAL_TAKEOVER,
                classify(packageMembership(40)));
        assertEquals(
                EXTERNAL_TAKEOVER,
                classify(packageMembership(20)));
    }

    @Test
    void expiryOnPackageOriginMembershipIsExternalTakeover() {
        assertEquals(
                EXTERNAL_TAKEOVER,
                classify(membership(
                        SYNTHETIC_MEMBER_ID,
                        30,
                        SYNTHETIC_ACTOR_ID,
                        "easyiga-midpoint",
                        "2026-07-29T10:00:00Z",
                        "2026-08-29")));
    }

    @Test
    void missingOrMalformedProvenanceIsUnknownLegacy() {
        JSONObject missingCreator = packageMembership(30);
        missingCreator.remove("created_by");
        assertEquals(UNKNOWN_LEGACY, classify(missingCreator));

        JSONObject missingExpiryField = packageMembership(30);
        missingExpiryField.remove("expires_at");
        assertEquals(UNKNOWN_LEGACY, classify(missingExpiryField));

        assertEquals(
                UNKNOWN_LEGACY,
                classify(membership(
                        SYNTHETIC_MEMBER_ID,
                        30,
                        SYNTHETIC_ACTOR_ID,
                        "easyiga-midpoint",
                        "not-an-instant",
                        JSONObject.NULL)));
    }

    @Test
    void partialActorMatchOrWrongMemberIdentityIsConflicting() {
        assertEquals(
                CONFLICTING,
                classify(membership(
                        SYNTHETIC_MEMBER_ID,
                        30,
                        "8001",
                        "easyiga-midpoint",
                        "2026-07-29T10:00:00Z",
                        JSONObject.NULL)));
        assertEquals(
                CONFLICTING,
                classify(membership(
                        SYNTHETIC_MEMBER_ID,
                        30,
                        SYNTHETIC_ACTOR_ID,
                        "replacement-admin",
                        "2026-07-29T10:00:00Z",
                        JSONObject.NULL)));
        assertEquals(
                CONFLICTING,
                GitlabMembershipProvenance.classify(
                        DirectMembership.from(packageMembership(30)),
                        ACTOR,
                        "different-member",
                        30));
    }

    private static GitlabMembershipProvenance.Ownership classify(JSONObject value) {
        return GitlabMembershipProvenance.classify(
                DirectMembership.from(value), ACTOR, SYNTHETIC_MEMBER_ID, 30);
    }

    private static JSONObject packageMembership(int accessLevel) {
        return membership(
                SYNTHETIC_MEMBER_ID,
                accessLevel,
                SYNTHETIC_ACTOR_ID,
                "easyiga-midpoint",
                "2026-07-29T10:00:00Z",
                JSONObject.NULL);
    }

    private static JSONObject membership(
            String memberId,
            int accessLevel,
            String creatorId,
            String creatorUsername,
            String createdAt,
            Object expiresAt) {
        return new JSONObject()
                .put("id", Long.parseLong(memberId))
                .put("access_level", accessLevel)
                .put("created_at", createdAt)
                .put("created_by", new JSONObject()
                        .put("id", Long.parseLong(creatorId))
                        .put("username", creatorUsername))
                .put("expires_at", expiresAt);
    }
}

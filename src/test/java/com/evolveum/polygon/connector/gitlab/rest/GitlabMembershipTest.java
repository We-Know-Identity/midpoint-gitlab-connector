/*
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.IntegrationActor;

import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class GitlabMembershipTest {

    private static final IntegrationActor INTEGRATION_ACTOR =
            new IntegrationActor("9001", "easyiga-midpoint");

    @Test
    void changesExistingRoleWithPut() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"access_level\":20}");
            server.enqueue(200, "{\"id\":7,\"access_level\":30}");
            GitlabApiClient client = client(server);

            client.ensureDirectMember(
                    "/groups/42", "7", 30, true, INTEGRATION_ACTOR);

            assertEquals("GET", server.request(0).method());
            assertEquals("/api/v4/groups/42/members/7", server.request(0).rawPath());
            assertEquals("PUT", server.request(1).method());
            JSONObject payload = new JSONObject(server.request(1).body());
            assertEquals("7", payload.getString("user_id"));
            assertEquals(30, payload.getInt("access_level"));
        }
    }

    @Test
    void doesNotDowngradeExternallyElevatedRole() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"access_level\":50}");
            GitlabApiClient client = client(server);

            client.ensureDirectMember(
                    "/groups/42", "7", 30, true, INTEGRATION_ACTOR);

            assertEquals(1, server.requests().size());
            assertEquals("GET", server.request(0).method());
            assertEquals("/api/v4/groups/42/members/7", server.request(0).rawPath());
        }
    }

    @Test
    void createsMissingDirectMembershipWithPost() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(404, "{\"message\":\"not found\"}");
            server.enqueue(201, packageMembership("7", 40));
            GitlabApiClient client = client(server);

            client.ensureDirectMember(
                    "/projects/99", "7", 40, false, INTEGRATION_ACTOR);

            assertEquals("POST", server.request(1).method());
            assertEquals("/api/v4/projects/99/members", server.request(1).rawPath());
            assertEquals(40, new JSONObject(server.request(1).body()).getInt("access_level"));
        }
    }

    @Test
    void missingCreationFingerprintIsPreservedForReconciliation() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(404, "{\"message\":\"not found\"}");
            server.enqueue(201, "{\"id\":7,\"access_level\":40}");
            server.enqueue(200, "{\"id\":7,\"access_level\":40}");
            GitlabApiClient client = client(server);

            ConnectorIOException error = assertThrows(
                    ConnectorIOException.class,
                    () -> client.ensureDirectMember(
                            "/projects/99", "7", 40, false, INTEGRATION_ACTOR));

            assertEquals(
                    "GitLab membership provenance is incomplete; safe rollback refused",
                    error.getMessage());
            assertEquals(3, server.requests().size());
            org.junit.jupiter.api.Assertions.assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())));
        }
    }

    @Test
    void matchingUnknownCreationFingerprintIsRolledBack() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            String incomplete = incompleteMembership("7", 40, "2026-07-29T10:00:00Z");
            server.enqueue(404, "{\"message\":\"not found\"}");
            server.enqueue(201, incomplete);
            server.enqueue(200, incomplete);
            server.enqueue(204, "");
            GitlabApiClient client = client(server);

            assertThrows(
                    ConnectorIOException.class,
                    () -> client.ensureDirectMember(
                            "/projects/99", "7", 40, false, INTEGRATION_ACTOR));

            assertEquals(4, server.requests().size());
            assertEquals("DELETE", server.request(3).method());
            assertEquals("/api/v4/projects/99/members/7", server.request(3).rawPath());
        }
    }

    @Test
    void rollbackFailureIsReportedWithoutLeakingResponseBody() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            String incomplete = incompleteMembership("7", 40, "2026-07-29T10:00:00Z");
            server.enqueue(404, "{\"message\":\"not found\"}");
            server.enqueue(201, incomplete);
            server.enqueue(200, incomplete);
            server.enqueue(500, "{\"secret\":\"sensitive-response\"}");
            GitlabApiClient client = client(server);

            ConnectorIOException error = assertThrows(
                    ConnectorIOException.class,
                    () -> client.ensureDirectMember(
                            "/projects/99", "7", 40, false, INTEGRATION_ACTOR));

            assertEquals(
                    "GitLab membership provenance verification and rollback failed",
                    error.getMessage());
            org.junit.jupiter.api.Assertions.assertFalse(
                    error.toString().contains("sensitive-response"));
            assertEquals(4, server.requests().size());
        }
    }

    @Test
    void unknownExternalReplacementWithDifferentFingerprintIsPreserved() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(
                    404,
                    "{\"message\":\"not found\"}");
            server.enqueue(
                    201,
                    incompleteMembership("7", 40, "2026-07-29T10:00:00Z"));
            server.enqueue(
                    200,
                    incompleteMembership("7", 40, "2026-07-29T10:00:01Z"));
            GitlabApiClient client = client(server);

            assertThrows(
                    ConnectorIOException.class,
                    () -> client.ensureDirectMember(
                            "/projects/99", "7", 40, false, INTEGRATION_ACTOR));

            assertEquals(3, server.requests().size());
            org.junit.jupiter.api.Assertions.assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())));
        }
    }

    @Test
    void vanishedCreationNeedsNoCompensatingDelete() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(404, "{\"message\":\"not found\"}");
            server.enqueue(201, "{\"id\":7,\"access_level\":40}");
            server.enqueue(404, "{\"message\":\"not found\"}");
            GitlabApiClient client = client(server);

            assertThrows(
                    ConnectorIOException.class,
                    () -> client.ensureDirectMember(
                            "/projects/99", "7", 40, false, INTEGRATION_ACTOR));

            assertEquals(3, server.requests().size());
            org.junit.jupiter.api.Assertions.assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())));
        }
    }

    @Test
    void externalTakeoverDuringCreationVerificationIsPreserved() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(404, "{\"message\":\"not found\"}");
            server.enqueue(201, "{\"id\":7,\"access_level\":30}");
            server.enqueue(200, externalMembership("7", 30));
            GitlabApiClient client = client(server);

            ConnectorIOException error = assertThrows(
                    ConnectorIOException.class,
                    () -> client.ensureDirectMember(
                            "/groups/42", "7", 30, true, INTEGRATION_ACTOR));

            assertEquals(
                    "GitLab membership provenance changed during creation; cleanup refused",
                    error.getMessage());
            assertEquals(3, server.requests().size());
            org.junit.jupiter.api.Assertions.assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())));
        }
    }

    @Test
    void concurrentExternalCreateIsRefetchedAndNotClaimed() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(404, "{\"message\":\"not found\"}");
            server.enqueue(409, "{\"message\":\"already exists\"}");
            server.enqueue(200, externalMembership("7", 30));
            GitlabApiClient client = client(server);

            client.ensureDirectMember(
                    "/groups/42", "7", 30, true, INTEGRATION_ACTOR);

            assertEquals(3, server.requests().size());
            assertEquals("POST", server.request(1).method());
            assertEquals("GET", server.request(2).method());
        }
    }

    @Test
    void concurrentLowerExternalMembershipIsElevatedWithoutChangingOwnership() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(404, "{\"message\":\"not found\"}");
            server.enqueue(409, "{\"message\":\"already exists\"}");
            server.enqueue(200, externalMembership("7", 20));
            server.enqueue(200, externalMembership("7", 30));
            GitlabApiClient client = client(server);

            client.ensureDirectMember(
                    "/groups/42", "7", 30, true, INTEGRATION_ACTOR);

            assertEquals(4, server.requests().size());
            assertEquals("PUT", server.request(3).method());
            assertEquals(
                    30,
                    new JSONObject(server.request(3).body()).getInt("access_level"));
        }
    }

    @Test
    void membershipCreationFailureLeavesNoFalseOwnershipOrCleanup() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(404, "{\"message\":\"not found\"}");
            server.enqueue(500, "{\"message\":\"failed\"}");
            GitlabApiClient client = client(server);

            assertThrows(
                    GitlabApiException.class,
                    () -> client.ensureDirectMember(
                            "/groups/42", "7", 30, true, INTEGRATION_ACTOR));

            assertEquals(2, server.requests().size());
            assertEquals("POST", server.request(1).method());
        }
    }

    @Test
    void groupRemovalAlwaysSkipsSubresources() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(204, "");
            GitlabApiClient client = client(server);

            client.removeDirectMember("/groups/42", "7", true);

            assertEquals("DELETE", server.request(0).method());
            assertEquals("skip_subresources=true", server.request(0).rawQuery());
        }
    }

    @Test
    void projectRemovalDoesNotUseGroupOnlyParameter() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(204, "");
            GitlabApiClient client = client(server);

            client.removeDirectMember("/projects/99", "7", false);

            assertEquals("DELETE", server.request(0).method());
            assertNull(server.request(0).rawQuery());
        }
    }

    private static GitlabApiClient client(GitlabTestServer server) {
        return new GitlabApiClient(server.baseUrl(), "test-token", 2_000, 2_000);
    }

    private static String packageMembership(String userId, int accessLevel) {
        return membership(userId, accessLevel, "9001", "easyiga-midpoint");
    }

    private static String externalMembership(String userId, int accessLevel) {
        return membership(userId, accessLevel, "8001", "external-admin");
    }

    private static String incompleteMembership(
            String userId,
            int accessLevel,
            String createdAt) {
        return new JSONObject()
                .put("id", Long.parseLong(userId))
                .put("access_level", accessLevel)
                .put("created_at", createdAt)
                .put("expires_at", JSONObject.NULL)
                .toString();
    }

    private static String membership(
            String userId,
            int accessLevel,
            String creatorId,
            String creatorUsername) {
        return new JSONObject()
                .put("id", Long.parseLong(userId))
                .put("access_level", accessLevel)
                .put("created_at", "2026-07-29T10:00:00Z")
                .put("created_by", new JSONObject()
                        .put("id", Long.parseLong(creatorId))
                        .put("username", creatorUsername))
                .put("expires_at", JSONObject.NULL)
                .toString();
    }
}

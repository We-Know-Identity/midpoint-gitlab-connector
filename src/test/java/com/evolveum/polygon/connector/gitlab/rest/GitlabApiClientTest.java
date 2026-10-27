/*
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class GitlabApiClientTest {

    @Test
    void followsGitLabPaginationForUnpagedSearches() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "[{\"id\":1}]", Map.of("X-Next-Page", "2"));
            server.enqueue(200, "[{\"id\":2}]", Map.of("X-Next-Page", ""));
            GitlabApiClient client = client(server);

            List<JSONObject> users = client.listAll("/users", Map.of("active", "true"));

            assertEquals(List.of(1L, 2L), users.stream().map(value -> value.getLong("id")).toList());
            assertEquals("active=true&page=1&per_page=100", server.request(0).rawQuery());
            assertEquals("active=true&page=2&per_page=100", server.request(1).rawQuery());
        }
    }

    @Test
    void translatesConnIdOffsetToResultIndexRatherThanGitLabPageNumber() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "[{\"id\":101},{\"id\":102},{\"id\":103}]");
            GitlabApiClient client = client(server);

            List<JSONObject> users = client.listWindow("/users", Map.of(), 102, 2);

            assertEquals(List.of(102L, 103L), users.stream().map(value -> value.getLong("id")).toList());
            assertEquals("page=2&per_page=100", server.request(0).rawQuery());
        }
    }

    @Test
    void encodesFullPathsAsSingleGitLabIdentifiers() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":8,\"full_path\":\"easyiga-demo/platform\"}");
            GitlabApiClient client = client(server);

            client.getJson("/groups/" + GitlabApiClient.encodePathIdentifier("easyiga-demo/platform"));

            assertEquals("/api/v4/groups/easyiga-demo%2Fplatform", server.request(0).rawPath());
            assertEquals("test-token", server.request(0).firstHeader("PRIVATE-TOKEN"));
        }
    }

    @Test
    void listsProjectEventsWithFilters() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(
                    200,
                    "[{\"id\":1,\"author_id\":7,\"created_at\":\"2026-09-03T14:13:35.172Z\"}]",
                    Map.of("X-Next-Page", ""));
            GitlabApiClient client = client(server);

            List<JSONObject> events = client.projectEvents("/projects/99", Map.of("after", "2026-06-01"));

            assertEquals(1, events.size());
            assertEquals("/api/v4/projects/99/events", server.request(0).rawPath());
            assertEquals("after=2026-06-01&page=1&per_page=100", server.request(0).rawQuery());
        }
    }

    @Test
    void boundsGroupProjectsToTheConfiguredMaximumWithoutFurtherPages() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(
                    200,
                    "[{\"id\":1},{\"id\":2},{\"id\":3}]",
                    Map.of("X-Next-Page", "2"));
            GitlabApiClient client = client(server);

            List<JSONObject> projects = client.groupProjects("/groups/42", 2);

            assertEquals(List.of(1L, 2L), projects.stream().map(value -> value.getLong("id")).toList());
            assertEquals(1, server.requests().size());
            assertEquals("/api/v4/groups/42/projects", server.request(0).rawPath());
        }
    }

    @Test
    void keepsResponseBodiesAndTokensOutOfErrors() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(401, "{\"message\":\"token test-token is invalid\"}");
            GitlabApiClient client = client(server);

            GitlabApiException error = assertThrows(GitlabApiException.class, () -> client.getJson("/user"));

            assertEquals(401, error.statusCode());
            assertTrue(error.getMessage().contains("GET /user"));
            assertFalse(error.getMessage().contains("test-token"));
            assertFalse(error.getMessage().contains("invalid"));
        }
    }

    @Test
    void keepsMalformedSuccessBodiesOutOfErrors() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "not-json-test-token");
            GitlabApiClient client = client(server);

            ConnectorIOException error =
                    assertThrows(ConnectorIOException.class, () -> client.getJson("/user"));

            assertTrue(error.getMessage().contains("malformed JSON"));
            assertFalse(error.getMessage().contains("test-token"));
        }
    }

    private static GitlabApiClient client(GitlabTestServer server) {
        return new GitlabApiClient(server.baseUrl(), "test-token", 2_000, 2_000);
    }
}

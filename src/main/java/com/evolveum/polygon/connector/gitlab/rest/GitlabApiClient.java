/*
 * Copyright 2016-2017 Evolveum
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.ID;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.DirectMembership;
import com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.IntegrationActor;
import com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.Ownership;

import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Small GitLab API v4 client. It never logs or exposes the token, JSON request
 * body, or response body.
 */
final class GitlabApiClient {

    static final int API_PAGE_SIZE = 100;

    private final URI apiBase;
    private final String privateToken;
    private final Duration requestTimeout;
    private final HttpClient client;

    GitlabApiClient(GitlabRestConfiguration configuration) {
        this(
                configuration.getBaseUrl(),
                reveal(configuration),
                configuration.getConnectTimeoutMillis(),
                configuration.getRequestTimeoutMillis());
    }

    GitlabApiClient(String baseUrl, String privateToken, int connectTimeoutMillis, int requestTimeoutMillis) {
        this.apiBase = normalizeApiBase(baseUrl);
        this.privateToken = privateToken;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMillis);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    JSONObject getJson(String path) {
        return parseObject(expectSuccess(send("GET", path, Map.of(), null)).body(), "GET", path);
    }

    Optional<JSONObject> getOptionalJson(String path) {
        ApiResponse response = send("GET", path, Map.of(), null);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        return Optional.of(parseObject(expectSuccess(response).body(), "GET", path));
    }

    JSONObject postJson(String path, JSONObject body) {
        return parseOptionalObject(expectSuccess(send("POST", path, Map.of(), body)).body(), "POST", path);
    }

    JSONObject putJson(String path, JSONObject body) {
        return parseOptionalObject(expectSuccess(send("PUT", path, Map.of(), body)).body(), "PUT", path);
    }

    void postWithoutResult(String path) {
        expectSuccess(send("POST", path, Map.of(), null));
    }

    void delete(String path, Map<String, String> query) {
        expectSuccess(send("DELETE", path, query, null));
    }

    List<JSONObject> listAll(String path, Map<String, String> query) {
        return listWindow(path, query, null, null);
    }

    /**
     * ConnId offsets are one-based result indexes, not GitLab page numbers.
     */
    List<JSONObject> listWindow(
            String path,
            Map<String, String> query,
            Integer oneBasedOffset,
            Integer requestedSize) {

        int zeroBasedOffset = Math.max(0, oneBasedOffset == null ? 0 : oneBasedOffset - 1);
        int page = (zeroBasedOffset / API_PAGE_SIZE) + 1;
        int skipOnFirstPage = zeroBasedOffset % API_PAGE_SIZE;
        int remaining = requestedSize == null || requestedSize <= 0 ? Integer.MAX_VALUE : requestedSize;
        List<JSONObject> result = new ArrayList<>();

        while (remaining > 0) {
            Map<String, String> pageQuery = new LinkedHashMap<>(query);
            pageQuery.put("page", Integer.toString(page));
            pageQuery.put("per_page", Integer.toString(API_PAGE_SIZE));

            ApiResponse response = expectSuccess(send("GET", path, pageQuery, null));
            JSONArray values = parseArray(response.body(), "GET", path);
            for (int index = skipOnFirstPage; index < values.length() && remaining > 0; index++) {
                result.add(values.getJSONObject(index));
                remaining--;
            }
            skipOnFirstPage = 0;

            String nextPage = response.firstHeader("x-next-page");
            if (nextPage != null) {
                if (nextPage.isBlank()) {
                    break;
                }
                page = parsePositivePage(nextPage);
            } else if (values.length() < API_PAGE_SIZE) {
                break;
            } else {
                page++;
            }
        }
        return result;
    }

    /**
     * Lists a project's events (GET /projects/:id/events), honoring GitLab's
     * after/before/action/target_type filters via {@code query}. Reuses
     * {@link #listAll} pagination; never logs the token or response body.
     */
    List<JSONObject> projectEvents(String projectEntityPath, Map<String, String> query) {
        return listAll(projectEntityPath + "/events", query);
    }

    /**
     * Lists at most {@code maxResults} of a group's projects
     * (GET /groups/:id/projects), in GitLab's default list order. A declared
     * bound, not a loop: reuses {@link #listWindow}'s size cap so a group with
     * more projects than {@code maxResults} is read once, not exhaustively.
     */
    List<JSONObject> groupProjects(String groupEntityPath, int maxResults) {
        return listWindow(groupEntityPath + "/projects", Map.of(), 1, maxResults);
    }

    Set<String> directMembersAtAccessLevel(String entityPath, int accessLevel) {
        Set<String> result = new LinkedHashSet<>();
        for (JSONObject member : listAll(entityPath + "/members", Map.of())) {
            if (member.optInt("access_level", -1) == accessLevel && member.has(ID)) {
                result.add(String.valueOf(member.getLong(ID)));
            }
        }
        return result;
    }

    Optional<DirectMembership> directMembership(String entityPath, String userId) {
        return getOptionalJson(entityPath + "/members/" + encodePathIdentifier(userId))
                .map(DirectMembership::from);
    }

    void ensureDirectMember(
            String entityPath,
            String userId,
            int accessLevel,
            boolean group,
            IntegrationActor actor) {

        String memberPath = entityPath + "/members/" + encodePathIdentifier(userId);
        Optional<DirectMembership> existing = directMembership(entityPath, userId);
        if (existing.isPresent()) {
            convergeExistingMember(memberPath, userId, accessLevel, existing.get());
            return;
        }

        JSONObject payload = membershipPayload(userId, accessLevel);
        final JSONObject created;
        try {
            created = postJson(entityPath + "/members", payload);
        } catch (GitlabApiException error) {
            if (error.statusCode() != 409) {
                throw error;
            }
            // Another provisioning or administrator operation won the race
            // after our absence check. Refetch and converge the actual row;
            // its own creator metadata determines later cleanup ownership.
            DirectMembership raced = directMembership(entityPath, userId)
                    .orElseThrow(() -> new ConnectorIOException(
                            "GitLab membership conflict could not be reconciled"));
            convergeExistingMember(memberPath, userId, accessLevel, raced);
            return;
        }

        DirectMembership evidence = DirectMembership.from(created);
        if (GitlabMembershipProvenance.classify(evidence, actor, userId, accessLevel)
                == Ownership.PACKAGE_CREATED) {
            return;
        }

        // A pinned GitLab membership response must carry creator provenance.
        // Refetch once in case an intermediary omitted response fields.
        Optional<DirectMembership> authoritative = directMembership(entityPath, userId);
        if (authoritative.isEmpty()) {
            throw new ConnectorIOException(
                    "GitLab membership creation could not be confirmed");
        }
        Ownership authoritativeOwnership = GitlabMembershipProvenance.classify(
                authoritative.get(), actor, userId, accessLevel);
        if (authoritativeOwnership == Ownership.PACKAGE_CREATED) {
            return;
        }
        if (authoritativeOwnership != Ownership.UNKNOWN_LEGACY) {
            // The row changed hands or was externally modified between POST
            // and verification. It is no longer safe to compensate by deleting
            // it, even though the original POST succeeded.
            throw new ConnectorIOException(
                    "GitLab membership provenance changed during creation; cleanup refused");
        }

        // Member URLs contain only the user ID. Never compensate an
        // UNKNOWN_LEGACY response on that identity alone: another actor could
        // have deleted and recreated the row after our POST. Roll back only
        // when both API views carry the same stable creation fingerprint.
        if (!GitlabMembershipProvenance.sameCreationFingerprint(
                evidence, authoritative.get())) {
            throw new ConnectorIOException(
                    "GitLab membership provenance is incomplete; safe rollback refused");
        }
        try {
            removeDirectMember(entityPath, userId, group);
        } catch (RuntimeException rollbackFailure) {
            throw new ConnectorIOException(
                    "GitLab membership provenance verification and rollback failed",
                    rollbackFailure);
        }
        throw new ConnectorIOException(
                "GitLab membership provenance was not persisted; creation was rolled back");
    }

    private void convergeExistingMember(
            String memberPath,
            String userId,
            int accessLevel,
            DirectMembership existing) {

        Integer existingAccessLevel = existing.accessLevel();
        if (existingAccessLevel == null) {
            throw new ConnectorIOException(
                    "GitLab API GET " + sanitizedPath(memberPath)
                            + " omitted required membership access level");
        }
        // Never downgrade an externally elevated or package-origin membership.
        // The association denotes a minimum role while it is assigned.
        if (existingAccessLevel >= accessLevel) {
            return;
        }
        putJson(memberPath, membershipPayload(userId, accessLevel));
    }

    private static JSONObject membershipPayload(String userId, int accessLevel) {
        return new JSONObject()
                .put("user_id", userId)
                .put("access_level", accessLevel);
    }

    void removeDirectMember(String entityPath, String userId, boolean group) {
        Map<String, String> query = group ? Map.of("skip_subresources", "true") : Map.of();
        delete(entityPath + "/members/" + encodePathIdentifier(userId), query);
    }

    static String encodePathIdentifier(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private ApiResponse send(String method, String path, Map<String, String> query, JSONObject body) {
        URI uri = buildUri(path, query);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("PRIVATE-TOKEN", privateToken);

        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        }

        try {
            HttpResponse<String> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ApiResponse(method, sanitizedPath(path), response.statusCode(), response.headers().map(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorIOException("Interrupted while calling GitLab API " + method + " " + sanitizedPath(path), e);
        } catch (IOException e) {
            throw new ConnectorIOException("Unable to call GitLab API " + method + " " + sanitizedPath(path), e);
        }
    }

    private URI buildUri(String path, Map<String, String> query) {
        StringBuilder value = new StringBuilder(apiBase.toASCIIString());
        if (!path.startsWith("/")) {
            value.append('/');
        }
        value.append(path);
        if (!query.isEmpty()) {
            value.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    value.append('&');
                }
                first = false;
                value.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        return URI.create(value.toString());
    }

    private static ApiResponse expectSuccess(ApiResponse response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GitlabApiException(response.method(), response.sanitizedPath(), response.statusCode());
        }
        return response;
    }

    private static JSONObject parseOptionalObject(String body, String method, String path) {
        return body == null || body.isBlank() ? new JSONObject() : parseObject(body, method, path);
    }

    private static JSONObject parseObject(String body, String method, String path) {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new ConnectorIOException(
                    "GitLab API " + method + " " + sanitizedPath(path) + " returned malformed JSON");
        }
    }

    private static JSONArray parseArray(String body, String method, String path) {
        try {
            return new JSONArray(body);
        } catch (JSONException e) {
            throw new ConnectorIOException(
                    "GitLab API " + method + " " + sanitizedPath(path) + " returned malformed JSON");
        }
    }

    private static int parsePositivePage(String header) {
        try {
            int page = Integer.parseInt(header);
            if (page <= 0) {
                throw new NumberFormatException("not positive");
            }
            return page;
        } catch (NumberFormatException e) {
            throw new ConnectorIOException("GitLab returned an invalid X-Next-Page header");
        }
    }

    private static URI normalizeApiBase(String configuredBaseUrl) {
        String normalized = configuredBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.endsWith("/api/v4")) {
            normalized += "/api/v4";
        }
        return URI.create(normalized);
    }

    private static String reveal(GitlabRestConfiguration configuration) {
        StringBuilder token = new StringBuilder();
        configuration.getPrivateToken().access(chars -> token.append(chars));
        return token.toString();
    }

    private static String sanitizedPath(String path) {
        int query = path.indexOf('?');
        return query >= 0 ? path.substring(0, query) : path;
    }

    private record ApiResponse(
            String method,
            String sanitizedPath,
            int statusCode,
            Map<String, List<String>> headers,
            String body) {

        String firstHeader(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (name.equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }
}

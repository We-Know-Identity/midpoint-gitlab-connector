/*
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.DEVELOPER_MEMBERS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.DEVELOPER_MEMBERS_LAST_EVENT;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.DEVELOPER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_SCANNED;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_TRUNCATED;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.FULL_PATH;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.MASTER_MEMBERS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.MASTER_MEMBERS_LAST_EVENT;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.MASTER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.NAME;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.PATH_WITH_NAMESPACE;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.PROJECT_OBJECT_CLASS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.PermissionDeniedException;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.AttributeDelta;
import org.identityconnectors.framework.common.objects.AttributeDeltaBuilder;
import org.identityconnectors.framework.common.objects.AttributeInfo;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.FilterBuilder;
import org.identityconnectors.framework.spi.operations.DeleteOp;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class GitlabRestConnectorTest {

    private static final Clock TEST_CLOCK =
            Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TEST_DATE = LocalDate.of(2026, 7, 28);

    @Test
    void schemaExposesStablePathsAndOnlyRequiredMembershipsAsWritable() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            GitlabRestConnector connector = connector(server);

            Schema schema = connector.schema();
            ObjectClassInfo account = schema.findObjectClassInfo(ObjectClass.ACCOUNT_NAME);
            ObjectClassInfo group = schema.findObjectClassInfo(ObjectClass.GROUP_NAME);
            ObjectClassInfo project = schema.findObjectClassInfo(PROJECT_OBJECT_CLASS);

            assertNotNull(account);
            assertNotNull(group);
            assertNotNull(project);
            assertTrue(attribute(group, DEVELOPER_MEMBERS).isMultiValued());
            assertTrue(attribute(group, DEVELOPER_MEMBERS).isUpdateable());
            assertFalse(attribute(group, FULL_PATH).isUpdateable());
            assertTrue(attribute(project, MASTER_MEMBERS).isMultiValued());
            assertTrue(attribute(project, MASTER_MEMBERS).isUpdateable());
            assertFalse(attribute(project, PATH_WITH_NAMESPACE).isUpdateable());
            assertTrue(attribute(group, DEVELOPER_MEMBERS_LAST_EVENT).isMultiValued());
            assertFalse(attribute(group, DEVELOPER_MEMBERS_LAST_EVENT).isUpdateable());
            assertFalse(attribute(group, DEVELOPER_MEMBERS_LAST_EVENT).isCreateable());
            assertTrue(attribute(project, MASTER_MEMBERS_LAST_EVENT).isMultiValued());
            assertFalse(attribute(project, MASTER_MEMBERS_LAST_EVENT).isUpdateable());
            assertFalse(attribute(project, MASTER_MEMBERS_LAST_EVENT).isCreateable());
            // Coverage context is read-only and typed so a consumer can label the signal.
            assertEquals(Integer.class, attribute(group, DEVELOPER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS).getType());
            assertEquals(Integer.class, attribute(group, DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_SCANNED).getType());
            assertEquals(Boolean.class, attribute(group, DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_TRUNCATED).getType());
            assertFalse(attribute(group, DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_TRUNCATED).isUpdateable());
            assertEquals(Integer.class, attribute(project, MASTER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS).getType());
            assertFalse(attribute(project, MASTER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS).isUpdateable());
            for (ObjectClassInfo objectClass : Set.of(account, group, project)) {
                assertTrue(objectClass.getAttributeInfo().stream()
                        .map(AttributeInfo::getName)
                        .noneMatch(name -> name.contains("created_by")
                                || name.contains("created_at")
                                || name.contains("ownership")));
            }
            connector.dispose();
        }
    }

    @Test
    void createsAccountWithGitLabRandomPasswordAndNoCommittedPassword() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(201, "{\"id\":17,\"username\":\"martin\"}");
            GitlabRestConnector connector = connector(server);

            Uid uid = connector.create(
                    ObjectClass.ACCOUNT,
                    Set.of(
                            AttributeBuilder.build(Name.NAME, "martin"),
                            AttributeBuilder.build(NAME, "Martin Demo"),
                            AttributeBuilder.build("email", "martin@example.test")),
                    options());

            assertEquals("17", uid.getUidValue());
            JSONObject body = new JSONObject(server.request(0).body());
            assertEquals("martin", body.getString("username"));
            assertTrue(body.getBoolean("force_random_password"));
            assertFalse(body.has("password"));
            assertTrue(body.getBoolean("skip_confirmation"));
            connector.dispose();
        }
    }

    @Test
    void doesNotExposeDeleteAndRejectsTopologyCreateWithoutApiCalls() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            GitlabRestConnector connector = connector(server);

            assertFalse(DeleteOp.class.isAssignableFrom(GitlabRestConnector.class));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> connector.create(ObjectClass.GROUP, Set.of(), options()));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> connector.create(new ObjectClass(PROJECT_OBJECT_CLASS), Set.of(), options()));
            assertTrue(server.requests().isEmpty());
            connector.dispose();
        }
    }

    @Test
    void resolvesGroupByExactFullPathAndReturnsDeveloperMembers() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(
                    200,
                    "{\"id\":42,\"name\":\"easyIGA Demo\",\"path\":\"easyiga-demo\","
                            + "\"full_path\":\"easyiga-demo\",\"web_url\":\"http://gitlab/easyiga-demo\"}");
            server.enqueue(
                    200,
                    "[{\"id\":7,\"username\":\"martin\",\"access_level\":30}]",
                    Map.of("X-Next-Page", ""));
            server.enqueue(200, "[]", Map.of("X-Next-Page", ""));
            GitlabRestConnector connector = connector(server);
            ArrayList<ConnectorObject> results = new ArrayList<>();

            connector.executeQuery(
                    ObjectClass.GROUP,
                    new EqualsFilter(AttributeBuilder.build(FULL_PATH, "easyiga-demo")),
                    object -> {
                        results.add(object);
                        return true;
                    },
                    options());

            assertEquals(1, results.size());
            assertEquals("42", results.get(0).getUid().getUidValue());
            assertEquals(
                    Set.of("7"),
                    Set.copyOf(results.get(0).getAttributeByName(DEVELOPER_MEMBERS).getValue()));
            assertTrue(results.get(0).getAttributeByName(DEVELOPER_MEMBERS_LAST_EVENT).getValue().isEmpty());
            assertEquals(0, results.get(0).getAttributeByName(DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_SCANNED).getValue().get(0));
            assertEquals(false, results.get(0).getAttributeByName(DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_TRUNCATED).getValue().get(0));
            assertEquals(90, results.get(0).getAttributeByName(DEVELOPER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS).getValue().get(0));
            assertEquals("/api/v4/groups/easyiga-demo", server.request(0).rawPath());
            assertEquals("/api/v4/groups/42/members", server.request(1).rawPath());
            assertEquals("/api/v4/groups/42/projects", server.request(2).rawPath());
            connector.dispose();
        }
    }

    @Test
    void reportsMostRecentInWindowEventPerMaintainerOnAProject() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(
                    200,
                    "{\"id\":99,\"name\":\"easyIGA Core\",\"path\":\"easyiga-core\","
                            + "\"path_with_namespace\":\"easyiga-demo/easyiga-core\"}");
            server.enqueue(
                    200,
                    "[{\"id\":7,\"username\":\"martin\",\"access_level\":40},"
                            + "{\"id\":8,\"username\":\"alena\",\"access_level\":40}]",
                    Map.of("X-Next-Page", ""));
            server.enqueue(
                    200,
                    "[{\"id\":1,\"author_id\":7,\"created_at\":\"2026-08-01T10:00:00.000Z\"},"
                            + "{\"id\":2,\"author_id\":7,\"created_at\":\"2026-09-01T10:00:00.000Z\"},"
                            + "{\"id\":3,\"author_id\":9,\"created_at\":\"2026-09-02T10:00:00.000Z\"}]",
                    Map.of("X-Next-Page", ""));
            GitlabRestConnector connector = connector(server);
            ArrayList<ConnectorObject> results = new ArrayList<>();

            connector.executeQuery(
                    new ObjectClass(PROJECT_OBJECT_CLASS),
                    new EqualsFilter(AttributeBuilder.build(Uid.NAME, "99")),
                    object -> {
                        results.add(object);
                        return true;
                    },
                    options());

            assertEquals(1, results.size());
            Set<String> pairs = Set.copyOf(
                    results.get(0).getAttributeByName(MASTER_MEMBERS_LAST_EVENT).getValue().stream()
                            .map(String.class::cast)
                            .toList());
            // martin (7): the later of two in-window events; alena (8): no event at all,
            // so she is simply absent; author 9 is not a Maintainer and is ignored.
            assertEquals(Set.of("7|2026-09-01T10:00:00Z"), pairs);
            assertEquals("/api/v4/projects/99/events", server.request(2).rawPath());
            connector.dispose();
        }
    }

    @Test
    void boundsDeveloperLastEventScanToConfiguredMaxProjectsPerGroup() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(
                    200,
                    "{\"id\":42,\"name\":\"easyIGA Demo\",\"full_path\":\"easyiga-demo\"}");
            server.enqueue(
                    200,
                    "[{\"id\":7,\"username\":\"martin\",\"access_level\":30}]",
                    Map.of("X-Next-Page", ""));
            server.enqueue(
                    200,
                    "[{\"id\":101},{\"id\":102}]",
                    Map.of("X-Next-Page", "2"));
            server.enqueue(
                    200,
                    "[{\"id\":1,\"author_id\":7,\"created_at\":\"2026-09-01T10:00:00.000Z\"}]",
                    Map.of("X-Next-Page", ""));
            GitlabRestConfiguration configuration = configuration(server);
            configuration.setMaxProjectsPerGroup(1);
            GitlabRestConnector connector = new GitlabRestConnector(TEST_CLOCK);
            connector.init(configuration);
            ArrayList<ConnectorObject> results = new ArrayList<>();

            connector.executeQuery(
                    ObjectClass.GROUP,
                    new EqualsFilter(AttributeBuilder.build(Uid.NAME, "42")),
                    object -> {
                        results.add(object);
                        return true;
                    },
                    options());

            assertEquals(1, results.size());
            // Only the first (bounded) project's events were fetched, never project 102's.
            assertEquals(4, server.requests().size());
            assertEquals("/api/v4/groups/42/projects", server.request(2).rawPath());
            assertEquals("/api/v4/projects/101/events", server.request(3).rawPath());
            assertEquals(
                    Set.of("7|2026-09-01T10:00:00Z"),
                    Set.copyOf(
                            results.get(0).getAttributeByName(DEVELOPER_MEMBERS_LAST_EVENT).getValue().stream()
                                    .map(String.class::cast)
                                    .toList()));
            // The listing returned bound + 1 projects, so the scan reports itself as truncated.
            assertEquals(1, results.get(0).getAttributeByName(DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_SCANNED).getValue().get(0));
            assertEquals(true, results.get(0).getAttributeByName(DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_TRUNCATED).getValue().get(0));
            connector.dispose();
        }
    }

    @Test
    void groupScanWithinBoundReportsCompleteCoverage() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(
                    200,
                    "{\"id\":42,\"name\":\"easyIGA Demo\",\"full_path\":\"easyiga-demo\"}");
            server.enqueue(
                    200,
                    "[{\"id\":7,\"username\":\"martin\",\"access_level\":30}]",
                    Map.of("X-Next-Page", ""));
            server.enqueue(200, "[{\"id\":101},{\"id\":102}]", Map.of("X-Next-Page", ""));
            server.enqueue(200, "[]", Map.of("X-Next-Page", ""));
            server.enqueue(200, "[]", Map.of("X-Next-Page", ""));
            GitlabRestConfiguration configuration = configuration(server);
            configuration.setMaxProjectsPerGroup(2);
            configuration.setEventLookbackDays(30);
            GitlabRestConnector connector = new GitlabRestConnector(TEST_CLOCK);
            connector.init(configuration);
            ArrayList<ConnectorObject> results = new ArrayList<>();

            connector.executeQuery(
                    ObjectClass.GROUP,
                    new EqualsFilter(AttributeBuilder.build(Uid.NAME, "42")),
                    object -> {
                        results.add(object);
                        return true;
                    },
                    options());

            assertEquals(1, results.size());
            assertEquals(5, server.requests().size());
            assertEquals("/api/v4/projects/101/events", server.request(3).rawPath());
            assertEquals("/api/v4/projects/102/events", server.request(4).rawPath());
            assertEquals(2, results.get(0).getAttributeByName(DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_SCANNED).getValue().get(0));
            assertEquals(false, results.get(0).getAttributeByName(DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_TRUNCATED).getValue().get(0));
            assertEquals(30, results.get(0).getAttributeByName(DEVELOPER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS).getValue().get(0));
            connector.dispose();
        }
    }

    @Test
    void groupWithoutDevelopersSkipsProjectListingAndReportsZeroCoverage() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(
                    200,
                    "{\"id\":42,\"name\":\"easyIGA Demo\",\"full_path\":\"easyiga-demo\"}");
            server.enqueue(200, "[]", Map.of("X-Next-Page", ""));
            GitlabRestConnector connector = connector(server);
            ArrayList<ConnectorObject> results = new ArrayList<>();

            connector.executeQuery(
                    ObjectClass.GROUP,
                    new EqualsFilter(AttributeBuilder.build(Uid.NAME, "42")),
                    object -> {
                        results.add(object);
                        return true;
                    },
                    options());

            assertEquals(2, server.requests().size());
            assertEquals(0, results.get(0).getAttributeByName(DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_SCANNED).getValue().get(0));
            assertEquals(false, results.get(0).getAttributeByName(DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_TRUNCATED).getValue().get(0));
            connector.dispose();
        }
    }

    @Test
    void refetchesAccountUsingMidPointUidAndNameConjunction() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(
                    200,
                    "{\"id\":17,\"username\":\"martin\",\"name\":\"Martin Demo\","
                            + "\"email\":\"martin@example.test\",\"state\":\"active\"}");
            GitlabRestConnector connector = connector(server);
            ArrayList<ConnectorObject> results = new ArrayList<>();

            connector.executeQuery(
                    ObjectClass.ACCOUNT,
                    FilterBuilder.and(
                            FilterBuilder.equalTo(AttributeBuilder.build(Uid.NAME, "17")),
                            FilterBuilder.equalTo(AttributeBuilder.build(Name.NAME, "martin"))),
                    object -> {
                        results.add(object);
                        return true;
                    },
                    options());

            assertEquals(1, results.size());
            assertEquals("17", results.get(0).getUid().getUidValue());
            assertEquals("martin", results.get(0).getName().getNameValue());
            assertEquals(1, server.requests().size());
            assertEquals("/api/v4/users/17", server.request(0).rawPath());
            connector.dispose();
        }
    }

    @Test
    void validatesEveryPredicateAfterDirectUidLookup() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(
                    200,
                    "{\"id\":17,\"username\":\"martin\",\"name\":\"Martin Demo\","
                            + "\"email\":\"martin@example.test\",\"state\":\"active\"}");
            GitlabRestConnector connector = connector(server);
            ArrayList<ConnectorObject> results = new ArrayList<>();

            connector.executeQuery(
                    ObjectClass.ACCOUNT,
                    FilterBuilder.and(
                            FilterBuilder.equalTo(AttributeBuilder.build(Uid.NAME, "17")),
                            FilterBuilder.equalTo(AttributeBuilder.build(Name.NAME, "somebody-else"))),
                    object -> {
                        results.add(object);
                        return true;
                    },
                    options());

            assertTrue(results.isEmpty());
            assertEquals(1, server.requests().size());
            connector.dispose();
        }
    }

    @Test
    void unsupportedFilterDiagnosticsExposeTypeButNotValues() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            GitlabRestConnector connector = connector(server);

            UnsupportedOperationException error = assertThrows(
                    UnsupportedOperationException.class,
                    () -> connector.executeQuery(
                            ObjectClass.ACCOUNT,
                            FilterBuilder.or(
                                    FilterBuilder.equalTo(
                                            AttributeBuilder.build(Name.NAME, "sensitive-left-value")),
                                    FilterBuilder.equalTo(
                                            AttributeBuilder.build(Name.NAME, "sensitive-right-value"))),
                            ignored -> true,
                            options()));

            assertTrue(error.getMessage().contains("OrFilter"));
            assertFalse(error.getMessage().contains("sensitive-left-value"));
            assertFalse(error.getMessage().contains("sensitive-right-value"));
            assertTrue(server.requests().isEmpty());
            connector.dispose();
        }
    }

    @Test
    void refetchesAccountUsingMidPointSingleValueContainsAllFilter() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(
                    200,
                    "{\"id\":17,\"username\":\"martin\",\"name\":\"Martin Demo\","
                            + "\"email\":\"martin@example.test\",\"state\":\"active\"}");
            GitlabRestConnector connector = connector(server);
            ArrayList<ConnectorObject> results = new ArrayList<>();

            connector.executeQuery(
                    ObjectClass.ACCOUNT,
                    FilterBuilder.containsAllValues(AttributeBuilder.build(Uid.NAME, "17")),
                    object -> {
                        results.add(object);
                        return true;
                    },
                    options());

            assertEquals(1, results.size());
            assertEquals("17", results.get(0).getUid().getUidValue());
            assertEquals(1, server.requests().size());
            assertEquals("/api/v4/users/17", server.request(0).rawPath());
            connector.dispose();
        }
    }

    @Test
    void rejectsMultiValueContainsAllWithoutLeakingValuesOrCallingApi() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            GitlabRestConnector connector = connector(server);

            InvalidAttributeValueException error = assertThrows(
                    InvalidAttributeValueException.class,
                    () -> connector.executeQuery(
                            ObjectClass.GROUP,
                            FilterBuilder.containsAllValues(AttributeBuilder.build(
                                    DEVELOPER_MEMBERS,
                                    "sensitive-first-value",
                                    "sensitive-second-value")),
                            ignored -> true,
                            options()));

            assertTrue(error.getMessage().contains("ContainsAllValuesFilter"));
            assertFalse(error.getMessage().contains("sensitive-first-value"));
            assertFalse(error.getMessage().contains("sensitive-second-value"));
            assertTrue(server.requests().isEmpty());
            connector.dispose();
        }
    }

    @Test
    void doesNotDeleteExternallyElevatedGroupMember() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            server.enqueue(200, managedGroup());
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, packageMembership("7", 40));
            GitlabRestConnector connector = connector(server);
            AttributeDelta removeDeveloper = new AttributeDeltaBuilder()
                    .setName(DEVELOPER_MEMBERS)
                    .addValueToRemove("7")
                    .build();

            connector.updateDelta(ObjectClass.GROUP, new Uid("42"), Set.of(removeDeveloper), options());

            assertEquals(4, server.requests().size());
            assertEquals("/api/v4/users/7", server.request(0).rawPath());
            assertEquals("/api/v4/groups/easyiga-demo/members/7", server.request(3).rawPath());
            connector.dispose();
        }
    }

    @Test
    void createsManagedGroupMembershipWithTargetNativeProvenance() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            server.enqueue(200, managedGroup());
            enqueueDedicatedAdministrator(server);
            server.enqueue(404, "{\"message\":\"not found\"}");
            server.enqueue(201, packageMembership("7", 30));
            GitlabRestConnector connector = connector(server);

            connector.updateDelta(
                    ObjectClass.GROUP,
                    new Uid("42"),
                    Set.of(addDelta(DEVELOPER_MEMBERS, "7")),
                    options());

            assertEquals(5, server.requests().size());
            assertEquals("POST", server.request(4).method());
            assertEquals("/api/v4/groups/easyiga-demo/members", server.request(4).rawPath());
            connector.dispose();
        }
    }

    @Test
    void removesDeveloperWithSubresourceSafetyParameter() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            server.enqueue(200, managedGroup());
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, packageMembership("7", 30));
            server.enqueue(200, packageMembership("7", 30));
            server.enqueue(204, "");
            GitlabRestConnector connector = connector(server);
            AttributeDelta removeDeveloper = new AttributeDeltaBuilder()
                    .setName(DEVELOPER_MEMBERS)
                    .addValueToRemove("7")
                    .build();

            connector.updateDelta(ObjectClass.GROUP, new Uid("42"), Set.of(removeDeveloper), options());

            assertEquals("DELETE", server.request(5).method());
            assertEquals("skip_subresources=true", server.request(5).rawQuery());
            connector.dispose();
        }
    }

    @Test
    void membershipChangedBeforeDeleteFailsClosed() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            server.enqueue(200, managedGroup());
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, packageMembership("7", 30));
            server.enqueue(200, externalMembership("7", 30));
            GitlabRestConnector connector = connector(server);

            ConnectorIOException error = assertThrows(
                    ConnectorIOException.class,
                    () -> connector.updateDelta(
                            ObjectClass.GROUP,
                            new Uid("42"),
                            Set.of(removeDelta(DEVELOPER_MEMBERS, "7")),
                            options()));

            assertEquals(
                    "GitLab membership changed during cleanup; deletion refused",
                    error.getMessage());
            assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())));
            connector.dispose();
        }
    }

    @Test
    void preservesExactExternalGroupMembership() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            server.enqueue(200, managedGroup());
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, externalMembership("7", 30));
            GitlabRestConnector connector = connector(server);

            connector.updateDelta(
                    ObjectClass.GROUP,
                    new Uid("42"),
                    Set.of(removeDelta(DEVELOPER_MEMBERS, "7")),
                    options());

            assertEquals(4, server.requests().size());
            assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())));
            connector.dispose();
        }
    }

    @Test
    void preservesStrongerExternalProjectMembership() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            server.enqueue(200, managedProject());
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, externalMembership("7", 50));
            GitlabRestConnector connector = connector(server);

            connector.updateDelta(
                    new ObjectClass(PROJECT_OBJECT_CLASS),
                    new Uid("99"),
                    Set.of(removeDelta(MASTER_MEMBERS, "7")),
                    options());

            assertEquals(4, server.requests().size());
            assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())));
            connector.dispose();
        }
    }

    @Test
    void repeatedPackageMembershipRemovalIsIdempotent() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            server.enqueue(200, managedGroup());
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, packageMembership("7", 30));
            server.enqueue(200, packageMembership("7", 30));
            server.enqueue(204, "");

            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            server.enqueue(200, managedGroup());
            enqueueDedicatedAdministrator(server);
            server.enqueue(404, "{\"message\":\"not found\"}");
            GitlabRestConnector connector = connector(server);
            AttributeDelta removeDeveloper = removeDelta(DEVELOPER_MEMBERS, "7");

            connector.updateDelta(
                    ObjectClass.GROUP,
                    new Uid("42"),
                    Set.of(removeDeveloper),
                    options());
            connector.updateDelta(
                    ObjectClass.GROUP,
                    new Uid("42"),
                    Set.of(removeDeveloper),
                    options());

            assertEquals(
                    1,
                    server.requests().stream()
                            .filter(request -> "DELETE".equals(request.method()))
                            .count());
            connector.dispose();
        }
    }

    @Test
    void rejectsMembershipMutationOutsideConfiguredBusinessPath() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            server.enqueue(
                    200,
                    "{\"id\":42,\"full_path\":\"easyiga-demo-unmanaged\"}");
            GitlabRestConnector connector = connector(server);

            assertThrows(
                    PermissionDeniedException.class,
                    () -> connector.updateDelta(
                            ObjectClass.GROUP,
                            new Uid("42"),
                            Set.of(removeDelta(DEVELOPER_MEMBERS, "7")),
                            options()));

            assertEquals(2, server.requests().size());
            assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())));
            connector.dispose();
        }
    }

    @Test
    void restartDoesNotClaimExactExternalMembership() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
                server.enqueue(200, managedGroup());
                enqueueDedicatedAdministrator(server);
                server.enqueue(200, externalMembership("7", 30));

                GitlabRestConnector connector = connector(server);
                connector.updateDelta(
                        ObjectClass.GROUP,
                        new Uid("42"),
                        Set.of(removeDelta(DEVELOPER_MEMBERS, "7")),
                        options());
                connector.dispose();
            }

            assertEquals(8, server.requests().size());
            assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())));
        }
    }

    @Test
    void missingAccountMakesMembershipRemovalAnIdempotentNoOp() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(404, "{\"message\":\"not found\"}");
            GitlabRestConnector connector = connector(server);
            AttributeDelta removeDeveloper = new AttributeDeltaBuilder()
                    .setName(DEVELOPER_MEMBERS)
                    .addValueToRemove("404")
                    .build();

            connector.updateDelta(ObjectClass.GROUP, new Uid("42"), Set.of(removeDeveloper), options());

            assertEquals(1, server.requests().size());
            assertEquals("/api/v4/users/404", server.request(0).rawPath());
            connector.dispose();
        }
    }

    @Test
    void rejectsInvalidMembershipDeltaNameBeforeAnyApiEffect() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            GitlabRestConnector connector = connector(server);
            AttributeDelta allowed = new AttributeDeltaBuilder()
                    .setName(DEVELOPER_MEMBERS)
                    .addValueToAdd("7")
                    .build();
            AttributeDelta invalid = new AttributeDeltaBuilder()
                    .setName(NAME)
                    .addValueToReplace("forbidden")
                    .build();
            Set<AttributeDelta> ordered = new LinkedHashSet<>();
            ordered.add(allowed);
            ordered.add(invalid);

            assertThrows(
                    PermissionDeniedException.class,
                    () -> connector.updateDelta(ObjectClass.GROUP, new Uid("42"), ordered, options()));

            assertTrue(server.requests().isEmpty());
            connector.dispose();
        }
    }

    @Test
    void rejectsAdministrativeIdentityMembershipMutationBeforeRoleLookup() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":1,\"username\":\"root\"}");
            GitlabRestConnector connector = connector(server);
            AttributeDelta addDeveloper = new AttributeDeltaBuilder()
                    .setName(DEVELOPER_MEMBERS)
                    .addValueToAdd("1")
                    .build();

            assertThrows(
                    PermissionDeniedException.class,
                    () -> connector.updateDelta(ObjectClass.GROUP, new Uid("42"), Set.of(addDeveloper), options()));
            assertEquals(1, server.requests().size());
            assertEquals("/api/v4/users/1", server.request(0).rawPath());
            connector.dispose();
        }
    }

    @Test
    void rejectsIntegrationAdministratorMembershipMutationBeforeRoleLookup() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":12,\"username\":\"easyiga-midpoint\"}");
            GitlabRestConnector connector = connector(server);
            AttributeDelta addMaintainer = new AttributeDeltaBuilder()
                    .setName(MASTER_MEMBERS)
                    .addValueToAdd("12")
                    .build();

            assertThrows(
                    PermissionDeniedException.class,
                    () -> connector.updateDelta(
                            new ObjectClass(PROJECT_OBJECT_CLASS),
                            new Uid("99"),
                            Set.of(addMaintainer),
                            options()));
            assertEquals(1, server.requests().size());
            assertEquals("/api/v4/users/12", server.request(0).rawPath());
            connector.dispose();
        }
    }

    @Test
    void membershipIdentityLookupFailsClosedWhenUsernameIsMissing() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7}");
            GitlabRestConnector connector = connector(server);
            AttributeDelta addDeveloper = new AttributeDeltaBuilder()
                    .setName(DEVELOPER_MEMBERS)
                    .addValueToAdd("7")
                    .build();

            assertThrows(
                    ConnectorIOException.class,
                    () -> connector.updateDelta(ObjectClass.GROUP, new Uid("42"), Set.of(addDeveloper), options()));
            assertEquals(1, server.requests().size());
            connector.dispose();
        }
    }

    @Test
    void connectionTestAcceptsDedicatedApiTokenAtThirtyDayBoundary() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, tokenResponse(
                    "easyiga-midpoint-connector",
                    TEST_DATE.plusDays(30).toString(),
                    "api"));
            GitlabRestConnector connector = connector(server);

            connector.test();

            assertEquals(2, server.requests().size());
            assertEquals("/api/v4/user", server.request(0).rawPath());
            assertEquals("/api/v4/personal_access_tokens/self", server.request(1).rawPath());
            connector.dispose();
        }
    }

    @Test
    void connectionTestHonoursConfiguredIntegrationIdentity() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":12,\"username\":\"midpoint-svc\",\"is_admin\":true}");
            server.enqueue(200, tokenResponse("midpoint-svc-token", TEST_DATE.plusDays(7).toString(), "api"));
            GitlabRestConfiguration configuration = configuration(server);
            configuration.setIntegrationUsername("midpoint-svc");
            configuration.setIntegrationTokenName("midpoint-svc-token");
            GitlabRestConnector connector = new GitlabRestConnector(TEST_CLOCK);
            connector.init(configuration);

            connector.test();

            // The default easyIGA identity is no longer accepted once another one is configured.
            server.enqueue(200, "{\"id\":12,\"username\":\"easyiga-midpoint\",\"is_admin\":true}");
            assertThrows(PermissionDeniedException.class, connector::test);
            connector.dispose();
        }
    }

    @Test
    void connectionTestRejectsRootEvenWithAdministratorToken() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":1,\"username\":\"root\",\"is_admin\":true}");
            GitlabRestConnector connector = connector(server);

            assertThrows(PermissionDeniedException.class, connector::test);
            assertEquals(1, server.requests().size());
            connector.dispose();
        }
    }

    @Test
    void connectionTestRejectsDedicatedAdministratorWithoutApiScope() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, tokenResponse(
                    "easyiga-midpoint-connector",
                    TEST_DATE.plusDays(7).toString(),
                    "read_api"));
            GitlabRestConnector connector = connector(server);

            assertThrows(PermissionDeniedException.class, connector::test);
            assertEquals(2, server.requests().size());
            connector.dispose();
        }
    }

    @Test
    void connectionTestRejectsWrongTokenNameWithoutEchoingIt() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            String wrongName = "sensitive-unapproved-token-name";
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, tokenResponse(
                    wrongName,
                    TEST_DATE.plusDays(7).toString(),
                    "api"));
            GitlabRestConnector connector = connector(server);

            PermissionDeniedException error =
                    assertThrows(PermissionDeniedException.class, connector::test);

            assertFalse(error.getMessage().contains(wrongName));
            assertEquals(2, server.requests().size());
            connector.dispose();
        }
    }

    @Test
    void connectionTestRejectsMissingTokenExpiration() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, tokenResponse(
                    "easyiga-midpoint-connector",
                    null,
                    "api"));
            GitlabRestConnector connector = connector(server);

            assertThrows(PermissionDeniedException.class, connector::test);

            assertEquals(2, server.requests().size());
            connector.dispose();
        }
    }

    @Test
    void connectionTestRejectsExpirationThatIsNotStrictlyFuture() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, tokenResponse(
                    "easyiga-midpoint-connector",
                    TEST_DATE.toString(),
                    "api"));
            GitlabRestConnector connector = connector(server);

            assertThrows(PermissionDeniedException.class, connector::test);

            assertEquals(2, server.requests().size());
            connector.dispose();
        }
    }

    @Test
    void connectionTestRejectsMalformedExpirationWithoutEchoingIt() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            String malformedExpiration = "sensitive-not-an-iso-date";
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, tokenResponse(
                    "easyiga-midpoint-connector",
                    malformedExpiration,
                    "api"));
            GitlabRestConnector connector = connector(server);

            PermissionDeniedException error =
                    assertThrows(PermissionDeniedException.class, connector::test);

            assertFalse(error.getMessage().contains(malformedExpiration));
            assertEquals(2, server.requests().size());
            connector.dispose();
        }
    }

    @Test
    void connectionTestRejectsTokenWithMoreThanThirtyDaysRemaining() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, tokenResponse(
                    "easyiga-midpoint-connector",
                    TEST_DATE.plusDays(31).toString(),
                    "api"));
            GitlabRestConnector connector = connector(server);

            assertThrows(PermissionDeniedException.class, connector::test);

            assertEquals(2, server.requests().size());
            connector.dispose();
        }
    }

    @Test
    void connectionTestRequiresInstanceAdministrator() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(
                    200,
                    "{\"id\":12,\"username\":\"easyiga-midpoint\",\"is_admin\":false}");
            GitlabRestConnector connector = connector(server);

            assertThrows(PermissionDeniedException.class, connector::test);
            assertEquals(1, server.requests().size());
            connector.dispose();
        }
    }

    @Test
    void protectsRootFromAccountUpdate() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":1,\"username\":\"root\"}");
            GitlabRestConnector connector = connector(server);
            AttributeDelta disable = new AttributeDeltaBuilder()
                    .setName(org.identityconnectors.framework.common.objects.OperationalAttributes.ENABLE_NAME)
                    .addValueToReplace(false)
                    .build();

            assertThrows(
                    PermissionDeniedException.class,
                    () -> connector.updateDelta(ObjectClass.ACCOUNT, new Uid("1"), Set.of(disable), options()));
            assertEquals(1, server.requests().size());
            connector.dispose();
        }
    }

    @Test
    void protectsIntegrationAdministratorFromBlock() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":12,\"username\":\"easyiga-midpoint\"}");
            GitlabRestConnector connector = connector(server);
            AttributeDelta disable = new AttributeDeltaBuilder()
                    .setName(org.identityconnectors.framework.common.objects.OperationalAttributes.ENABLE_NAME)
                    .addValueToReplace(false)
                    .build();

            assertThrows(
                    PermissionDeniedException.class,
                    () -> connector.updateDelta(ObjectClass.ACCOUNT, new Uid("12"), Set.of(disable), options()));

            assertEquals(1, server.requests().size());
            assertEquals("/api/v4/users/12", server.request(0).rawPath());
            connector.dispose();
        }
    }

    @Test
    void accountDisableRemovesOnlyConfiguredPackageMembershipsBeforeBlocking()
            throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, packageMembership("7", 30));
            server.enqueue(200, packageMembership("7", 30));
            server.enqueue(204, "");
            server.enqueue(200, packageMembership("7", 40));
            server.enqueue(200, packageMembership("7", 40));
            server.enqueue(204, "");
            server.enqueue(201, "{}");

            GitlabRestConfiguration configuration = configuration(server);
            configuration.setDeprovisionDeveloperGroupPath("easyiga-demo");
            configuration.setDeprovisionMaintainerProjectPath(
                    "easyiga-demo/easyiga-core");
            GitlabRestConnector connector = new GitlabRestConnector(TEST_CLOCK);
            connector.init(configuration);
            AttributeDelta disable = new AttributeDeltaBuilder()
                    .setName(
                            org.identityconnectors.framework.common.objects
                                    .OperationalAttributes.ENABLE_NAME)
                    .addValueToReplace(false)
                    .build();

            connector.updateDelta(
                    ObjectClass.ACCOUNT, new Uid("7"), Set.of(disable), options());

            assertEquals(9, server.requests().size());
            assertEquals(
                    "/api/v4/groups/easyiga-demo/members/7",
                    server.request(2).rawPath());
            assertEquals("DELETE", server.request(4).method());
            assertEquals("skip_subresources=true", server.request(4).rawQuery());
            assertEquals(
                    "/api/v4/projects/easyiga-demo%2Feasyiga-core/members/7",
                    server.request(5).rawPath());
            assertEquals("DELETE", server.request(7).method());
            assertEquals("/api/v4/users/7/block", server.request(8).rawPath());
            connector.dispose();
        }
    }

    @Test
    void accountDisablePreservesExternalGroupAndRemovesPackageProject()
            throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, externalMembership("7", 30));
            server.enqueue(200, packageMembership("7", 40));
            server.enqueue(200, packageMembership("7", 40));
            server.enqueue(204, "");
            server.enqueue(201, "{}");
            GitlabRestConnector connector = connector(server);

            connector.updateDelta(
                    ObjectClass.ACCOUNT,
                    new Uid("7"),
                    Set.of(disableDelta()),
                    options());

            assertEquals(7, server.requests().size());
            assertEquals(
                    "/api/v4/projects/easyiga-demo%2Feasyiga-core/members/7",
                    server.request(5).rawPath());
            assertEquals("DELETE", server.request(5).method());
            assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())
                            && request.rawPath().startsWith("/api/v4/groups/")));
            assertEquals("/api/v4/users/7/block", server.request(6).rawPath());
            connector.dispose();
        }
    }

    @Test
    void accountDisableRemovesPackageGroupAndPreservesExternalProject()
            throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, packageMembership("7", 30));
            server.enqueue(200, packageMembership("7", 30));
            server.enqueue(204, "");
            server.enqueue(200, externalMembership("7", 40));
            server.enqueue(201, "{}");
            GitlabRestConnector connector = connector(server);

            connector.updateDelta(
                    ObjectClass.ACCOUNT,
                    new Uid("7"),
                    Set.of(disableDelta()),
                    options());

            assertEquals(7, server.requests().size());
            assertEquals("/api/v4/groups/easyiga-demo/members/7", server.request(4).rawPath());
            assertEquals("DELETE", server.request(4).method());
            assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())
                            && request.rawPath().startsWith("/api/v4/projects/")));
            assertEquals("/api/v4/users/7/block", server.request(6).rawPath());
            connector.dispose();
        }
    }

    @Test
    void accountDisablePreservesUnknownLegacyMemberships() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            enqueueDedicatedAdministrator(server);
            server.enqueue(200, "{\"id\":7,\"access_level\":30}");
            server.enqueue(404, "{\"message\":\"not found\"}");
            server.enqueue(201, "{}");
            GitlabRestConnector connector = connector(server);

            connector.updateDelta(
                    ObjectClass.ACCOUNT,
                    new Uid("7"),
                    Set.of(disableDelta()),
                    options());

            assertEquals(5, server.requests().size());
            assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())));
            assertEquals("/api/v4/users/7/block", server.request(4).rawPath());
            connector.dispose();
        }
    }

    @Test
    void accountDisableFailsClosedOnConflictingCreatorIdentity() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            server.enqueue(200, "{\"id\":7,\"username\":\"martin\"}");
            enqueueDedicatedAdministrator(server);
            server.enqueue(
                    200,
                    membership("7", 30, "12", "root", JSONObject.NULL));
            GitlabRestConnector connector = connector(server);

            assertThrows(
                    ConnectorIOException.class,
                    () -> connector.updateDelta(
                            ObjectClass.ACCOUNT,
                            new Uid("7"),
                            Set.of(disableDelta()),
                            options()));

            assertEquals(3, server.requests().size());
            assertTrue(server.requests().stream()
                    .noneMatch(request -> "DELETE".equals(request.method())
                            || request.rawPath().endsWith("/block")));
            connector.dispose();
        }
    }

    @Test
    void disposalDoesNotDestroyConnIdManagedConfiguration() throws Exception {
        try (GitlabTestServer server = new GitlabTestServer()) {
            GitlabRestConfiguration configuration = configuration(server);
            GitlabRestConnector first = new GitlabRestConnector();
            first.init(configuration);
            first.schema();
            first.dispose();

            GitlabRestConnector second = new GitlabRestConnector();
            second.init(configuration);

            assertEquals(server.baseUrl(), configuration.getBaseUrl());
            second.dispose();
        }
    }

    private static AttributeInfo attribute(ObjectClassInfo objectClass, String name) {
        return objectClass.getAttributeInfo().stream()
                .filter(attribute -> name.equals(attribute.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static GitlabRestConnector connector(GitlabTestServer server) {
        GitlabRestConnector connector = new GitlabRestConnector(TEST_CLOCK);
        connector.init(configuration(server));
        return connector;
    }

    private static void enqueueDedicatedAdministrator(GitlabTestServer server) {
        server.enqueue(
                200,
                "{\"id\":12,\"username\":\"easyiga-midpoint\",\"is_admin\":true}");
    }

    private static String tokenResponse(String name, String expiration, String... scopes) {
        return new JSONObject()
                .put("id", 91)
                .put("user_id", 12)
                .put("active", true)
                .put("revoked", false)
                .put("name", name)
                .put("expires_at", expiration == null ? JSONObject.NULL : expiration)
                .put("scopes", Arrays.asList(scopes))
                .toString();
    }

    private static String managedGroup() {
        return new JSONObject()
                .put("id", 42)
                .put("full_path", "easyiga-demo")
                .toString();
    }

    private static String managedProject() {
        return new JSONObject()
                .put("id", 99)
                .put("path_with_namespace", "easyiga-demo/easyiga-core")
                .toString();
    }

    private static String packageMembership(String memberId, int accessLevel) {
        return membership(
                memberId, accessLevel, "12", "easyiga-midpoint", JSONObject.NULL);
    }

    private static String externalMembership(String memberId, int accessLevel) {
        return membership(
                memberId, accessLevel, "1", "root", JSONObject.NULL);
    }

    private static String membership(
            String memberId,
            int accessLevel,
            String creatorId,
            String creatorUsername,
            Object expiresAt) {
        return new JSONObject()
                .put("id", Long.parseLong(memberId))
                .put("access_level", accessLevel)
                .put("created_at", "2026-07-29T10:00:00Z")
                .put("created_by", new JSONObject()
                        .put("id", Long.parseLong(creatorId))
                        .put("username", creatorUsername))
                .put("expires_at", expiresAt)
                .toString();
    }

    private static AttributeDelta removeDelta(String name, String memberId) {
        return new AttributeDeltaBuilder()
                .setName(name)
                .addValueToRemove(memberId)
                .build();
    }

    private static AttributeDelta addDelta(String name, String memberId) {
        return new AttributeDeltaBuilder()
                .setName(name)
                .addValueToAdd(memberId)
                .build();
    }

    private static AttributeDelta disableDelta() {
        return new AttributeDeltaBuilder()
                .setName(
                        org.identityconnectors.framework.common.objects
                                .OperationalAttributes.ENABLE_NAME)
                .addValueToReplace(false)
                .build();
    }

    private static GitlabRestConfiguration configuration(GitlabTestServer server) {
        GitlabRestConfiguration configuration = new GitlabRestConfiguration();
        configuration.setBaseUrl(server.baseUrl());
        configuration.setPrivateToken(new GuardedString("test-token".toCharArray()));
        configuration.setConnectTimeoutMillis(2_000);
        configuration.setRequestTimeoutMillis(2_000);
        configuration.setDeprovisionDeveloperGroupPath("easyiga-demo");
        configuration.setDeprovisionMaintainerProjectPath(
                "easyiga-demo/easyiga-core");
        return configuration;
    }

    private static OperationOptions options() {
        return new OperationOptions(Map.of());
    }
}

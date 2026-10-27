/*
 * Copyright 2016-2017 Evolveum
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.AFTER;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.AUTHOR_ID;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.CREATED_AT;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.DESCRIPTION;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.DEVELOPER_ACCESS_LEVEL;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.DEVELOPER_MEMBERS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.DEVELOPER_MEMBERS_LAST_EVENT;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.DEVELOPER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_SCANNED;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_TRUNCATED;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.EMAIL;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.FULL_PATH;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.GROUPS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.ID;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.MAINTAINER_ACCESS_LEVEL;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.MASTER_MEMBERS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.MASTER_MEMBERS_LAST_EVENT;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.MASTER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.MAX_INTEGRATION_TOKEN_REMAINING_DAYS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.NAME;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.PATH;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.PATH_WITH_NAMESPACE;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.PROJECTS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.PROJECT_OBJECT_CLASS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.ROOT_USERNAME;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.STATE;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.USERNAME;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.USERS;
import static com.evolveum.polygon.connector.gitlab.rest.GitlabConstants.WEB_URL;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.DirectMembership;
import com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.IntegrationActor;
import com.evolveum.polygon.connector.gitlab.rest.GitlabMembershipProvenance.Ownership;

import org.identityconnectors.common.CollectionUtil;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.InvalidPasswordException;
import org.identityconnectors.framework.common.exceptions.OperationTimeoutException;
import org.identityconnectors.framework.common.exceptions.PermissionDeniedException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeDelta;
import org.identityconnectors.framework.common.objects.AttributeInfoBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfoBuilder;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.SchemaBuilder;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.AndFilter;
import org.identityconnectors.framework.common.objects.filter.ContainsAllValuesFilter;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.Connector;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.operations.CreateOp;
import org.identityconnectors.framework.spi.operations.SchemaOp;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.TestOp;
import org.identityconnectors.framework.spi.operations.UpdateDeltaOp;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Safety-focused GitLab API v4 connector for the easyIGA local demo.
 */
@ConnectorClass(displayNameKey = "connector.gitlab.rest.display", configurationClass = GitlabRestConfiguration.class)
public final class GitlabRestConnector
        implements Connector, TestOp, SchemaOp, CreateOp, UpdateDeltaOp, SearchOp<Filter> {

    private GitlabRestConfiguration configuration;
    private GitlabApiClient api;
    private Schema schema;
    private final Clock clock;

    public GitlabRestConnector() {
        this(Clock.systemUTC());
    }

    GitlabRestConnector(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void init(Configuration configuration) {
        if (!(configuration instanceof GitlabRestConfiguration gitlabConfiguration)) {
            throw new IllegalArgumentException("Expected GitlabRestConfiguration");
        }
        gitlabConfiguration.validate();
        this.configuration = gitlabConfiguration;
        this.api = new GitlabApiClient(gitlabConfiguration);
    }

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public void dispose() {
        api = null;
        schema = null;
        configuration = null;
    }

    @Override
    public void test() {
        IntegrationActor actor = integrationActor();

        JSONObject token = translated(() -> api.getJson("/personal_access_tokens/self"));
        if (!token.optBoolean("active", false) || token.optBoolean("revoked", true)) {
            throw new PermissionDeniedException("GitLab integration token is inactive or revoked");
        }
        if (!actor.id().equals(requiredJsonIdentifier(token, "user_id"))) {
            throw new PermissionDeniedException(
                    "GitLab integration token identity does not match the authenticated administrator");
        }
        if (!configuration.effectiveIntegrationTokenName().equals(token.optString(NAME))) {
            throw new PermissionDeniedException(
                    "GitLab integration token name does not match the required connector policy");
        }
        JSONArray scopes = token.optJSONArray("scopes");
        boolean hasApiScope = false;
        if (scopes != null) {
            for (int index = 0; index < scopes.length(); index++) {
                if ("api".equals(scopes.optString(index))) {
                    hasApiScope = true;
                    break;
                }
            }
        }
        if (!hasApiScope) {
            throw new PermissionDeniedException("GitLab integration token must have api scope");
        }
        validateTokenExpiration(token);
    }

    private void validateTokenExpiration(JSONObject token) {
        String rawExpiration = token.optString("expires_at", "");
        final LocalDate expiration;
        try {
            if (rawExpiration.isBlank()) {
                throw new DateTimeParseException("missing", "", 0);
            }
            expiration = LocalDate.parse(rawExpiration);
        } catch (DateTimeParseException e) {
            // Do not retain the parser exception: its message includes the
            // untrusted token metadata value.
            throw new PermissionDeniedException(
                    "GitLab integration token must have a valid ISO expiration date");
        }

        LocalDate today = LocalDate.now(clock);
        if (!expiration.isAfter(today)) {
            throw new PermissionDeniedException(
                    "GitLab integration token expiration must be strictly in the future");
        }
        if (expiration.isAfter(today.plusDays(MAX_INTEGRATION_TOKEN_REMAINING_DAYS))) {
            throw new PermissionDeniedException(
                    "GitLab integration token must have no more than 30 days remaining");
        }
    }

    @Override
    public Schema schema() {
        if (schema != null) {
            return schema;
        }

        SchemaBuilder builder = new SchemaBuilder(GitlabRestConnector.class);
        builder.defineObjectClass(
                accountObjectClass(),
                CreateOp.class,
                SearchOp.class,
                UpdateDeltaOp.class);
        builder.defineObjectClass(groupObjectClass(), SearchOp.class, UpdateDeltaOp.class);
        builder.defineObjectClass(projectObjectClass(), SearchOp.class, UpdateDeltaOp.class);
        schema = builder.build();
        return schema;
    }

    @Override
    public Uid create(ObjectClass objectClass, Set<Attribute> attributes, OperationOptions options) {
        requireObjectClass(objectClass);
        if (!objectClass.is(ObjectClass.ACCOUNT_NAME)) {
            throw topologyMutationRejected(objectClass, "create");
        }
        if (attributes == null) {
            throw new InvalidAttributeValueException("Account attributes are required");
        }

        String username = requiredString(attributes, Name.NAME);
        if (isProtectedUsername(username)) {
            throw new PermissionDeniedException("GitLab administrative identities are protected");
        }

        JSONObject body = new JSONObject()
                .put(USERNAME, username)
                .put(NAME, requiredString(attributes, NAME))
                .put(EMAIL, requiredString(attributes, EMAIL))
                .put("skip_confirmation", true);

        GuardedString password = single(attributes, OperationalAttributes.PASSWORD_NAME, GuardedString.class);
        if (password != null) {
            body.put("password", reveal(password));
        } else if (configuration.isForceRandomPasswordOnCreate()) {
            body.put("force_random_password", true);
        } else {
            throw new InvalidPasswordException(
                    "A password is required when forceRandomPasswordOnCreate is false");
        }

        JSONObject created = translated(() -> api.postJson(USERS, body));
        String uid = requiredId(created);
        Boolean enabled = single(attributes, OperationalAttributes.ENABLE_NAME, Boolean.class);
        if (Boolean.FALSE.equals(enabled)) {
            translatedRun(() -> api.postWithoutResult(USERS + "/" + GitlabApiClient.encodePathIdentifier(uid) + "/block"));
        }
        return new Uid(uid);
    }

    @Override
    public Set<AttributeDelta> updateDelta(
            ObjectClass objectClass,
            Uid uid,
            Set<AttributeDelta> deltas,
            OperationOptions options) {

        requireObjectClass(objectClass);
        requireUid(uid);
        if (deltas == null) {
            throw new InvalidAttributeValueException("Attribute deltas are required");
        }

        if (objectClass.is(ObjectClass.ACCOUNT_NAME)) {
            updateAccount(uid, deltas);
        } else if (objectClass.is(ObjectClass.GROUP_NAME)) {
            updateMemberships(uid, deltas, true);
        } else if (objectClass.is(PROJECT_OBJECT_CLASS)) {
            updateMemberships(uid, deltas, false);
        } else {
            throw unsupportedObjectClass(objectClass);
        }
        return Set.of();
    }

    @Override
    public FilterTranslator<Filter> createFilterTranslator(ObjectClass objectClass, OperationOptions options) {
        requireObjectClass(objectClass);
        return filter -> filter == null ? List.of() : CollectionUtil.newList(filter);
    }

    @Override
    public void executeQuery(
            ObjectClass objectClass,
            Filter query,
            ResultsHandler handler,
            OperationOptions options) {

        requireObjectClass(objectClass);
        if (handler == null) {
            throw new InvalidAttributeValueException("ResultsHandler is required");
        }
        OperationOptions effectiveOptions = options == null ? new OperationOptions(Map.of()) : options;

        List<JSONObject> objects = translated(() -> search(objectClass, query, effectiveOptions));
        for (JSONObject object : objects) {
            ConnectorObject converted = toConnectorObject(objectClass, object);
            if (!handler.handle(converted)) {
                break;
            }
        }
    }

    private List<JSONObject> search(ObjectClass objectClass, Filter query, OperationOptions options) {
        List<ExactFilter> exactFilters = parseExactFilters(query);
        if (!exactFilters.isEmpty()) {
            List<JSONObject> direct = directLookup(objectClass, exactFilters);
            if (direct != null) {
                direct.removeIf(candidate -> !matchesAll(candidate, objectClass, exactFilters));
                return applyWindow(direct, options);
            }
        }

        String path = endpoint(objectClass);
        Map<String, String> queryParameters = queryParameters(objectClass, exactFilters);
        List<JSONObject> candidates;
        if (exactFilters.isEmpty()) {
            candidates = api.listWindow(path, queryParameters, options.getPagedResultsOffset(), options.getPageSize());
        } else {
            // GitLab search is fuzzy. Fetch every candidate, then enforce exact
            // matching before applying the ConnId result window.
            candidates = api.listAll(path, queryParameters);
            candidates.removeIf(candidate -> !matchesAll(candidate, objectClass, exactFilters));
            candidates = applyWindow(candidates, options);
        }
        return candidates;
    }

    private List<JSONObject> directLookup(ObjectClass objectClass, List<ExactFilter> filters) {
        ExactFilter uid = findFilter(filters, Uid.NAME);
        if (uid != null) {
            String encoded = GitlabApiClient.encodePathIdentifier(uid.value());
            return new ArrayList<>(
                    api.getOptionalJson(endpoint(objectClass) + "/" + encoded).map(List::of).orElseGet(List::of));
        }
        ExactFilter stablePath = objectClass.is(ObjectClass.GROUP_NAME)
                ? findFilter(filters, FULL_PATH)
                : objectClass.is(PROJECT_OBJECT_CLASS)
                        ? findFilter(filters, PATH_WITH_NAMESPACE)
                        : null;
        if (stablePath != null) {
            String encoded = GitlabApiClient.encodePathIdentifier(stablePath.value());
            String endpoint = objectClass.is(ObjectClass.GROUP_NAME) ? GROUPS : PROJECTS;
            return new ArrayList<>(
                    api.getOptionalJson(endpoint + "/" + encoded).map(List::of).orElseGet(List::of));
        }
        return null;
    }

    private Map<String, String> queryParameters(ObjectClass objectClass, List<ExactFilter> filters) {
        if (filters.isEmpty()) {
            return Map.of();
        }
        ExactFilter exact = findFilter(filters, Name.NAME);
        if (exact == null && objectClass.is(ObjectClass.ACCOUNT_NAME)) {
            exact = findFilter(filters, USERNAME);
        }
        if (exact == null) {
            // Conjunctions without a GitLab narrowing parameter remain exact:
            // list all pages and validate every predicate locally.
            return Map.of();
        }
        if (objectClass.is(ObjectClass.ACCOUNT_NAME)
                && (Name.NAME.equals(exact.attribute()) || USERNAME.equals(exact.attribute()))) {
            return Map.of(USERNAME, exact.value());
        }
        if (Name.NAME.equals(exact.attribute()) || NAME.equals(exact.attribute())) {
            return Map.of("search", exact.value());
        }
        throw new UnsupportedOperationException(
                "Exact search on attribute " + exact.attribute() + " is unsupported for " + objectClass);
    }

    private boolean matchesAll(JSONObject candidate, ObjectClass objectClass, List<ExactFilter> filters) {
        return filters.stream().allMatch(exact -> matches(candidate, objectClass, exact));
    }

    private boolean matches(JSONObject candidate, ObjectClass objectClass, ExactFilter exact) {
        String jsonAttribute = exact.attribute();
        if (Uid.NAME.equals(jsonAttribute)) {
            jsonAttribute = ID;
        } else if (Name.NAME.equals(jsonAttribute)) {
            jsonAttribute = objectClass.is(ObjectClass.ACCOUNT_NAME) ? USERNAME : NAME;
        }
        return candidate.has(jsonAttribute)
                && exact.value().equals(String.valueOf(candidate.get(jsonAttribute)));
    }

    private void updateAccount(Uid uid, Set<AttributeDelta> deltas) {
        protectAdministrativeAccount(uid);
        JSONObject body = new JSONObject();
        Boolean enabled = null;

        for (AttributeDelta delta : deltas) {
            List<Object> replacement = delta.getValuesToReplace();
            if (replacement == null) {
                throw new UnsupportedOperationException(
                        "GitLab scalar account attributes require replace deltas: " + delta.getName());
            }
            Object value = singleReplacement(delta);
            String attributeName = delta.getName();
            if (Name.NAME.equals(attributeName)) {
                String username = asString(value, attributeName);
                if (isProtectedUsername(username)) {
                    throw new PermissionDeniedException("GitLab administrative identities are protected");
                }
                body.put(USERNAME, username);
            } else if (NAME.equals(attributeName) || EMAIL.equals(attributeName)) {
                body.put(attributeName, asString(value, attributeName));
            } else if (OperationalAttributes.PASSWORD_NAME.equals(attributeName)) {
                body.put("password", reveal(asType(value, GuardedString.class, attributeName)));
            } else if (OperationalAttributes.ENABLE_NAME.equals(attributeName)) {
                enabled = asType(value, Boolean.class, attributeName);
            } else {
                throw new UnsupportedOperationException(
                        "GitLab account attribute is not updateable: " + attributeName);
            }
        }

        String encodedUid = GitlabApiClient.encodePathIdentifier(uid.getUidValue());
        if (!body.isEmpty()) {
            translated(() -> api.putJson(USERS + "/" + encodedUid, body));
        }
        if (enabled != null) {
            if (!enabled) {
                removeConfiguredMembershipsOnDisable(uid);
            }
            String action = enabled ? "/unblock" : "/block";
            translatedRun(() -> api.postWithoutResult(USERS + "/" + encodedUid + action));
        }
    }

    private void removeConfiguredMembershipsOnDisable(Uid uid) {
        String groupPath = configuration.getDeprovisionDeveloperGroupPath();
        String projectPath = configuration.getDeprovisionMaintainerProjectPath();
        if (StringUtil.isBlank(groupPath) && StringUtil.isBlank(projectPath)) {
            return;
        }
        IntegrationActor actor = integrationActor();
        removeOwnedMembership(
                GROUPS + "/" + GitlabApiClient.encodePathIdentifier(groupPath),
                uid.getUidValue(),
                DEVELOPER_ACCESS_LEVEL,
                true,
                actor);
        removeOwnedMembership(
                PROJECTS + "/" + GitlabApiClient.encodePathIdentifier(projectPath),
                uid.getUidValue(),
                MAINTAINER_ACCESS_LEVEL,
                false,
                actor);
    }

    private void removeOwnedMembership(
            String entityPath,
            String userId,
            int packageAccessLevel,
            boolean group,
            IntegrationActor actor) {

        DirectMembership direct = translated(
                () -> api.directMembership(entityPath, userId)).orElse(null);
        if (direct == null) {
            return;
        }
        Ownership ownership = GitlabMembershipProvenance.classify(
                direct, actor, userId, packageAccessLevel);
        if (ownership == Ownership.CONFLICTING) {
            throw new ConnectorIOException(
                    "GitLab membership provenance conflicts with the integration identity; cleanup refused");
        }
        if (ownership == Ownership.PACKAGE_CREATED) {
            DirectMembership rechecked = translated(
                    () -> api.directMembership(entityPath, userId)).orElse(null);
            if (rechecked == null) {
                return;
            }
            if (!direct.equals(rechecked)) {
                throw new ConnectorIOException(
                        "GitLab membership changed during cleanup; deletion refused");
            }
            translatedRun(() -> api.removeDirectMember(entityPath, userId, group));
        }
    }

    private void updateMemberships(Uid uid, Set<AttributeDelta> deltas, boolean group) {
        String allowedAttribute = group ? DEVELOPER_MEMBERS : MASTER_MEMBERS;
        int accessLevel = group ? DEVELOPER_ACCESS_LEVEL : MAINTAINER_ACCESS_LEVEL;

        for (AttributeDelta delta : deltas) {
            if (!allowedAttribute.equals(delta.getName())) {
                throw new PermissionDeniedException(
                        "GitLab group/project topology attribute is not updateable: " + delta.getName());
            }
        }

        Set<String> additions = new HashSet<>();
        Set<String> removals = new HashSet<>();
        Set<String> replacement = null;
        for (AttributeDelta delta : deltas) {
            additions.addAll(stringSet(delta.getValuesToAdd(), delta.getName()));
            removals.addAll(stringSet(delta.getValuesToRemove(), delta.getName()));
            if (delta.getValuesToReplace() != null) {
                if (replacement != null) {
                    throw new InvalidAttributeValueException(
                            "Only one membership replace delta is supported per operation");
                }
                replacement = stringSet(delta.getValuesToReplace(), delta.getName());
            }
        }

        String entityPath = null;
        if (replacement != null) {
            entityPath = managedEntityPath(uid, group);
            String replacementEntityPath = entityPath;
            Set<String> current = translated(
                    () -> api.directMembersAtAccessLevel(replacementEntityPath, accessLevel));
            additions.addAll(replacement);
            additions.removeAll(current);
            removals.addAll(current);
            removals.removeAll(replacement);
        }

        // Grant/change first. If a value appears in both lists, the desired
        // grant wins and is never deleted afterward.
        removals.removeAll(additions);

        // Validate every affected identity before the first mutation. Missing
        // additions remain an error; a removal for an already deleted account
        // is an idempotent no-op.
        for (String userId : additions) {
            protectAdministrativeAccount(new Uid(userId));
        }
        Set<String> existingRemovals = new HashSet<>();
        for (String userId : removals) {
            if (protectMembershipAccountIfPresent(userId)) {
                existingRemovals.add(userId);
            }
        }

        if (additions.isEmpty() && existingRemovals.isEmpty()) {
            return;
        }
        if (entityPath == null) {
            entityPath = managedEntityPath(uid, group);
        }
        IntegrationActor actor = integrationActor();
        String managedEntityPath = entityPath;
        for (String userId : additions) {
            translatedRun(() -> api.ensureDirectMember(
                    managedEntityPath, userId, accessLevel, group, actor));
        }
        for (String userId : existingRemovals) {
            removeOwnedMembership(
                    managedEntityPath, userId, accessLevel, group, actor);
        }
    }

    private String managedEntityPath(Uid uid, boolean group) {
        String businessPath = group
                ? configuration.getDeprovisionDeveloperGroupPath()
                : configuration.getDeprovisionMaintainerProjectPath();
        if (StringUtil.isBlank(businessPath)) {
            throw new PermissionDeniedException(
                    "GitLab managed membership path is not configured");
        }

        String entityPath = (group ? GROUPS : PROJECTS) + "/"
                + GitlabApiClient.encodePathIdentifier(businessPath);
        JSONObject entity = translated(() -> api.getJson(entityPath));
        String pathAttribute = group ? FULL_PATH : PATH_WITH_NAMESPACE;
        if (!uid.getUidValue().equals(requiredId(entity))
                || !businessPath.equals(requiredJsonString(entity, pathAttribute))) {
            throw new PermissionDeniedException(
                    "GitLab membership mutation target is outside the configured managed path");
        }
        return entityPath;
    }

    private ConnectorObject toConnectorObject(ObjectClass objectClass, JSONObject value) {
        ConnectorObjectBuilder builder = new ConnectorObjectBuilder()
                .setObjectClass(objectClass)
                .setUid(requiredId(value));

        if (objectClass.is(ObjectClass.ACCOUNT_NAME)) {
            builder.setName(requiredJsonString(value, USERNAME));
            addIfPresent(builder, NAME, value);
            addIfPresent(builder, EMAIL, value);
            addIfPresent(builder, STATE, value);
            addIfPresent(builder, WEB_URL, value);
            builder.addAttribute(
                    OperationalAttributes.ENABLE_NAME,
                    !"blocked".equalsIgnoreCase(value.optString(STATE)));
        } else if (objectClass.is(ObjectClass.GROUP_NAME)) {
            builder.setName(requiredJsonString(value, NAME));
            addIfPresent(builder, PATH, value);
            addIfPresent(builder, FULL_PATH, value);
            addIfPresent(builder, DESCRIPTION, value);
            addIfPresent(builder, WEB_URL, value);
            String entityPath = GROUPS + "/" + requiredId(value);
            Set<String> members = translated(() -> api.directMembersAtAccessLevel(entityPath, DEVELOPER_ACCESS_LEVEL));
            builder.addAttribute(DEVELOPER_MEMBERS, members);
            GroupLastEventScan scan = translated(() -> groupMemberLastEventPairs(entityPath, members));
            builder.addAttribute(DEVELOPER_MEMBERS_LAST_EVENT, scan.pairs());
            builder.addAttribute(DEVELOPER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS, configuration.getEventLookbackDays());
            builder.addAttribute(DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_SCANNED, scan.projectsScanned());
            builder.addAttribute(DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_TRUNCATED, scan.truncated());
        } else if (objectClass.is(PROJECT_OBJECT_CLASS)) {
            builder.setName(requiredJsonString(value, NAME));
            addIfPresent(builder, PATH, value);
            addIfPresent(builder, PATH_WITH_NAMESPACE, value);
            addIfPresent(builder, DESCRIPTION, value);
            addIfPresent(builder, WEB_URL, value);
            String entityPath = PROJECTS + "/" + requiredId(value);
            Set<String> members = translated(() -> api.directMembersAtAccessLevel(entityPath, MAINTAINER_ACCESS_LEVEL));
            builder.addAttribute(MASTER_MEMBERS, members);
            builder.addAttribute(
                    MASTER_MEMBERS_LAST_EVENT,
                    translated(() -> projectMemberLastEventPairs(entityPath, members)));
            builder.addAttribute(MASTER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS, configuration.getEventLookbackDays());
        } else {
            throw unsupportedObjectClass(objectClass);
        }
        return builder.build();
    }

    /**
     * #422 NAC2: for each current Maintainer, that member's most recent event
     * {@code created_at} on this specific project, encoded as "<userId>|<ISO-8601>"
     * pairs (ConnId has no native map type). A Maintainer with no event inside the
     * configured lookback window is simply absent from the result: a partial
     * signal, not a fabricated one.
     */
    private Set<String> projectMemberLastEventPairs(String projectEntityPath, Set<String> memberIds) {
        if (memberIds.isEmpty()) {
            return Set.of();
        }
        Map<String, Instant> lastEventByAuthor = new LinkedHashMap<>();
        accumulateLastEvents(lastEventByAuthor, projectEntityPath, memberIds);
        return encodeLastEventPairs(lastEventByAuthor);
    }

    /**
     * Result of a group scan: the pairs plus the coverage context a consumer
     * needs to read an absent pair correctly. {@code truncated} is true when
     * GitLab listed more projects than {@code maxProjectsPerGroup}; only the
     * first {@code projectsScanned} were read.
     */
    record GroupLastEventScan(Set<String> pairs, int projectsScanned, boolean truncated) {
    }

    /**
     * #422 NAC3: no group-scoped events endpoint exists, so a Developer's
     * last-activity is the most recent event across the projects GitLab lists
     * for the group (its defaults: shared projects included, subgroup projects
     * excluded, newest first). Bounded N+1: {@code maxProjectsPerGroup} caps how
     * many of those projects are read, so a group with more projects than the
     * bound yields a partial signal rather than an unbounded scan. The listing
     * asks for one project more than the bound so that truncation can be
     * reported without paying for the full listing.
     */
    private GroupLastEventScan groupMemberLastEventPairs(String groupEntityPath, Set<String> memberIds) {
        if (memberIds.isEmpty()) {
            return new GroupLastEventScan(Set.of(), 0, false);
        }
        int bound = configuration.getMaxProjectsPerGroup();
        List<JSONObject> listed = api.groupProjects(groupEntityPath, bound + 1);
        boolean truncated = listed.size() > bound;
        List<JSONObject> projects = truncated ? listed.subList(0, bound) : listed;
        Map<String, Instant> lastEventByAuthor = new LinkedHashMap<>();
        for (JSONObject project : projects) {
            accumulateLastEvents(lastEventByAuthor, PROJECTS + "/" + requiredId(project), memberIds);
        }
        return new GroupLastEventScan(encodeLastEventPairs(lastEventByAuthor), projects.size(), truncated);
    }

    private void accumulateLastEvents(
            Map<String, Instant> lastEventByAuthor, String projectEntityPath, Set<String> memberIds) {

        String after = LocalDate.now(clock).minusDays(configuration.getEventLookbackDays()).toString();
        for (JSONObject event : api.projectEvents(projectEntityPath, Map.of(AFTER, after))) {
            if (!event.has(AUTHOR_ID) || event.isNull(AUTHOR_ID)) {
                continue;
            }
            String authorId = String.valueOf(event.getLong(AUTHOR_ID));
            if (!memberIds.contains(authorId)) {
                continue;
            }
            Instant createdAt = requiredEventInstant(event);
            Instant existing = lastEventByAuthor.get(authorId);
            if (existing == null || createdAt.isAfter(existing)) {
                lastEventByAuthor.put(authorId, createdAt);
            }
        }
    }

    private static Set<String> encodeLastEventPairs(Map<String, Instant> lastEventByAuthor) {
        Set<String> pairs = new LinkedHashSet<>();
        lastEventByAuthor.forEach((userId, instant) -> pairs.add(userId + "|" + instant));
        return pairs;
    }

    private static Instant requiredEventInstant(JSONObject event) {
        String raw = event.optString(CREATED_AT, "");
        if (raw.isBlank()) {
            throw new ConnectorIOException("GitLab event response omitted required attribute " + CREATED_AT);
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ConnectorIOException("GitLab event response returned a malformed " + CREATED_AT);
        }
    }

    private void protectAdministrativeAccount(Uid uid) {
        JSONObject account = translated(() -> api.getJson(USERS + "/"
                + GitlabApiClient.encodePathIdentifier(uid.getUidValue())));
        rejectProtectedAdministrativeAccount(account);
    }

    private boolean protectMembershipAccountIfPresent(String userId) {
        return translated(() -> api.getOptionalJson(USERS + "/"
                        + GitlabApiClient.encodePathIdentifier(userId)))
                .map(account -> {
                    rejectProtectedAdministrativeAccount(account);
                    return true;
                })
                .orElse(false);
    }

    private IntegrationActor integrationActor() {
        JSONObject currentUser = translated(() -> api.getJson("/user"));
        String integrationUsername = configuration.effectiveIntegrationUsername();
        if (!integrationUsername.equals(currentUser.optString(USERNAME))) {
            throw new PermissionDeniedException(
                    "GitLab token must belong to the configured dedicated integration administrator");
        }
        if (!currentUser.optBoolean("is_admin", false)) {
            throw new PermissionDeniedException(
                    "GitLab token is valid but the integration identity is not an instance administrator");
        }
        return new IntegrationActor(requiredId(currentUser), integrationUsername);
    }

    private void rejectProtectedAdministrativeAccount(JSONObject account) {
        if (isProtectedUsername(requiredJsonString(account, USERNAME))) {
            throw new PermissionDeniedException("GitLab administrative identities are protected");
        }
    }

    private boolean isProtectedUsername(String username) {
        return ROOT_USERNAME.equalsIgnoreCase(username)
                || configuration.effectiveIntegrationUsername().equalsIgnoreCase(username);
    }

    private static org.identityconnectors.framework.common.objects.ObjectClassInfo accountObjectClass() {
        ObjectClassInfoBuilder builder = new ObjectClassInfoBuilder().setType(ObjectClass.ACCOUNT_NAME);
        builder.addAttributeInfo(readWrite(NAME, String.class, true));
        builder.addAttributeInfo(readWrite(EMAIL, String.class, true));
        builder.addAttributeInfo(readOnly(STATE, String.class));
        builder.addAttributeInfo(readOnly(WEB_URL, String.class));
        builder.addAttributeInfo(new AttributeInfoBuilder(
                        OperationalAttributes.PASSWORD_NAME, GuardedString.class)
                .setReadable(false)
                .setReturnedByDefault(false)
                .setCreateable(true)
                .setUpdateable(true)
                .build());
        builder.addAttributeInfo(new AttributeInfoBuilder(
                        OperationalAttributes.ENABLE_NAME, Boolean.class)
                .setReadable(true)
                .setCreateable(true)
                .setUpdateable(true)
                .build());
        return builder.build();
    }

    private static org.identityconnectors.framework.common.objects.ObjectClassInfo groupObjectClass() {
        ObjectClassInfoBuilder builder = new ObjectClassInfoBuilder().setType(ObjectClass.GROUP_NAME);
        builder.addAttributeInfo(readOnly(PATH, String.class));
        builder.addAttributeInfo(readOnly(FULL_PATH, String.class));
        builder.addAttributeInfo(readOnly(DESCRIPTION, String.class));
        builder.addAttributeInfo(readOnly(WEB_URL, String.class));
        builder.addAttributeInfo(membership(DEVELOPER_MEMBERS));
        builder.addAttributeInfo(readOnlyMultivalued(DEVELOPER_MEMBERS_LAST_EVENT));
        builder.addAttributeInfo(readOnly(DEVELOPER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS, Integer.class));
        builder.addAttributeInfo(readOnly(DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_SCANNED, Integer.class));
        builder.addAttributeInfo(readOnly(DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_TRUNCATED, Boolean.class));
        return builder.build();
    }

    private static org.identityconnectors.framework.common.objects.ObjectClassInfo projectObjectClass() {
        ObjectClassInfoBuilder builder = new ObjectClassInfoBuilder().setType(PROJECT_OBJECT_CLASS);
        builder.addAttributeInfo(readOnly(PATH, String.class));
        builder.addAttributeInfo(readOnly(PATH_WITH_NAMESPACE, String.class));
        builder.addAttributeInfo(readOnly(DESCRIPTION, String.class));
        builder.addAttributeInfo(readOnly(WEB_URL, String.class));
        builder.addAttributeInfo(membership(MASTER_MEMBERS));
        builder.addAttributeInfo(readOnlyMultivalued(MASTER_MEMBERS_LAST_EVENT));
        builder.addAttributeInfo(readOnly(MASTER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS, Integer.class));
        return builder.build();
    }

    private static org.identityconnectors.framework.common.objects.AttributeInfo readWrite(
            String name, Class<?> type, boolean required) {
        return new AttributeInfoBuilder(name, type)
                .setRequired(required)
                .setReadable(true)
                .setCreateable(true)
                .setUpdateable(true)
                .build();
    }

    private static org.identityconnectors.framework.common.objects.AttributeInfo readOnly(
            String name, Class<?> type) {
        return new AttributeInfoBuilder(name, type)
                .setReadable(true)
                .setCreateable(false)
                .setUpdateable(false)
                .build();
    }

    private static org.identityconnectors.framework.common.objects.AttributeInfo membership(String name) {
        return new AttributeInfoBuilder(name, String.class)
                .setReadable(true)
                .setCreateable(false)
                .setUpdateable(true)
                .setMultiValued(true)
                .build();
    }

    private static org.identityconnectors.framework.common.objects.AttributeInfo readOnlyMultivalued(String name) {
        return new AttributeInfoBuilder(name, String.class)
                .setReadable(true)
                .setCreateable(false)
                .setUpdateable(false)
                .setMultiValued(true)
                .build();
    }

    private static List<JSONObject> applyWindow(List<JSONObject> values, OperationOptions options) {
        int start = Math.max(0, options.getPagedResultsOffset() == null ? 0 : options.getPagedResultsOffset() - 1);
        if (start >= values.size()) {
            return List.of();
        }
        int requested = options.getPageSize() == null || options.getPageSize() <= 0
                ? values.size()
                : options.getPageSize();
        int end = Math.min(values.size(), start + requested);
        return new ArrayList<>(values.subList(start, end));
    }

    private static List<ExactFilter> parseExactFilters(Filter filter) {
        if (filter == null) {
            return List.of();
        }
        List<ExactFilter> result = new ArrayList<>();
        collectExactFilters(filter, result);
        return result;
    }

    private static void collectExactFilters(Filter filter, List<ExactFilter> result) {
        if (filter instanceof AndFilter and) {
            for (Filter nested : and.getFilters()) {
                collectExactFilters(nested, result);
            }
            return;
        }
        if (filter instanceof EqualsFilter equals) {
            collectSingleValuedAttribute(equals.getAttribute(), result, filter.getClass());
            return;
        }
        if (filter instanceof ContainsAllValuesFilter containsAll) {
            collectSingleValuedAttribute(containsAll.getAttribute(), result, filter.getClass());
            return;
        }
        throw new UnsupportedOperationException(
                "Unsupported ConnId filter type "
                        + filter.getClass().getName()
                        + "; only exact single-valued Equals/ContainsAllValues filters and their AND conjunctions are supported");
    }

    private static void collectSingleValuedAttribute(
            Attribute attribute, List<ExactFilter> result, Class<?> filterType) {
        if (attribute.getValue() == null || attribute.getValue().size() != 1) {
            throw new InvalidAttributeValueException(
                    "Exact ConnId filter type "
                            + filterType.getName()
                            + " must contain exactly one value");
        }
        result.add(new ExactFilter(attribute.getName(), String.valueOf(attribute.getValue().get(0))));
    }

    private static ExactFilter findFilter(List<ExactFilter> filters, String attribute) {
        return filters.stream()
                .filter(filter -> attribute.equals(filter.attribute()))
                .findFirst()
                .orElse(null);
    }

    private static String endpoint(ObjectClass objectClass) {
        if (objectClass.is(ObjectClass.ACCOUNT_NAME)) {
            return USERS;
        }
        if (objectClass.is(ObjectClass.GROUP_NAME)) {
            return GROUPS;
        }
        if (objectClass.is(PROJECT_OBJECT_CLASS)) {
            return PROJECTS;
        }
        throw unsupportedObjectClass(objectClass);
    }

    private static UnsupportedOperationException topologyMutationRejected(ObjectClass objectClass, String operation) {
        if (objectClass != null
                && (objectClass.is(ObjectClass.GROUP_NAME) || objectClass.is(PROJECT_OBJECT_CLASS))) {
            return new UnsupportedOperationException(
                    "GitLab group/project topology " + operation + " is intentionally unsupported");
        }
        return unsupportedObjectClass(objectClass);
    }

    private static UnsupportedOperationException unsupportedObjectClass(ObjectClass objectClass) {
        return new UnsupportedOperationException("Unsupported GitLab object class: " + objectClass);
    }

    private static void requireObjectClass(ObjectClass objectClass) {
        if (objectClass == null) {
            throw new InvalidAttributeValueException("ObjectClass is required");
        }
    }

    private static void requireUid(Uid uid) {
        if (uid == null || uid.getUidValue() == null || uid.getUidValue().isBlank()) {
            throw new InvalidAttributeValueException("Uid is required");
        }
    }

    private static String requiredString(Set<Attribute> attributes, String name) {
        String value = single(attributes, name, String.class);
        if (value == null || value.isBlank()) {
            throw new InvalidAttributeValueException("Required GitLab account attribute is missing: " + name);
        }
        return value;
    }

    private static <T> T single(Set<Attribute> attributes, String name, Class<T> type) {
        for (Attribute attribute : attributes) {
            if (!name.equals(attribute.getName())) {
                continue;
            }
            List<Object> values = attribute.getValue();
            if (values == null || values.isEmpty()) {
                return null;
            }
            if (values.size() != 1) {
                throw new InvalidAttributeValueException("Attribute must contain one value: " + name);
            }
            return asType(values.get(0), type, name);
        }
        return null;
    }

    private static Object singleReplacement(AttributeDelta delta) {
        List<Object> values = delta.getValuesToReplace();
        if (values == null || values.size() != 1 || values.get(0) == null) {
            throw new InvalidAttributeValueException(
                    "Replace delta must contain one non-null value: " + delta.getName());
        }
        return values.get(0);
    }

    private static <T> T asType(Object value, Class<T> type, String name) {
        if (!type.isInstance(value)) {
            throw new InvalidAttributeValueException(
                    "Attribute " + name + " must have type " + type.getSimpleName());
        }
        return type.cast(value);
    }

    private static String asString(Object value, String name) {
        String result = asType(value, String.class, name);
        if (result.isBlank()) {
            throw new InvalidAttributeValueException("Attribute must not be blank: " + name);
        }
        return result;
    }

    private static Set<String> stringSet(Collection<Object> values, String name) {
        Set<String> result = new HashSet<>();
        if (values == null) {
            return result;
        }
        for (Object value : values) {
            result.add(asString(value, name));
        }
        return result;
    }

    private static String reveal(GuardedString value) {
        StringBuilder revealed = new StringBuilder();
        value.access(chars -> revealed.append(chars));
        return revealed.toString();
    }

    private static String requiredId(JSONObject value) {
        if (!value.has(ID)) {
            throw new ConnectorIOException("GitLab response omitted required id");
        }
        return String.valueOf(value.getLong(ID));
    }

    private static String requiredJsonString(JSONObject value, String name) {
        String result = value.optString(name, "");
        if (result.isBlank()) {
            throw new ConnectorIOException("GitLab response omitted required attribute " + name);
        }
        return result;
    }

    private static String requiredJsonIdentifier(JSONObject value, String name) {
        if (!value.has(name) || value.isNull(name)) {
            throw new ConnectorIOException("GitLab response omitted required attribute " + name);
        }
        return String.valueOf(value.get(name));
    }

    private static void addIfPresent(ConnectorObjectBuilder builder, String attribute, JSONObject value) {
        if (value.has(attribute) && !value.isNull(attribute)) {
            builder.addAttribute(attribute, value.get(attribute));
        }
    }

    private static <T> T translated(Supplier<T> action) {
        try {
            return action.get();
        } catch (GitlabApiException e) {
            throw translate(e);
        }
    }

    private static void translatedRun(Runnable action) {
        try {
            action.run();
        } catch (GitlabApiException e) {
            throw translate(e);
        }
    }

    private static ConnectorException translate(GitlabApiException e) {
        return switch (e.statusCode()) {
            case 401, 403 -> new PermissionDeniedException(e.getMessage(), e);
            case 404, 410 -> new UnknownUidException(e.getMessage(), e);
            case 408, 504 -> new OperationTimeoutException(e.getMessage(), e);
            case 409 -> new AlreadyExistsException(e.getMessage(), e);
            case 400, 405, 406, 422 -> new InvalidAttributeValueException(e.getMessage(), e);
            default -> new ConnectorIOException(e.getMessage(), e);
        };
    }

    private record ExactFilter(String attribute, String value) {
    }
}

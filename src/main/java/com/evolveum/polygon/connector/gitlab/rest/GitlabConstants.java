/*
 * Copyright 2016-2017 Evolveum
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

final class GitlabConstants {

    static final String PROJECT_OBJECT_CLASS = "Project";
    static final String ROOT_USERNAME = "root";
    /** Defaults for the configurable dedicated integration identity (see GitlabRestConfiguration). */
    static final String DEFAULT_INTEGRATION_USERNAME = "easyiga-midpoint";
    static final String DEFAULT_INTEGRATION_TOKEN_NAME = "easyiga-midpoint-connector";
    static final int MAX_INTEGRATION_TOKEN_REMAINING_DAYS = 30;

    static final String USERS = "/users";
    static final String GROUPS = "/groups";
    static final String PROJECTS = "/projects";

    static final String ID = "id";
    static final String USERNAME = "username";
    static final String NAME = "name";
    static final String EMAIL = "email";
    static final String STATE = "state";
    static final String WEB_URL = "web_url";
    static final String PATH = "path";
    static final String FULL_PATH = "full_path";
    static final String PATH_WITH_NAMESPACE = "path_with_namespace";
    static final String DESCRIPTION = "description";
    static final String CREATED_AT = "created_at";
    static final String CREATED_BY = "created_by";
    static final String EXPIRES_AT = "expires_at";
    static final String AUTHOR_ID = "author_id";
    static final String AFTER = "after";

    static final String DEVELOPER_MEMBERS = "developer_members";
    static final String MASTER_MEMBERS = "master_members";
    // #422: per-(account, entitlement) last-use signal, encoded as "<userId>|<ISO-8601>"
    // pairs (ConnId has no native map type). See README "Activity signals".
    static final String MASTER_MEMBERS_LAST_EVENT = "master_members_last_event";
    static final String DEVELOPER_MEMBERS_LAST_EVENT = "developer_members_last_event";
    // Coverage context for the last-event signal: the window both scans used, and for
    // groups how many projects were scanned and whether the listing exceeded the bound.
    // Without these, an absent pair cannot be told apart from a truncated scan.
    static final String MASTER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS = "master_members_last_event_lookback_days";
    static final String DEVELOPER_MEMBERS_LAST_EVENT_LOOKBACK_DAYS = "developer_members_last_event_lookback_days";
    static final String DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_SCANNED = "developer_members_last_event_projects_scanned";
    static final String DEVELOPER_MEMBERS_LAST_EVENT_PROJECTS_TRUNCATED = "developer_members_last_event_projects_truncated";

    static final int DEVELOPER_ACCESS_LEVEL = 30;
    static final int MAINTAINER_ACCESS_LEVEL = 40;

    private GitlabConstants() {
    }
}

/*
 * Copyright 2016-2017 Evolveum
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import java.net.URI;

import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.spi.AbstractConfiguration;
import org.identityconnectors.framework.spi.ConfigurationProperty;
import org.identityconnectors.framework.spi.StatefulConfiguration;

/**
 * Configuration kept deliberately small so the resource has an auditable
 * security boundary.
 */
public final class GitlabRestConfiguration extends AbstractConfiguration implements StatefulConfiguration {

    private String baseUrl;
    private GuardedString privateToken;
    private int connectTimeoutMillis = 10_000;
    private int requestTimeoutMillis = 30_000;
    private boolean allowAccountDelete;
    private boolean allowTopologyMutations;
    private boolean forceRandomPasswordOnCreate = true;
    private String deprovisionDeveloperGroupPath;
    private String deprovisionMaintainerProjectPath;
    private int eventLookbackDays = 90;
    private int maxProjectsPerGroup = 20;
    private String integrationUsername = GitlabConstants.DEFAULT_INTEGRATION_USERNAME;
    private String integrationTokenName = GitlabConstants.DEFAULT_INTEGRATION_TOKEN_NAME;

    @ConfigurationProperty(
            order = 1,
            displayMessageKey = "baseUrl.display",
            helpMessageKey = "baseUrl.help",
            required = true)
    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @ConfigurationProperty(
            order = 2,
            displayMessageKey = "privateToken.display",
            helpMessageKey = "privateToken.help",
            required = true,
            confidential = true)
    public GuardedString getPrivateToken() {
        return privateToken;
    }

    public void setPrivateToken(GuardedString privateToken) {
        this.privateToken = privateToken;
    }

    @ConfigurationProperty(
            order = 3,
            displayMessageKey = "connectTimeoutMillis.display",
            helpMessageKey = "connectTimeoutMillis.help")
    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    @ConfigurationProperty(
            order = 4,
            displayMessageKey = "requestTimeoutMillis.display",
            helpMessageKey = "requestTimeoutMillis.help")
    public int getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(int requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    @ConfigurationProperty(
            order = 5,
            displayMessageKey = "allowAccountDelete.display",
            helpMessageKey = "allowAccountDelete.help")
    public boolean isAllowAccountDelete() {
        return allowAccountDelete;
    }

    public void setAllowAccountDelete(boolean allowAccountDelete) {
        this.allowAccountDelete = allowAccountDelete;
    }

    @ConfigurationProperty(
            order = 6,
            displayMessageKey = "allowTopologyMutations.display",
            helpMessageKey = "allowTopologyMutations.help")
    public boolean isAllowTopologyMutations() {
        return allowTopologyMutations;
    }

    public void setAllowTopologyMutations(boolean allowTopologyMutations) {
        this.allowTopologyMutations = allowTopologyMutations;
    }

    @ConfigurationProperty(
            order = 7,
            displayMessageKey = "forceRandomPasswordOnCreate.display",
            helpMessageKey = "forceRandomPasswordOnCreate.help")
    public boolean isForceRandomPasswordOnCreate() {
        return forceRandomPasswordOnCreate;
    }

    public void setForceRandomPasswordOnCreate(boolean forceRandomPasswordOnCreate) {
        this.forceRandomPasswordOnCreate = forceRandomPasswordOnCreate;
    }

    @ConfigurationProperty(
            order = 8,
            displayMessageKey = "deprovisionDeveloperGroupPath.display",
            helpMessageKey = "deprovisionDeveloperGroupPath.help")
    public String getDeprovisionDeveloperGroupPath() {
        return deprovisionDeveloperGroupPath;
    }

    public void setDeprovisionDeveloperGroupPath(String deprovisionDeveloperGroupPath) {
        this.deprovisionDeveloperGroupPath = deprovisionDeveloperGroupPath;
    }

    @ConfigurationProperty(
            order = 9,
            displayMessageKey = "deprovisionMaintainerProjectPath.display",
            helpMessageKey = "deprovisionMaintainerProjectPath.help")
    public String getDeprovisionMaintainerProjectPath() {
        return deprovisionMaintainerProjectPath;
    }

    public void setDeprovisionMaintainerProjectPath(String deprovisionMaintainerProjectPath) {
        this.deprovisionMaintainerProjectPath = deprovisionMaintainerProjectPath;
    }

    @ConfigurationProperty(
            order = 10,
            displayMessageKey = "eventLookbackDays.display",
            helpMessageKey = "eventLookbackDays.help")
    public int getEventLookbackDays() {
        return eventLookbackDays;
    }

    public void setEventLookbackDays(int eventLookbackDays) {
        this.eventLookbackDays = eventLookbackDays;
    }

    @ConfigurationProperty(
            order = 11,
            displayMessageKey = "maxProjectsPerGroup.display",
            helpMessageKey = "maxProjectsPerGroup.help")
    public int getMaxProjectsPerGroup() {
        return maxProjectsPerGroup;
    }

    public void setMaxProjectsPerGroup(int maxProjectsPerGroup) {
        this.maxProjectsPerGroup = maxProjectsPerGroup;
    }

    @ConfigurationProperty(
            order = 12,
            displayMessageKey = "integrationUsername.display",
            helpMessageKey = "integrationUsername.help")
    public String getIntegrationUsername() {
        return integrationUsername;
    }

    public void setIntegrationUsername(String integrationUsername) {
        this.integrationUsername = integrationUsername;
    }

    @ConfigurationProperty(
            order = 13,
            displayMessageKey = "integrationTokenName.display",
            helpMessageKey = "integrationTokenName.help")
    public String getIntegrationTokenName() {
        return integrationTokenName;
    }

    public void setIntegrationTokenName(String integrationTokenName) {
        this.integrationTokenName = integrationTokenName;
    }

    /** Trimmed, validated username of the dedicated integration administrator. */
    String effectiveIntegrationUsername() {
        return integrationUsername.trim();
    }

    /** Trimmed, validated name the integration token must carry. */
    String effectiveIntegrationTokenName() {
        return integrationTokenName.trim();
    }

    @Override
    public void validate() {
        if (StringUtil.isBlank(baseUrl)) {
            throw new ConfigurationException("GitLab baseUrl is required");
        }

        final URI parsed;
        try {
            parsed = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("GitLab baseUrl is not a valid URI", e);
        }
        if (!"http".equalsIgnoreCase(parsed.getScheme()) && !"https".equalsIgnoreCase(parsed.getScheme())) {
            throw new ConfigurationException("GitLab baseUrl must use http or https");
        }
        if (StringUtil.isBlank(parsed.getHost())) {
            throw new ConfigurationException("GitLab baseUrl must contain a host");
        }
        if (parsed.getRawUserInfo() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new ConfigurationException("GitLab baseUrl must not contain credentials, a query, or a fragment");
        }
        if (privateToken == null || isEmpty(privateToken)) {
            throw new ConfigurationException("GitLab privateToken is required");
        }
        if (connectTimeoutMillis <= 0) {
            throw new ConfigurationException("connectTimeoutMillis must be positive");
        }
        if (requestTimeoutMillis <= 0) {
            throw new ConfigurationException("requestTimeoutMillis must be positive");
        }
        if (allowAccountDelete) {
            throw new ConfigurationException(
                    "Account hard delete is intentionally unsupported; allowAccountDelete must remain false");
        }
        if (allowTopologyMutations) {
            throw new ConfigurationException(
                    "Group and project topology mutations are intentionally unsupported; allowTopologyMutations must remain false");
        }
        boolean hasDeveloperGroup = !StringUtil.isBlank(deprovisionDeveloperGroupPath);
        boolean hasMaintainerProject = !StringUtil.isBlank(deprovisionMaintainerProjectPath);
        if (hasDeveloperGroup != hasMaintainerProject) {
            throw new ConfigurationException(
                    "Both deprovision membership paths must be configured together");
        }
        if (eventLookbackDays <= 0) {
            throw new ConfigurationException("eventLookbackDays must be positive");
        }
        if (maxProjectsPerGroup <= 0) {
            throw new ConfigurationException("maxProjectsPerGroup must be positive");
        }
        if (StringUtil.isBlank(integrationUsername)) {
            throw new ConfigurationException("integrationUsername is required");
        }
        if (GitlabConstants.ROOT_USERNAME.equalsIgnoreCase(integrationUsername.trim())) {
            throw new ConfigurationException(
                    "integrationUsername must be a dedicated identity, not root");
        }
        if (StringUtil.isBlank(integrationTokenName)) {
            throw new ConfigurationException("integrationTokenName is required");
        }
    }

    @Override
    public void release() {
        // Declarative configuration is owned and may be cached/reused by
        // ConnId. There are no connector-owned resources to release here.
    }

    private static boolean isEmpty(GuardedString value) {
        boolean[] empty = {true};
        value.access(chars -> empty[0] = chars.length == 0);
        return empty[0];
    }
}

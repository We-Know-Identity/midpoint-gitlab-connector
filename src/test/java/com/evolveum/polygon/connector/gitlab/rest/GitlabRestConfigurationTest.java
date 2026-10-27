/*
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

class GitlabRestConfigurationTest {

    @Test
    void acceptsHttpAndHttpsBaseUrls() {
        assertDoesNotThrow(() -> configuration("http://gitlab").validate());
        assertDoesNotThrow(() -> configuration("https://gitlab.example.test/subpath").validate());
    }

    @Test
    void rejectsCredentialsInBaseUrl() {
        assertThrows(
                ConfigurationException.class,
                () -> configuration("https://name:password@gitlab.example.test").validate());
    }

    @Test
    void rejectsEmptyTokenAndNonPositiveTimeouts() {
        GitlabRestConfiguration emptyToken = new GitlabRestConfiguration();
        emptyToken.setBaseUrl("http://gitlab");
        emptyToken.setPrivateToken(new GuardedString(new char[0]));
        assertThrows(ConfigurationException.class, emptyToken::validate);

        GitlabRestConfiguration invalidTimeout = configuration("http://gitlab");
        invalidTimeout.setRequestTimeoutMillis(0);
        assertThrows(ConfigurationException.class, invalidTimeout::validate);
    }

    @Test
    void topologyMutationSwitchCannotBeEnabled() {
        GitlabRestConfiguration configuration = configuration("http://gitlab");
        configuration.setAllowTopologyMutations(true);
        assertThrows(ConfigurationException.class, configuration::validate);
    }

    @Test
    void accountDeleteSwitchCannotBeEnabled() {
        GitlabRestConfiguration configuration = configuration("http://gitlab");
        configuration.setAllowAccountDelete(true);
        assertThrows(ConfigurationException.class, configuration::validate);
    }

    @Test
    void defaultsToPositiveLastUseBounds() {
        GitlabRestConfiguration configuration = configuration("http://gitlab");
        assertEquals(90, configuration.getEventLookbackDays());
        assertEquals(20, configuration.getMaxProjectsPerGroup());
        assertDoesNotThrow(configuration::validate);
    }

    @Test
    void integrationIdentityDefaultsToEasyIgaConvention() {
        GitlabRestConfiguration configuration = configuration("http://gitlab");
        assertEquals("easyiga-midpoint", configuration.getIntegrationUsername());
        assertEquals("easyiga-midpoint-connector", configuration.getIntegrationTokenName());
        assertDoesNotThrow(configuration::validate);
    }

    @Test
    void integrationIdentityIsConfigurableButNeverRootOrBlank() {
        GitlabRestConfiguration custom = configuration("http://gitlab");
        custom.setIntegrationUsername(" midpoint-svc ");
        custom.setIntegrationTokenName(" midpoint-svc-token ");
        assertDoesNotThrow(custom::validate);
        assertEquals("midpoint-svc", custom.effectiveIntegrationUsername());
        assertEquals("midpoint-svc-token", custom.effectiveIntegrationTokenName());

        GitlabRestConfiguration root = configuration("http://gitlab");
        root.setIntegrationUsername("Root");
        assertThrows(ConfigurationException.class, root::validate);

        GitlabRestConfiguration blankUser = configuration("http://gitlab");
        blankUser.setIntegrationUsername("  ");
        assertThrows(ConfigurationException.class, blankUser::validate);

        GitlabRestConfiguration blankToken = configuration("http://gitlab");
        blankToken.setIntegrationTokenName("");
        assertThrows(ConfigurationException.class, blankToken::validate);
    }

    @Test
    void rejectsNonPositiveEventLookbackDays() {
        GitlabRestConfiguration configuration = configuration("http://gitlab");
        configuration.setEventLookbackDays(0);
        assertThrows(ConfigurationException.class, configuration::validate);
    }

    @Test
    void rejectsNonPositiveMaxProjectsPerGroup() {
        GitlabRestConfiguration configuration = configuration("http://gitlab");
        configuration.setMaxProjectsPerGroup(0);
        assertThrows(ConfigurationException.class, configuration::validate);
    }

    @Test
    void deprovisionMembershipPathsMustBeConfiguredTogether() {
        GitlabRestConfiguration configuration = configuration("http://gitlab");
        configuration.setDeprovisionDeveloperGroupPath("easyiga-demo");
        assertThrows(ConfigurationException.class, configuration::validate);

        configuration.setDeprovisionMaintainerProjectPath(
                "easyiga-demo/easyiga-core");
        assertDoesNotThrow(configuration::validate);
    }

    private static GitlabRestConfiguration configuration(String baseUrl) {
        GitlabRestConfiguration configuration = new GitlabRestConfiguration();
        configuration.setBaseUrl(baseUrl);
        configuration.setPrivateToken(new GuardedString("test-token".toCharArray()));
        return configuration;
    }
}

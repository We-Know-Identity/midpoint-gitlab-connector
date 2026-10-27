/*
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

import org.identityconnectors.framework.api.ConnectorInfo;
import org.identityconnectors.framework.api.ConnectorInfoManager;
import org.identityconnectors.framework.api.ConnectorInfoManagerFactory;
import org.junit.jupiter.api.Test;

/**
 * Loads the assembled JAR through ConnId itself. This catches malformed bundle
 * manifests, missing nested libraries, and configuration-introspection errors
 * that ordinary classpath unit tests cannot detect.
 */
class GitlabBundleLoadIT {

    @Test
    void assembledJarIsDiscoverableByConnId16() throws Exception {
        Path bundle = Path.of(
                "target",
                "connector-gitlab-rest-2.3.0-easyiga-connector.jar").toAbsolutePath();
        ConnectorInfoManager manager =
                ConnectorInfoManagerFactory.getInstance().getLocalManager(bundle.toUri().toURL());

        List<ConnectorInfo> connectors = manager.getConnectorInfos();

        assertEquals(1, connectors.size());
        ConnectorInfo connector = connectors.get(0);
        assertEquals(
                "com.evolveum.polygon.connector-gitlab-rest",
                connector.getConnectorKey().getBundleName());
        assertEquals("2.3.0-easyiga", connector.getConnectorKey().getBundleVersion());
        assertEquals(
                GitlabRestConnector.class.getName(),
                connector.getConnectorKey().getConnectorName());
        assertTrue(connector.createDefaultAPIConfiguration()
                .getConfigurationProperties()
                .getPropertyNames()
                .containsAll(List.of(
                        "baseUrl",
                        "privateToken",
                        "connectTimeoutMillis",
                        "requestTimeoutMillis",
                        "allowAccountDelete",
                        "allowTopologyMutations",
                        "forceRandomPasswordOnCreate",
                        "deprovisionDeveloperGroupPath",
                        "deprovisionMaintainerProjectPath",
                        "eventLookbackDays",
                        "maxProjectsPerGroup")));

        try (JarFile jar = new JarFile(bundle.toFile())) {
            assertNotNull(jar.getEntry("META-INF/LICENSE"));
            assertNotNull(jar.getEntry("META-INF/NOTICE"));
        }
    }
}

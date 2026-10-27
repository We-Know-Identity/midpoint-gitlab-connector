/*
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class GitlabInstallerScriptTest {

    @Test
    void identicalInstallStillReachesStaleVersionCleanup() throws Exception {
        String script = Files.readString(Path.of("install-connector.sh"));

        assertFalse(script.contains("exit 0"));
        assertTrue(script.indexOf("find \"$target_dir\"") < script.indexOf("if [ \"$already_installed\" = true ]"));
        assertTrue(script.contains("! -name \"$(basename \"$target_jar\")\""));
    }
}

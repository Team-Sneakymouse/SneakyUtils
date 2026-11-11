package com.sneakyutils.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Reads the real process environment; build.gradle.kts sets P_SERVER_NAME for the test JVM. */
final class ServerUtilEnvironmentTest {

    @Test
    void javaStaticGettersReadServerNameFromEnvironment() {
        assertEquals("Dev Main", System.getenv("P_SERVER_NAME"), "test JVM environment fixture");
        assertEquals("Dev Main", ServerUtil.getServerName());
        assertEquals("dev-main", ServerUtil.getServerId());
    }
}

package com.dansplugins.factionsystem.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStartupOrderTest {

    @Test
    void canonicalGatePrecedesDatabaseFlywayServicesListenersAndTasks() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/kotlin/com/dansplugins/factionsystem/MedievalFactions.kt"));
        int gate = source.indexOf(
                "activatePreparedConfig(ConfigLifecycle.prepare(configFile, bundledConfigYaml))");

        assertTrue(gate >= 0, "onEnable must contain the canonical config gate");
        assertTrue(gate < source.indexOf("Class.forName(\"org.h2.Driver\")"));
        assertTrue(gate < source.indexOf("HikariDataSource(hikariConfig)"));
        assertTrue(gate < source.indexOf("Flyway.configure()"));
        assertTrue(gate < source.indexOf("services = Services("));
        assertTrue(gate < source.indexOf("registerEvents(it, this)"));
        assertTrue(gate < source.indexOf("scheduleSyncRepeatingTask"));
    }

    @Test
    void startupAndDpcPathsDoNotUseBukkitSecondReadOrAppendOnlyWriters() throws Exception {
        String startup = Files.readString(Path.of(
                "src/main/kotlin/com/dansplugins/factionsystem/MedievalFactions.kt"));
        String dpc = Files.readString(Path.of(
                "src/main/kotlin/com/dansplugins/factionsystem/command/faction/dpc/"
                        + "MfFactionDpcCommand.kt"));

        assertFalse(startup.contains("saveDefaultConfig()"));
        assertFalse(startup.contains("reloadConfig()"));
        assertFalse(startup.contains("copyDefaults(true)"));
        assertFalse(startup.contains("saveConfig()"));
        assertFalse(dpc.contains("config.set("));
        assertFalse(dpc.contains("saveConfig()"));
        assertTrue(dpc.contains("updateOperatorConfig"));
    }
}

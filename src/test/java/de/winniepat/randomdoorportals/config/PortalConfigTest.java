package de.winniepat.randomdoorportals.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalConfigTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalConfigTest.class);

    @TempDir
    Path tempDir;

    @Test
    void creatingTemplateOnFirstRun() throws IOException {
        PortalConfig config = PortalConfig.loadOrCreate(tempDir, LOGGER);
        Path file = tempDir.resolve(PortalConfig.FILE_NAME);
        assertTrue(Files.exists(file), "template must be written");
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"enabled\""));
        // defaults
        assertTrue(config.enabled);
        assertEquals(40, config.cooldownTicks);
        assertTrue(config.dangerProtection);
        assertEquals(2, config.chunkScanBudget);
    }

    @Test
    void loadingValuesFromDisk() throws IOException {
        Files.writeString(tempDir.resolve(PortalConfig.FILE_NAME), """
                {"enabled": false, "cooldownTicks": 200, "chunkScanBudget": 8, "debugLogging": true}
                """, StandardCharsets.UTF_8);
        PortalConfig config = PortalConfig.loadOrCreate(tempDir, LOGGER);
        assertFalse(config.enabled);
        assertEquals(200, config.cooldownTicks);
        assertEquals(8, config.chunkScanBudget);
        assertTrue(config.debugLogging);
        assertTrue(config.dangerProtection, "unset fields keep their default");
    }

    @Test
    void corruptFileFallsBackToDefaultsWithBackup() throws IOException {
        Path file = tempDir.resolve(PortalConfig.FILE_NAME);
        Files.writeString(file, "{ this is not json ](", StandardCharsets.UTF_8);
        PortalConfig config = PortalConfig.loadOrCreate(tempDir, LOGGER);
        assertTrue(config.enabled);
        assertEquals(40, config.cooldownTicks);
        assertTrue(Files.exists(tempDir.resolve("randomdoorportals.broken.json")), "backup written");
        assertTrue(Files.exists(file), "original kept for inspection");
    }

    @Test
    void emptyFileFallsBackToDefaults() throws IOException {
        Files.writeString(tempDir.resolve(PortalConfig.FILE_NAME), "   ", StandardCharsets.UTF_8);
        PortalConfig config = PortalConfig.loadOrCreate(tempDir, LOGGER);
        assertTrue(config.enabled);
    }

    @Test
    void unknownTopLevelFieldIsIgnored() throws IOException {
        Files.writeString(tempDir.resolve(PortalConfig.FILE_NAME),
                "{\"futureSetting\": \"x\", \"cooldownTicks\": 99}", StandardCharsets.UTF_8);
        PortalConfig config = PortalConfig.loadOrCreate(tempDir, LOGGER);
        assertEquals(99, config.cooldownTicks);
    }

    // -------------------------------------------------------------- clamping

    @Test
    void clamping() {
        PortalConfig config = PortalConfig.createDefault();
        config.cooldownTicks = -5;
        config.chunkScanBudget = 0;
        config.scanTimeBudgetMillis = 1000;
        config.maxQueueSize = 3;
        config.minDistanceBlocks = -10;
        config.maxDistanceBlocks = -10;
        config.clamp();
        assertEquals(0, config.cooldownTicks);
        assertEquals(1, config.chunkScanBudget);
        assertEquals(50, config.scanTimeBudgetMillis);
        assertEquals(16, config.maxQueueSize);
        assertEquals(0.0, config.minDistanceBlocks, 1.0E-9);
        assertEquals(0.0, config.maxDistanceBlocks, 1.0E-9);
    }

    @Test
    void minDistanceLargerThanMaxIsDropped() {
        PortalConfig config = PortalConfig.createDefault();
        config.minDistanceBlocks = 500;
        config.maxDistanceBlocks = 100;
        config.clamp();
        assertEquals(0.0, config.minDistanceBlocks, 1.0E-9, "impossible min>max is disabled");
        assertEquals(100.0, config.maxDistanceBlocks, 1.0E-9);
    }

    @Test
    void distanceWindow() {
        PortalConfig config = PortalConfig.createDefault();
        assertTrue(config.distanceAllowed(0.0), "no bounds = always allowed");
        config.minDistanceBlocks = 100;
        config.maxDistanceBlocks = 200;
        assertFalse(config.distanceAllowed(50));
        assertTrue(config.distanceAllowed(100));
        assertTrue(config.distanceAllowed(200));
        assertFalse(config.distanceAllowed(250));
        config.maxDistanceBlocks = 0; // max disabled
        assertTrue(config.distanceAllowed(1_000_000));
    }

    @Test
    void dimensionSwitches() {
        PortalConfig config = PortalConfig.createDefault();
        config.allowOverworld = true;
        config.allowNether = false;
        config.allowEnd = false;
        assertTrue(config.isDimensionEnabled("overworld"));
        assertFalse(config.isDimensionEnabled("the_nether"));
        assertFalse(config.isDimensionEnabled("the_end"));
        assertTrue(config.isDimensionEnabled("OVERWORLD"), "case-insensitive");
        // Custom dimensions follow the Overworld switch.
        assertTrue(config.isDimensionEnabled("custom:my_dimension".toLowerCase(Locale.ROOT)));
        config.allowOverworld = false;
        assertFalse(config.isDimensionEnabled("custom:inner".toLowerCase(Locale.ROOT)));
    }
}

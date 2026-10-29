package de.winniepat.randomdoorportals.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Configuration for Random Door Portals.
 *
 * <p>Values are loaded from {@code config/randomdoorportals.json} (Gson). Missing or invalid
 * input falls back to safe defaults; numeric values are clamped into their valid ranges so a
 * hand-edited or corrupt file can never break the server loop.</p>
 *
 * <p>The config is read once at mod initialization and whenever an operator runs
 * {@code /rdp reload}. It is never written back except when the file does not exist
 * (a template with the current defaults is created).</p>
 */
public final class PortalConfig {
    public static final String FILE_NAME = "randomdoorportals.json";

    // --- master switch ---
    /** Master enable/disable for the whole mod. */
    public boolean enabled = true;

    // --- dimension switches ---
    /** Allow portal crossings in the Overworld. */
    public boolean allowOverworld = true;
    /** Allow portal crossings in the Nether. */
    public boolean allowNether = true;
    /** Allow portal crossings in the End. */
    public boolean allowEnd = true;

    // --- teleports ---
    /** Minimum ticks between two teleports of the same player. 0..6000. */
    public int cooldownTicks = 40;
    /** Minimum horizontal block distance between entrance and destination. 0 = disabled. */
    public double minDistanceBlocks = 0.0;
    /** Maximum horizontal block distance between entrance and destination. 0 = unlimited. */
    public double maxDistanceBlocks = 0.0;
    /** If true, the entrance door must be open when the player crosses it. */
    public boolean requireEntranceOpen = true;
    /** If true, the destination door must be open to be chosen. */
    public boolean requireDestinationOpen = true;
    /** Extra protection against teleporting into liquids, fire or the void. */
    public boolean dangerProtection = true;
    /** Try to avoid reusing the last destination for a player when other candidates exist. */
    public boolean avoidRepeatDestinations = true;

    // --- scanning ---
    /** Maximum chunks scanned per server tick (1..1024). */
    public int chunkScanBudget = 2;
    /** Maximum milliseconds spent scanning per server tick (1..50). */
    public int scanTimeBudgetMillis = 4;
    /** Maximum pending chunk-scans kept in the queue. */
    public int maxQueueSize = 1024;

    // --- effects ---
    /** Spawn portal particles at entrance and destination. */
    public boolean particlesEnabled = true;
    /** Play portal sounds on teleport. */
    public boolean soundsEnabled = true;

    // --- diagnostics ---
    /** Verbose debug logging (per-teleport details, scan statistics). */
    public boolean debugLogging = false;

    private PortalConfig() {
    }

    /** Creates the config instance used while no file has been loaded yet. */
    public static PortalConfig createDefault() {
        return new PortalConfig();
    }

    /**
     * Loads {@code config/randomdoorportals.json}, creating a written template on first run.
     * Any parse/IO error results in default values being used, the untouched input kept for
     * inspection plus a warning being logged.
     */
    public static PortalConfig loadOrCreate(Path configDir, Logger log) {
        Path file = configDir.resolve(FILE_NAME);
        PortalConfig config = new PortalConfig();
        if (!Files.exists(file)) {
            try {
                writeTemplate(config, file, log);
            } catch (IOException e) {
                log.error("Random Door Portals could not write a default config file at {} ({})", file, e.toString());
            }
            return config;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                log.warn("Random Door Portals config {} is empty; using defaults", file);
                return config;
            }
            Gson gson = new GsonBuilder().create();
            PortalConfig parsed = gson.fromJson(json, PortalConfig.class);
            if (parsed == null) {
                log.warn("Random Door Portals config {} parsed to null; using defaults", file);
                return config;
            }
            copyInto(parsed, config);
            config.clamp();
            log.info("[RandomDoorPortals] config loaded: cooldown={}t, scanBudget={}/t, debug={}",
                    config.cooldownTicks, config.chunkScanBudget, config.debugLogging);
        } catch (Exception e) {
            // Keep the broken file for inspection, back it up, and continue with defaults.
            log.error("Random Door Portals config {} is invalid ({}); using default values. "
                    + "A backup was saved next to it.", file, e.toString());
            backupBroken(file, log);
        }
        return config;
    }

    /** Writes a fresh, pretty-printed template (also used on first start). */
    private static void writeTemplate(PortalConfig config, Path file, Logger log) throws IOException {
        Files.createDirectories(file.getParent());
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        String json = gson.toJson(config);
        Files.writeString(file, json + System.lineSeparator(), StandardCharsets.UTF_8);
        log.info("[RandomDoorPortals] created default config at {}", file);
    }

    private static void backupBroken(Path file, Logger log) {
        try {
            Path backup = file.resolveSibling(FILE_NAME.replace(".json", ".broken.json"));
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            if (log.isDebugEnabled()) {
                log.debug("Backed up broken config to {}", backup);
            }
        } catch (IOException io) {
            log.warn("Random Door Portals could not back up the broken config file: {}", io.toString());
        }
    }

    /** Copies every field of {@code from} into {@code to} (all fields are public by design). */
    private static void copyInto(PortalConfig from, PortalConfig to) {
        to.enabled = from.enabled;
        to.allowOverworld = from.allowOverworld;
        to.allowNether = from.allowNether;
        to.allowEnd = from.allowEnd;
        to.cooldownTicks = from.cooldownTicks;
        to.minDistanceBlocks = from.minDistanceBlocks;
        to.maxDistanceBlocks = from.maxDistanceBlocks;
        to.requireEntranceOpen = from.requireEntranceOpen;
        to.requireDestinationOpen = from.requireDestinationOpen;
        to.dangerProtection = from.dangerProtection;
        to.avoidRepeatDestinations = from.avoidRepeatDestinations;
        to.chunkScanBudget = from.chunkScanBudget;
        to.scanTimeBudgetMillis = from.scanTimeBudgetMillis;
        to.maxQueueSize = from.maxQueueSize;
        to.particlesEnabled = from.particlesEnabled;
        to.soundsEnabled = from.soundsEnabled;
        to.debugLogging = from.debugLogging;
    }

    /** Clamps all numeric values into their valid ranges. */
    public void clamp() {
        cooldownTicks = clampInt(cooldownTicks, 0, 6000, 40);
        minDistanceBlocks = clampDouble(minDistanceBlocks, 0.0, 200_000.0, 0.0);
        maxDistanceBlocks = clampDouble(maxDistanceBlocks, 0.0, 200_000.0, 0.0);
        if (maxDistanceBlocks > 0.0 && minDistanceBlocks > maxDistanceBlocks) {
            // A minimum larger than the maximum would make valid destinations impossible:
            // treat the minimum as the limit in that case (documented behavior).
            minDistanceBlocks = 0.0;
        }
        chunkScanBudget = clampInt(chunkScanBudget, 1, 1024, 2);
        scanTimeBudgetMillis = clampInt(scanTimeBudgetMillis, 1, 50, 4);
        maxQueueSize = clampInt(maxQueueSize, 16, 1_000_000, 1024);
    }

    private static int clampInt(int value, int min, int max, int fallback) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static double clampDouble(double value, double min, double max, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /** Returns true when the given Overworld/Nether/End style dimension name is enabled. */
    public boolean isDimensionEnabled(String dimensionValue) {
        String path = dimensionValue.toLowerCase(Locale.ROOT);
        return switch (path) {
            case "overworld" -> allowOverworld;
            case "the_nether" -> allowNether;
            case "the_end" -> allowEnd;
            // Custom dimensions default to the Overworld toggle so data packs are not ignored.
            default -> allowOverworld;
        };
    }

    /**
     * True when {@code distance} (horizontal blocks) satisfies the configured window.
     * A zero value for either bound disables that bound.
     */
    public boolean distanceAllowed(double distanceBlocks) {
        if (minDistanceBlocks > 0.0 && distanceBlocks < minDistanceBlocks) {
            return false;
        }
        if (maxDistanceBlocks > 0.0 && distanceBlocks > maxDistanceBlocks) {
            return false;
        }
        return true;
    }
}

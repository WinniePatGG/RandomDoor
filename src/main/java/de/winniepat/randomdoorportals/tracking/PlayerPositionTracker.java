package de.winniepat.randomdoorportals.tracking;

import de.winniepat.randomdoorportals.RandomDoorPortals;
import de.winniepat.randomdoorportals.config.PortalConfig;
import de.winniepat.randomdoorportals.teleport.PortalTeleportService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player movement snapshot bookkeeping for doorway crossing detection.
 *
 * <p>Each server tick every player's current position is compared against the position
 * remembered from the previous tick. Segments longer than {@link #MAX_TRACK_JUMP} are treated
 * as teleports (ender pearls, respawn, other mods) and never trigger crossings.</p>
 *
 * <p>The tracker also owns the per-player state the portal rules need: the teleport cooldown
 * and the two "recent" doors used to avoid repetitions (the door just used as an entrance and
 * the one it led to).</p>
 */
public final class PlayerPositionTracker {
    /** Segments longer than this are never interpreted as walking through a doorway. */
    private static final double MAX_TRACK_JUMP = 4.0;

    /** Sentinel meaning "no memory" (a real encoded door key is never Long.MIN_VALUE). */
    public static final long NO_DOOR = Long.MIN_VALUE;

    private static final class PlayerData {
        boolean hasPrevious;
        double x;
        double y;
        double z;
        int cooldownUntilTick;
        long recentEntrance = NO_DOOR;
        long recentDestination = NO_DOOR;
    }

    private final Map<UUID, PlayerData> players = new HashMap<>();

    public void markJoin(ServerPlayerEntity player) {
        PlayerData data = new PlayerData();
        data.x = player.getX();
        data.y = player.getY();
        data.z = player.getZ();
        data.hasPrevious = true;
        players.put(player.getUuid(), data);
    }

    public void markLeave(ServerPlayerEntity player) {
        players.remove(player.getUuid());
    }

    /** Called by the teleport service after a successful portal pass. */
    public void rememberPortalUse(UUID playerId, long entranceKey, long destinationKey) {
        PlayerData data = players.get(playerId);
        if (data != null) {
            data.recentEntrance = entranceKey;
            data.recentDestination = destinationKey;
        }
    }

    public long recentEntrance(UUID playerId) {
        PlayerData data = players.get(playerId);
        return data == null ? NO_DOOR : data.recentEntrance;
    }

    public long recentDestination(UUID playerId) {
        PlayerData data = players.get(playerId);
        return data == null ? NO_DOOR : data.recentDestination;
    }

    /** Runs crossing detection for every online player of every dimension. */
    public void tick(MinecraftServer server, PortalTeleportService service) {
        PortalConfig config = RandomDoorPortals.CONFIG;
        int tick = server.getTicks();
        for (ServerWorld world : server.getWorlds()) {
            List<ServerPlayerEntity> worldPlayers = world.getPlayers();
            if (worldPlayers.isEmpty()) {
                continue;
            }
            for (ServerPlayerEntity player : worldPlayers) {
                if (!player.isAlive() || player.isSpectator()) {
                    continue;
                }
                PlayerData data = players.computeIfAbsent(player.getUuid(), id -> new PlayerData());
                double nowX = player.getX();
                double nowY = player.getY();
                double nowZ = player.getZ();
                if (!data.hasPrevious) {
                    snapshot(data, nowX, nowY, nowZ);
                    continue;
                }
                double dx = nowX - data.x;
                double dy = nowY - data.y;
                double dz = nowZ - data.z;
                if (dx * dx + dy * dy + dz * dz > MAX_TRACK_JUMP * MAX_TRACK_JUMP) {
                    // A jump of several blocks is a teleport, flight or packet correction:
                    // treat the new position as the baseline without crossing checks.
                    snapshot(data, nowX, nowY, nowZ);
                    continue;
                }
                boolean onCooldown = tick < data.cooldownUntilTick;
                boolean teleported = false;
                if (!onCooldown && config.enabled && !player.isFallFlying()) {
                    teleported = service.handleCrossing(player, data.x, data.y, data.z);
                }
                if (teleported) {
                    data.cooldownUntilTick = tick + config.cooldownTicks;
                    snapshot(data, player.getX(), player.getY(), player.getZ());
                } else {
                    snapshot(data, nowX, nowY, nowZ);
                }
            }
        }
    }

    private static void snapshot(PlayerData data, double x, double y, double z) {
        data.x = x;
        data.y = y;
        data.z = z;
        data.hasPrevious = true;
    }
}

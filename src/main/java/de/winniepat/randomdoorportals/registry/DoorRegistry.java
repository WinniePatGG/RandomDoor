package de.winniepat.randomdoorportals.registry;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Runtime side of the door registry.
 *
 * <p>The actual door sets live in one {@link PortalDoorState} per dimension; each state is
 * created through the world's {@code PersistentStateManager} on first access, which also loads
 * everything saved on disk. Mutating methods call {@code markDirty()} so vanilla world saving
 * writes the changes automatically.</p>
 *
 * <p>All access is expected from the main server thread (event callbacks, tick handlers,
 * commands). The {@link #forWorld(ServerWorld)} call is cheap after the first one.</p>
 */
public final class DoorRegistry {
    private final Map<RegistryKey<World>, PortalDoorState> states = new HashMap<>();

    /**
     * Returns the persistent door state for the given world, creating and loading it if necessary.
     */
    public PortalDoorState forWorld(ServerWorld world) {
        return states.computeIfAbsent(world.getRegistryKey(), key ->
                world.getPersistentStateManager().getOrCreate(PortalDoorState.STATE_TYPE, PortalDoorState.STATE_ID));
    }

    /** All currently loaded states keyed by dimension (for validation sweeps and diagnostics). */
    public Iterable<Map.Entry<RegistryKey<World>, PortalDoorState>> entries() {
        return states.entrySet();
    }

    public int stateCount() {
        return states.size();
    }

    public int totalCount() {
        int total = 0;
        for (PortalDoorState state : states.values()) {
            total += state.size();
        }
        return total;
    }
}

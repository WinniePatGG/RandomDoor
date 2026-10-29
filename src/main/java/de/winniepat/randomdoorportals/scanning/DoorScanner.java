package de.winniepat.randomdoorportals.scanning;

import de.winniepat.randomdoorportals.RandomDoorPortals;
import de.winniepat.randomdoorportals.config.PortalConfig;
import de.winniepat.randomdoorportals.portal.DoorGeometry;
import de.winniepat.randomdoorportals.registry.DoorRegistry;
import de.winniepat.randomdoorportals.registry.PortalDoorState;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers door blocks on chunk load and keeps the registry in sync with reality.
 *
 * <p>Chunk loads from any thread are queued ({@link ServerChunkEvents#CHUNK_LOAD} may run off
 * the main thread). The queue is drained on the main server tick with two budgets:
 * a fixed number of chunks per tick and a wall-clock millisecond budget. Scans never
 * request chunk loads: a queued chunk that unloaded again is skipped and rediscovered on its
 * next load.</p>
 *
 * <p>Each scan reconciles the chunk's door list: newly found lower door halves are added,
 * previously registered keys that no longer exist in that chunk are removed. That also cleans
 * up doors that were removed by external tools while the chunk was unloaded.</p>
 *
 * <p>A small validation sweep runs every tick and re-checks a bounded number of registered
 * doors that are currently inside loaded chunks, removing entries whose blocks vanished.</p>
 */
public final class DoorScanner {
    private static final int VALIDATION_ENTRIES_PER_TICK = 64;

    private record ChunkRequest(ServerWorld world, int chunkX, int chunkZ) {
    }

    private final DoorRegistry registry;
    private final Queue<ChunkRequest> queue = new ConcurrentLinkedQueue<>();
    private final Set<ChunkRequest> queuedRequests = ConcurrentHashMap.newKeySet();

    private final Map<RegistryKey<World>, List<Long>> sweepLists = new HashMap<>();
    private final Map<RegistryKey<World>, Integer> sweepIndex = new HashMap<>();

    public DoorScanner(DoorRegistry registry) {
        this.registry = registry;
    }

    // ------------------------------------------------------------- event hook

    /** Called for every loaded chunk (any thread). Never does heavy work itself. */
    public void onChunkLoad(ServerWorld world, WorldChunk chunk) {
        PortalConfig config = RandomDoorPortals.CONFIG;
        if (!config.enabled || !config.isDimensionEnabled(world.getRegistryKey().getValue().getPath())) {
            return;
        }
        if (queue.size() >= config.maxQueueSize) {
            if (config.debugLogging) {
                RandomDoorPortals.LOGGER.debug("[RandomDoorPortals] chunk scan queue is full, dropping {}",
                        chunk.getPos());
            }
            return;
        }
        ChunkPos pos = chunk.getPos();
        ChunkRequest request = new ChunkRequest(world, pos.x, pos.z);
        if (queuedRequests.add(request)) {
            queue.offer(request);
        }
    }

    // -------------------------------------------------------------- per tick

    public void tick(MinecraftServer server) {
        PortalConfig config = RandomDoorPortals.CONFIG;
        if (!config.enabled) {
            return;
        }
        long deadline = System.nanoTime() + config.scanTimeBudgetMillis * 1_000_000L;
        int processed = 0;
        while (processed < config.chunkScanBudget && System.nanoTime() < deadline) {
            ChunkRequest request = queue.poll();
            if (request == null) {
                break;
            }
            queuedRequests.remove(request);
            ServerChunkManager chunkManager = request.world.getChunkManager();
            // The chunk may have unloaded again before the scan ran - never force loading.
            if (!chunkManager.isChunkLoaded(request.chunkX(), request.chunkZ())) {
                continue;
            }
            try {
                scanChunk(request.world, request.chunkX(), request.chunkZ(), config);
            } catch (Exception e) {
                RandomDoorPortals.LOGGER.error("[RandomDoorPortals] chunk scan failed at {} {} in {}: {}",
                        request.chunkX(), request.chunkZ(), request.world.getRegistryKey(), e.toString());
            }
            processed++;
        }
        if (config.debugLogging && processed > 0) {
            RandomDoorPortals.LOGGER.debug("[RandomDoorPortals] scanned {} chunk(s) this tick, {} queued, {} doors total",
                    processed, queue.size(), registry.totalCount());
        }
    }

    /**
     * Bounded periodic validation: re-checks a few registered doors per tick, but only when
     * their chunk is currently loaded (list sweeps wrap around per dimension).
     */
    public void validateSome(MinecraftServer server) {
        PortalConfig config = RandomDoorPortals.CONFIG;
        if (!config.enabled) {
            return;
        }
        int budget = VALIDATION_ENTRIES_PER_TICK;
        for (Map.Entry<RegistryKey<World>, PortalDoorState> entry : registry.entries()) {
            if (budget <= 0) {
                break;
            }
            RegistryKey<World> dimensionKey = entry.getKey();
            PortalDoorState state = entry.getValue();
            List<Long> sweep = sweepLists.get(dimensionKey);
            int index = sweepIndex.getOrDefault(dimensionKey, 0);
            if (sweep == null || index >= sweep.size()) {
                sweep = state.snapshot();
                sweepLists.put(dimensionKey, sweep);
                index = 0;
            }
            ServerWorld world = server.getWorld(dimensionKey);
            if (world == null) {
                // Dimension not currently loaded; keep the data, try again later.
                continue;
            }
            int checked = 0;
            while (index < sweep.size() && checked < budget) {
                validateDoor(world, state, sweep.get(index));
                index++;
                checked++;
            }
            sweepIndex.put(dimensionKey, index);
            budget -= checked;
        }
    }

    private void validateDoor(ServerWorld world, PortalDoorState state, long doorKey) {
        int x = DoorGeometry.doorKeyX(doorKey);
        int y = DoorGeometry.doorKeyY(doorKey);
        int z = DoorGeometry.doorKeyZ(doorKey);
        ServerChunkManager chunkManager = world.getChunkManager();
        if (!chunkManager.isChunkLoaded(x >> 4, z >> 4)) {
            return; // Only validated when loaded; a later scan reconciles unloaded chunks.
        }
        BlockState blockState = world.getBlockState(new BlockPos(x, y, z));
        if (!(blockState.getBlock() instanceof DoorBlock)
                || blockState.get(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            state.remove(doorKey);
        }
    }

    // ------------------------------------------------------------- scanning

    /** Iterates the block states of a loaded chunk and reconciles the registry. */
    private void scanChunk(ServerWorld world, int chunkX, int chunkZ, PortalConfig config) {
        PortalDoorState state = registry.forWorld(world);
        WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);

        List<Long> found = new ArrayList<>();
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = chunk.getBottomY();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection section = sections[sectionIndex];
            if (section == null || section.isEmpty()) {
                continue;
            }
            int sectionBaseY = bottomY + sectionIndex * 16;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int ly = 0; ly < 16; ly++) {
                        BlockState blockState = section.getBlockState(lx, ly, lz);
                        if (!(blockState.getBlock() instanceof DoorBlock)) {
                            continue;
                        }
                        if (blockState.get(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                            continue;
                        }
                        found.add(DoorGeometry.encodeDoorKey(
                                chunkX * 16 + lx, sectionBaseY + ly, chunkZ * 16 + lz));
                    }
                }
            }
        }

        int added = 0;
        for (long key : found) {
            if (state.add(key)) {
                added++;
            }
        }
        // Anything registered in this chunk that is not part of the fresh scan is stale.
        List<Long> known = new ArrayList<>();
        state.doorsInChunk(chunkX, chunkZ, known);
        Set<Long> foundSet = new HashSet<>(found);
        int removed = 0;
        for (long key : known) {
            if (!foundSet.contains(key) && state.remove(key)) {
                removed++;
            }
        }
        if (config.debugLogging && (added > 0 || removed > 0)) {
            RandomDoorPortals.LOGGER.debug(
                    "[RandomDoorPortals] chunk {} {}: +{} door(s), -{} stale (total {})",
                    chunkX, chunkZ, added, removed, state.size());
        }
    }
}

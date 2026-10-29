package de.winniepat.randomdoorportals;

import de.winniepat.randomdoorportals.command.RdpCommand;
import de.winniepat.randomdoorportals.config.PortalConfig;
import de.winniepat.randomdoorportals.effects.PortalEffects;
import de.winniepat.randomdoorportals.portal.DoorGeometry;
import de.winniepat.randomdoorportals.registry.DoorRegistry;
import de.winniepat.randomdoorportals.registry.PortalDoorState;
import de.winniepat.randomdoorportals.scanning.DoorScanner;
import de.winniepat.randomdoorportals.teleport.PortalTeleportService;
import de.winniepat.randomdoorportals.tracking.PlayerPositionTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Random Door Portals: walking through any open registered door teleports the player to
 * a different registered door in the same dimension.
 *
 * <p>Fully server-side; vanilla clients do not need any mod installed. All logic runs on
 * the main server thread (the chunk-load queue is the only concurrent structure).</p>
 */
public final class RandomDoorPortals implements ModInitializer {
    public static final String MOD_ID = "randomdoorportals";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Reloadable configuration; replaced wholesale by {@code /rdp reload}. */
    public static volatile PortalConfig CONFIG = PortalConfig.createDefault();

    public static final DoorRegistry PORTALS = new DoorRegistry();

    private static DoorScanner scanner;
    private static PortalTeleportService teleportService;
    private static PlayerPositionTracker tracker;
    private static PortalEffects effects;

    @Override
    public void onInitialize() {
        CONFIG = PortalConfig.loadOrCreate(FabricLoader.getInstance().getConfigDir(), LOGGER);

        effects = new PortalEffects();
        tracker = new PlayerPositionTracker();
        teleportService = new PortalTeleportService(PORTALS, effects, tracker);
        scanner = new DoorScanner(PORTALS);

        // --- door discovery -------------------------------------------------------
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> scanner.onChunkLoad(world, chunk));

        // --- crossing detection + scans + validation on the server tick ----------
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            scanner.tick(server);
            scanner.validateSome(server);
            tracker.tick(server, teleportService);
        });

        // --- tracker lifetimes ----------------------------------------------------
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> tracker.markJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> tracker.markLeave(handler.getPlayer()));

        // --- door removal on block break -----------------------------------------
        PlayerBlockBreakEvents.AFTER.register(RandomDoorPortals::onBlockBroken);

        // --- door registration on first physical use -----------------------------
        UseBlockCallback.EVENT.register(RandomDoorPortals::onDoorUse);

        // --- persistence on shutdown (world save usually handles it already) -----
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                LOGGER.info("[RandomDoorPortals] stopping - persisting {} door(s)",
                        PORTALS.totalCount()));

        // --- commands --------------------------------------------------------------
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                RdpCommand.register(dispatcher));

        LOGGER.info("[RandomDoorPortals] initialized (server-side only)");
    }

    // ------------------------------------------------------------------ events

    private static void onBlockBroken(World world, PlayerEntity player, BlockPos pos,
                                      BlockState state, net.minecraft.block.entity.BlockEntity blockEntity) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        if (!(state.getBlock() instanceof DoorBlock)) {
            return;
        }
        long key = lowerDoorKey(pos, state);
        if (PORTALS.forWorld(serverWorld).remove(key)) {
            if (CONFIG.debugLogging) {
                LOGGER.debug("[RandomDoorPortals] door broken and removed: {}", key);
            }
        }
    }

    private static ActionResult onDoorUse(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return ActionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return ActionResult.PASS;
        }
        long key = lowerDoorKey(pos, state);
        if (PORTALS.forWorld(serverWorld).add(key)) {
            if (CONFIG.debugLogging) {
                LOGGER.debug("[RandomDoorPortals] new door registered on first use: {}", key);
            }
        }
        return ActionResult.PASS;
    }

    /** Normalizes either door half to the lower block position key. */
    private static long lowerDoorKey(BlockPos pos, BlockState state) {
        int y = state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.getY() - 1 : pos.getY();
        return DoorGeometry.encodeDoorKey(pos.getX(), y, pos.getZ());
    }
}

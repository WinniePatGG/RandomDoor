package de.winniepat.randomdoorportals.teleport;

import de.winniepat.randomdoorportals.RandomDoorPortals;
import de.winniepat.randomdoorportals.config.PortalConfig;
import de.winniepat.randomdoorportals.effects.PortalEffects;
import de.winniepat.randomdoorportals.portal.DoorGeometry;
import de.winniepat.randomdoorportals.registry.DoorRegistry;
import de.winniepat.randomdoorportals.registry.PortalDoorState;
import de.winniepat.randomdoorportals.tracking.PlayerPositionTracker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CactusBlock;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CobwebBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FireBlock;
import net.minecraft.block.MagmaBlock;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.block.PowderSnowBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.WitherRoseBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Detects doorway crossings from per-tick position segments and performs the actual teleport.
 *
 * <p>Crossing model: for every registered door near the player's previous/new chunk,
 * the segment is tested against the doorway's mid-plane. All conditions must hold:</p>
 * <ul>
 *   <li>the start point is not already on the plane ({@link DoorGeometry#crossesPlane}),</li>
 *   <li>the crossing point lies inside the doorway cell (horizontal span + two-block height band),</li>
 *   <li>the door must (optionally) be open, and the door block must still exist,</li>
 *   <li>the door's chunk is loaded (blocks are never read from unloaded chunks).</li>
 * </ul>
 *
 * <p>Destination choice: all registered doors of the same dimension except the entrance and
 * the two "recent" doors (when configured), filtered by open state and distance window,
 * in randomized order. The first door that provides a safe arrival spot wins. If no door
 * qualifies, no teleport happens.</p>
 */
public final class PortalTeleportService {
    private final DoorRegistry registry;
    private final PortalEffects effects;
    private final PlayerPositionTracker tracker;

    public PortalTeleportService(DoorRegistry registry, PortalEffects effects, PlayerPositionTracker tracker) {
        this.registry = registry;
        this.effects = effects;
        this.tracker = tracker;
    }

    // ------------------------------------------------------------- detection

    /**
     * Checks whether the segment {@code prevPosition -> player's current position} passes
     * through an open registered doorway, and teleports if a destination qualifies.
     *
     * @return true when the player was teleported (the caller must reset cooldown + previous pos)
     */
    public boolean handleCrossing(ServerPlayerEntity player, double prevX, double prevY, double prevZ) {
        PortalConfig config = RandomDoorPortals.CONFIG;
        if (!config.enabled) {
            return false;
        }
        ServerWorld world = player.getServerWorld();
        if (!config.isDimensionEnabled(world.getRegistryKey().getValue().getPath())) {
            return false;
        }
        PortalDoorState state = registry.forWorld(world);
        if (state.isEmpty()) {
            return false;
        }

        double nowX = player.getX();
        double nowY = player.getY();
        double nowZ = player.getZ();

        int prevChunkX = MathHelper.floor(prevX) >> 4;
        int prevChunkZ = MathHelper.floor(prevZ) >> 4;
        int nowChunkX = MathHelper.floor(nowX) >> 4;
        int nowChunkZ = MathHelper.floor(nowZ) >> 4;

        List<Long> candidates = new ArrayList<>();
        state.doorsInChunk(prevChunkX, prevChunkZ, candidates);
        state.doorsInChunk(nowChunkX, nowChunkZ, candidates);

        Set<Long> seen = new HashSet<>();
        for (long doorKey : candidates) {
            if (!seen.add(doorKey)) {
                continue;
            }
            int doorX = DoorGeometry.doorKeyX(doorKey);
            int doorY = DoorGeometry.doorKeyY(doorKey);
            int doorZ = DoorGeometry.doorKeyZ(doorKey);
            // Never read block state from an unloaded chunk (that would force-load it).
            if (!world.getChunkManager().isChunkLoaded(doorX >> 4, doorZ >> 4)) {
                continue;
            }
            BlockPos doorPos = new BlockPos(doorX, doorY, doorZ);
            BlockState doorBlockState = world.getBlockState(doorPos);
            if (!(doorBlockState.getBlock() instanceof DoorBlock)
                    || doorBlockState.get(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                continue; // stale entry; the periodic validation sweep removes it
            }
            Direction facing = doorBlockState.get(DoorBlock.FACING);
            boolean axisX = facing.getAxis() == Direction.Axis.X;
            double plane = axisX ? doorX + 0.5 : doorZ + 0.5;
            double prevAlong = axisX ? prevX : prevZ;
            double nowAlong = axisX ? nowX : nowZ;
            if (!DoorGeometry.crossesPlane(prevAlong, nowAlong, plane)) {
                continue;
            }
            double t = DoorGeometry.crossingFraction(prevAlong, nowAlong, plane);
            if (t < 0.0) {
                continue;
            }
            double perpPrev = axisX ? prevZ : prevX;
            double perpNow = axisX ? nowZ : nowX;
            double perpAt = perpPrev + t * (perpNow - perpPrev);
            double lowerPerp = axisX ? doorZ : doorX;
            if (!DoorGeometry.withinDoorwaySpan(perpAt, lowerPerp)) {
                continue;
            }
            double yAt = prevY + t * (nowY - prevY);
            if (!DoorGeometry.withinDoorwayHeight(yAt, doorY)) {
                continue;
            }
            if (config.requireEntranceOpen && !doorBlockState.get(DoorBlock.OPEN)) {
                continue;
            }
            if (config.debugLogging) {
                RandomDoorPortals.LOGGER.debug("[RandomDoorPortals] {} crossed door {} ({} {}) in {}",
                        player.getGameProfile().getName(), doorKey, doorX, doorY, doorZ);
            }
            // Even if no destination qualifies below, the doorway was crossed: returning
            // true lets the tracker reset its cooldown and baseline position.
            teleportThrough(world, player, doorKey, doorPos, doorBlockState);
            return true;
        }
        return false;
    }

    private boolean teleportThrough(ServerWorld world, ServerPlayerEntity player, long entranceKey,
                                    BlockPos entrancePos, BlockState entranceDoorState) {
        PortalConfig config = RandomDoorPortals.CONFIG;
        PortalDoorState state = registry.forWorld(world);
        List<Long> pool = state.snapshot();
        if (pool.size() < 2) {
            if (config.debugLogging) {
                RandomDoorPortals.LOGGER.debug("[RandomDoorPortals] no other doors registered in {}",
                        world.getRegistryKey().getValue());
            }
            return false;
        }
        List<Long> options = new ArrayList<>();
        for (long key : pool) {
            if (key == entranceKey) {
                continue;
            }
            options.add(key);
        }
        if (config.avoidRepeatDestinations) {
            long recentEntrance = tracker.recentEntrance(player.getUuid());
            long recentDestination = tracker.recentDestination(player.getUuid());
            List<Long> fresh = new ArrayList<>();
            for (long key : options) {
                if (key != recentEntrance && key != recentDestination) {
                    fresh.add(key);
                }
            }
            if (!fresh.isEmpty()) {
                options = fresh;
            }
        }

        // Randomize the remaining candidates before evaluating the expensive checks.
        Random random = world.getRandom();
        for (int i = options.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            long tmp = options.get(i);
            options.set(i, options.get(j));
            options.set(j, tmp);
        }

        int entranceX = DoorGeometry.doorKeyX(entranceKey);
        int entranceZ = DoorGeometry.doorKeyZ(entranceKey);
        double entranceCenterX = entranceX + 0.5;
        double entranceCenterZ = entranceZ + 0.5;

        for (long destinationKey : options) {
            int destX = DoorGeometry.doorKeyX(destinationKey);
            int destY = DoorGeometry.doorKeyY(destinationKey);
            int destZ = DoorGeometry.doorKeyZ(destinationKey);
            if (!world.getChunkManager().isChunkLoaded(destX >> 4, destZ >> 4)) {
                continue; // never force load; periodic validation cleans these up
            }
            BlockPos destPos = new BlockPos(destX, destY, destZ);
            BlockState destDoorState = world.getBlockState(destPos);
            if (!(destDoorState.getBlock() instanceof DoorBlock)
                    || destDoorState.get(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                state.remove(destinationKey); // opportunistic cleanup
                continue;
            }
            if (config.requireDestinationOpen && !destDoorState.get(DoorBlock.OPEN)) {
                continue;
            }
            double dx = (destX + 0.5) - entranceCenterX;
            double dz = (destZ + 0.5) - entranceCenterZ;
            if (!config.distanceAllowed(Math.sqrt(dx * dx + dz * dz))) {
                continue;
            }
            Optional<Vec3d> arrival = findSafeArrival(world, player, destPos, destDoorState, config);
            if (arrival.isEmpty()) {
                if (config.debugLogging) {
                    RandomDoorPortals.LOGGER.debug(
                            "[RandomDoorPortals] door {} {} has no safe arrival spot", destX, destZ);
                }
                continue;
            }
            Vec3d spot = arrival.get();
            Direction destFacing = destDoorState.get(DoorBlock.FACING);
            player.teleport(world, spot.x, spot.y, spot.z, EnumSet.noneOf(PositionFlag.class),
                    destFacing.asRotation(), player.getPitch());
            if (!player.isFallFlying()) {
                Vec3d velocity = player.getVelocity();
                player.setVelocity(velocity.multiply(1.0, 0.0, 1.0));
                player.setOnGround(true);
            }
            tracker.rememberPortalUse(player.getUuid(), entranceKey, destinationKey);
            effects.playAt(world, entrancePos, false);
            effects.playAt(world, destPos, true);
            if (config.debugLogging) {
                RandomDoorPortals.LOGGER.debug("[RandomDoorPortals] teleported {} from ({}, {}) to ({}, {})",
                        player.getGameProfile().getName(), entranceCenterX, entranceCenterZ, spot.x, spot.z);
            }
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------- arrivals

    /**
     * Searches for a safe standing position next to the destination door. Preference order:
     * the facing side of the door (the room the player walks into when it opens), the opposite
     * side, the two perpendicular sides; each with several distances and a +1 y fallback.
     */
    private Optional<Vec3d> findSafeArrival(ServerWorld world, ServerPlayerEntity player,
                                            BlockPos doorPos, BlockState doorBlockState, PortalConfig config) {
        Direction facing = doorBlockState.get(DoorBlock.FACING);
        List<Direction> sides = new ArrayList<>();
        sides.add(facing);
        sides.add(facing.getOpposite());
        switch (facing.getAxis()) {
            case X -> {
                sides.add(Direction.NORTH);
                sides.add(Direction.SOUTH);
            }
            case Z -> {
                sides.add(Direction.EAST);
                sides.add(Direction.WEST);
            }
            default -> {
                return Optional.empty(); // doors always face horizontally
            }
        }
        double centerX = doorPos.getX() + 0.5;
        double centerZ = doorPos.getZ() + 0.5;
        int index = 0;
        for (Direction side : sides) {
            double[] distances = index < 2
                    ? new double[]{1.05, 1.35, 1.7}
                    : new double[]{0.75, 1.25};
            for (double distance : distances) {
                for (int yOffset = 0; yOffset <= 1; yOffset++) {
                    double x = centerX + (side.getOffsetX() * distance);
                    double z = centerZ + (side.getOffsetZ() * distance);
                    double feetY = doorPos.getY() + yOffset;
                    if (isSafeSpot(world, player, x, feetY, z, config)) {
                        return Optional.of(new Vec3d(x, feetY, z));
                    }
                }
            }
            index++;
        }
        return Optional.empty();
    }

    private boolean isSafeSpot(ServerWorld world, ServerPlayerEntity player,
                               double x, double feetY, double z, PortalConfig config) {
        if (Double.isNaN(x) || Double.isNaN(feetY) || Double.isNaN(z) || Double.isInfinite(feetY)) {
            return false;
        }
        if (feetY < world.getBottomY() || feetY + 2.0 > world.getTopY()) {
            return false;
        }
        Box bodyBox = new Box(x - 0.3, feetY, z - 0.3, x + 0.3, feetY + 1.8, z + 0.3);
        if (!world.getWorldBorder().contains(bodyBox)) {
            return false;
        }
        int minX = MathHelper.floor(bodyBox.minX);
        int maxX = MathHelper.floor(bodyBox.maxX);
        int minY = MathHelper.floor(bodyBox.minY);
        int maxY = MathHelper.floor(bodyBox.maxY);
        int minZ = MathHelper.floor(bodyBox.minZ);
        int maxZ = MathHelper.floor(bodyBox.maxZ);
        // All chunks we are about to read must be loaded, otherwise reading block states
        // would force-load them.
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockPos cell = new BlockPos(bx, by, bz);
                    BlockState cellState = world.getBlockState(cell);
                    VoxelShape shape = cellState.getCollisionShape(world, cell, ShapeContext.absent());
                    if (!shape.isEmpty()) {
                        for (Box occupied : shape.offset(bx, by, bz).getBoundingBoxes()) {
                            if (occupied.intersects(bodyBox)) {
                                return false;
                            }
                        }
                    }
                    if (config.dangerProtection) {
                        if (!world.getFluidState(cell).isEmpty() || isHazardous(cellState)) {
                            return false;
                        }
                    }
                }
            }
        }
        if (config.dangerProtection) {
            // Require ground below the feet (no endless void falls) and no hazard floors.
            boolean floor = false;
            int baseBy = MathHelper.floor(feetY);
            int footX = MathHelper.floor(x);
            int footZ = MathHelper.floor(z);
            for (int depth = 1; depth <= 3 && !floor; depth++) {
                int fy = baseBy - depth;
                if (fy < world.getBottomY()) {
                    return false;
                }
                BlockPos cell = new BlockPos(footX, fy, footZ);
                BlockState cellState = world.getBlockState(cell);
                if (!world.getFluidState(cell).isEmpty() || isHazardous(cellState)) {
                    return false;
                }
                floor = !cellState.getCollisionShape(world, cell, ShapeContext.absent()).isEmpty();
            }
            if (!floor) {
                return false;
            }
        }
        if (!world.getOtherEntities(player, bodyBox,
                e -> e.isAlive() && e instanceof LivingEntity).isEmpty()) {
            return false;
        }
        return true;
    }

    private static boolean isHazardous(BlockState state) {
        Block block = state.getBlock();
        return block instanceof FireBlock
                || block instanceof MagmaBlock
                || block instanceof CactusBlock
                || block instanceof CobwebBlock
                || block instanceof PowderSnowBlock
                || block instanceof CampfireBlock
                || block instanceof SweetBerryBushBlock
                || block instanceof PointedDripstoneBlock
                || block instanceof WitherRoseBlock;
    }
}

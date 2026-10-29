package de.winniepat.randomdoorportals.effects;

import de.winniepat.randomdoorportals.RandomDoorPortals;
import de.winniepat.randomdoorportals.config.PortalConfig;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

/**
 * Portal particles and sounds for both the entrance and the arrival side. Broadcast to all
 * nearby players (this mod is server-side only, so clients cannot play effects locally).
 */
public final class PortalEffects {
    private static final float VOLUME = 0.6f;
    private static final float PITCH = 1.0f;

    /**
     * @param destination true for the arrival side (reverse portal spiral), false for the
     *                    entrance side (ingress portal particles)
     */
    public void playAt(ServerWorld world, BlockPos doorPos, boolean destination) {
        PortalConfig config = RandomDoorPortals.CONFIG;
        double x = doorPos.getX() + 0.5;
        double y = doorPos.getY() + 1.0;
        double z = doorPos.getZ() + 0.5;
        if (config.particlesEnabled) {
            world.spawnParticles(destination ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.PORTAL,
                    x, y, z, 24, 0.35, 0.7, 0.35, 0.4);
        }
        if (config.soundsEnabled) {
            world.playSound(null, doorPos, SoundEvents.BLOCK_PORTAL_TRAVEL, SoundCategory.BLOCKS,
                    VOLUME, PITCH);
        }
    }
}

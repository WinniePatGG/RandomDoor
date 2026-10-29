package de.winniepat.randomdoorportals.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import de.winniepat.randomdoorportals.RandomDoorPortals;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /rdp status} - shows the persisted door count per dimension and the active config.
 * {@code /rdp reload} - reloads {@code config/randomdoorportals.json} (operator only).
 */
public final class RdpCommand {
    private RdpCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("rdp")
                .requires(source -> source.hasPermissionLevel(0))
                .executes(RdpCommand::status)
                .then(CommandManager.literal("status").executes(RdpCommand::status))
                .then(CommandManager.literal("reload")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(RdpCommand::reload)));
    }

    private static int status(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        MinecraftServer server = source.getServer();
        StringBuilder message = new StringBuilder();
        message.append("Random Door Portals\n");
        message.append(RandomDoorPortals.CONFIG.enabled ? "  enabled: yes\n" : "  enabled: NO (doors are inactive)\n");
        int total = 0;
        List<String> dimensionLines = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            long count = RandomDoorPortals.PORTALS.forWorld(world).size();
            total += count;
            String path = world.getRegistryKey().getValue().getPath();
            boolean enabled = RandomDoorPortals.CONFIG.isDimensionEnabled(path);
            dimensionLines.add("  " + path + ": " + count + " door(s)" + (enabled ? "" : " [disabled]"));
        }
        dimensionLines.forEach(line -> message.append(line).append('\n'));
        message.append("  total: ").append(total).append('\n');
        message.append("  cooldown: ").append(RandomDoorPortals.CONFIG.cooldownTicks).append(" tick(s)\n");
        message.append("  distance window: min ")
                .append(RandomDoorPortals.CONFIG.minDistanceBlocks)
                .append(" / max ")
                .append(RandomDoorPortals.CONFIG.maxDistanceBlocks)
                .append(" blocks\n");
        message.append("  scan budget: ").append(RandomDoorPortals.CONFIG.chunkScanBudget)
                .append(" chunk(s) / ").append(RandomDoorPortals.CONFIG.scanTimeBudgetMillis)
                .append(" ms per tick\n");
        source.sendFeedback(() -> Text.literal(message.toString()), false);
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        // Commands already run on the main server thread.
        RandomDoorPortals.CONFIG = de.winniepat.randomdoorportals.config.PortalConfig.loadOrCreate(
                FabricLoader.getInstance().getConfigDir(), RandomDoorPortals.LOGGER);
        RandomDoorPortals.LOGGER.info("[RandomDoorPortals] configuration reloaded via /rdp reload");
        source.sendFeedback(
                () -> Text.literal("Random Door Portals: configuration reloaded"), false);
        return 1;
    }
}

package com.cobblehub.questsync;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Hooks the player-join lifecycle event and applies the first-join teleport when relevant.
 * <p>
 * Flow:
 * <ol>
 *   <li>Player connects, JOIN event fires on the server thread.</li>
 *   <li>We schedule an async DB lookup to avoid stalling the tick loop.</li>
 *   <li>If the configured flag is absent, we hop back to the server thread, teleport the
 *       player to the tutorial location, then write the flag asynchronously.</li>
 * </ol>
 * The tutorial location lives in {@link Config.TutorialLocation} and is editable via JSON.
 */
public final class PlayerJoinHandler {

    private final CobblehubQuestSyncMod mod;
    /** UUIDs we've already evaluated this session, so a reconnect within the same uptime is a no-op. */
    private final Set<UUID> evaluatedThisSession = new HashSet<>();

    public PlayerJoinHandler(CobblehubQuestSyncMod mod) {
        this.mod = mod;
    }

    public void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            onPlayerJoin(player);
        });

        // Clear the in-memory cache on stop so a server restart re-evaluates everybody.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> evaluatedThisSession.clear());
    }

    private void onPlayerJoin(ServerPlayer player) {
        Config cfg = mod.config();
        if (!cfg.firstJoin.enabled) return;
        if (!cfg.mysql.enabled) {
            CobblehubQuestSyncMod.LOGGER.debug("MySQL disabled — skipping first-join check for {}.", player.getGameProfile().getName());
            return;
        }
        Database db = mod.database();
        if (db == null) return;

        UUID uuid = player.getUUID();
        if (!evaluatedThisSession.add(uuid)) return;

        String flagKey = cfg.firstJoin.flagKey;
        String playerName = player.getGameProfile().getName();

        CobblehubQuestSyncMod.async(() -> {
            boolean hasFlag;
            try {
                hasFlag = db.hasFlag(uuid, flagKey);
            } catch (Exception e) {
                CobblehubQuestSyncMod.LOGGER.error("Failed to read flag '{}' for {}; skipping teleport.", flagKey, playerName, e);
                return;
            }
            if (hasFlag) {
                CobblehubQuestSyncMod.LOGGER.debug("Player {} has flag '{}', leaving spawn behaviour intact.", playerName, flagKey);
                return;
            }

            // Hop back to the main server thread for the teleport — world mutations are not thread-safe.
            player.getServer().execute(() -> teleportToTutorial(player, cfg.firstJoin.tutorialLocation, playerName));

            // Write the flag asynchronously after teleport request — minor race window if the player
            // disconnects between the two, but the worst case is being teleported again on next join.
            try {
                db.setFlag(uuid, flagKey);
                CobblehubQuestSyncMod.LOGGER.info("First join recorded for {} (flag '{}').", playerName, flagKey);
            } catch (Exception e) {
                CobblehubQuestSyncMod.LOGGER.error("Failed to persist flag '{}' for {}.", flagKey, playerName, e);
            }
        });
    }

    private void teleportToTutorial(ServerPlayer player, Config.TutorialLocation loc, String playerName) {
        ResourceLocation dimId = ResourceLocation.tryParse(loc.dimension);
        if (dimId == null) {
            CobblehubQuestSyncMod.LOGGER.error("Invalid dimension id '{}' in config — aborting teleport for {}.", loc.dimension, playerName);
            return;
        }
        ResourceKey<Level> worldKey = ResourceKey.create(Registries.DIMENSION, dimId);
        ServerLevel world = player.getServer().getLevel(worldKey);
        if (world == null) {
            CobblehubQuestSyncMod.LOGGER.error("Dimension '{}' is not loaded — aborting teleport for {}.", loc.dimension, playerName);
            return;
        }

        player.teleportTo(world, loc.x, loc.y, loc.z, loc.yaw, loc.pitch);
        CobblehubQuestSyncMod.LOGGER.info("Teleported {} to tutorial location {} ({}, {}, {}).",
                playerName, loc.dimension, loc.x, loc.y, loc.z);
    }
}

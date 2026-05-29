package com.cobblehub.questsync;

import dev.architectury.event.EventResult;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bridges FTB Quests progression to the shared MySQL store.
 * <p>
 * Two responsibilities:
 * <ol>
 *   <li><b>Capture:</b> register on {@link ObjectCompletedEvent#TASK}. When a task is
 *       completed on this server, record a row per online team member so the progress
 *       follows them across the network.</li>
 *   <li><b>Replay:</b> at player join, fetch the player's previously completed task IDs
 *       from the database and force-complete the corresponding tasks on this server's
 *       team data via {@link TeamData#setCompleted(long, Date)}.</li>
 * </ol>
 * <p>
 * Re-application uses {@code setCompleted} directly, which only writes to the internal
 * completion map. It does <b>not</b> invoke {@code Task.onCompleted()} (the path that
 * fires the event), so we cannot create an infinite write-replay loop. It also does
 * not trigger reward auto-claim — that logic lives in {@code Quest.onCompleted()},
 * a separate code path. Players get their rewards exactly once, on the server where
 * they originally completed the task.
 * <p>
 * Task IDs are persisted as their canonical 16-char hex string ({@code Long.toHexString}
 * with the FTB convention of uppercase, zero-padded). That string is stable across
 * server restarts and matches what FTB writes in its own SNBT files, so it's the
 * right identifier to share between two servers running the same quest book.
 */
public final class QuestSyncListener {

    private final CobblehubQuestSyncMod mod;

    public QuestSyncListener(CobblehubQuestSyncMod mod) {
        this.mod = mod;
    }

    public void register() {
        // We only care about Task-level completions, not whole quests or chapters.
        // Completing the last task of a quest implicitly completes the quest via FTB's
        // internal cascade — but we don't need to mirror that; replaying tasks is enough
        // to reconstruct quest-level completion at the next join.
        ObjectCompletedEvent.TASK.register(this::onTaskCompleted);

        // Replay hook. We use FTB Teams' own PLAYER_LOGGED_IN event because it is
        // guaranteed to fire AFTER the player's team data has been initialised and
        // synchronised — exactly when we need it. Hooking ServerPlayConnectionEvents.JOIN
        // would be too early (TeamData.get would throw because the team doesn't exist yet).
        TeamEvent.PLAYER_LOGGED_IN.register(event -> replayForPlayer(event.getPlayer()));

        CobblehubQuestSyncMod.LOGGER.info("Registered FTB Quests event listeners (capture + replay).");
    }

    private EventResult onTaskCompleted(ObjectCompletedEvent.TaskEvent event) {
        if (!mod.config().mysql.enabled) return EventResult.pass();
        Database db = mod.database();
        if (db == null) return EventResult.pass();

        Task task = event.getTask();
        if (task == null) return EventResult.pass();
        // FTB's id-as-hex format. This is the stable identifier across servers.
        String taskId = idToHex(task.id);
        String serverName = mod.config().serverName;

        // The event carries the team's currently-online members. In solo-teams mode
        // (which is our target deployment), this is exactly one player — the one who
        // triggered the completion. If a future setup uses multi-member teams, every
        // online member sees the completion in DB and the next join on the other
        // server replays it for them too. Offline members will catch up the next time
        // they log in, since FTB's local team data is what feeds future events.
        List<ServerPlayer> members = event.getOnlineMembers();
        if (members.isEmpty()) return EventResult.pass();

        for (ServerPlayer player : members) {
            UUID uuid = player.getUUID();
            String name = player.getGameProfile().getName();
            CobblehubQuestSyncMod.async(() -> {
                try {
                    db.recordTaskCompleted(uuid, taskId, serverName);
                    CobblehubQuestSyncMod.LOGGER.debug("Recorded task {} completed for {}.", taskId, name);
                } catch (Exception e) {
                    CobblehubQuestSyncMod.LOGGER.error("Failed to record task {} for {}.", taskId, name, e);
                }
            });
        }
        // We are a passive observer — never interrupt the FTB event chain.
        return EventResult.pass();
    }

    /**
     * Replays previously completed tasks for a player onto the local server's team data.
     * Called from {@link PlayerJoinHandler} after any first-join teleport is resolved,
     * so the player is in the right world before progression catches up.
     */
    public void replayForPlayer(ServerPlayer player) {
        if (!mod.config().mysql.enabled) return;
        Database db = mod.database();
        if (db == null) return;

        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        MinecraftServer server = player.getServer();
        if (server == null) return;

        CobblehubQuestSyncMod.async(() -> {
            Set<String> remoteCompleted;
            try {
                remoteCompleted = db.getCompletedTaskIds(uuid);
            } catch (Exception e) {
                CobblehubQuestSyncMod.LOGGER.error("Failed to read quest progress for {}.", name, e);
                return;
            }
            if (remoteCompleted.isEmpty()) {
                return;
            }

            // World mutations need the server thread.
            server.execute(() -> applyOnServerThread(player, remoteCompleted, name));
        });
    }

    private void applyOnServerThread(ServerPlayer player, Set<String> remoteCompleted, String name) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null) {
            CobblehubQuestSyncMod.LOGGER.warn("ServerQuestFile.INSTANCE is null — skipping replay for {}.", name);
            return;
        }

        TeamData teamData;
        try {
            teamData = TeamData.get(player);
        } catch (Exception e) {
            // TeamData.get throws if the player's team isn't initialised yet. This can
            // happen at the very first JOIN tick before FTB Teams has built the solo
            // team. We just skip — the next interaction with the quest book will
            // self-heal because we'll have already written the data to disk via DB.
            CobblehubQuestSyncMod.LOGGER.warn("TeamData not ready yet for {}; replay deferred.", name);
            return;
        }

        Date now = new Date();
        int applied = 0;
        int alreadyDone = 0;
        int unknown = 0;

        for (String taskHex : remoteCompleted) {
            Long taskId = hexToId(taskHex);
            if (taskId == null) {
                CobblehubQuestSyncMod.LOGGER.warn("Malformed task id '{}' in DB for {} — skipping.", taskHex, name);
                continue;
            }
            Task task = file.getTask(taskId);
            if (task == null) {
                // The other server has a task that we don't — quest books out of sync.
                // Log once and move on; the player will be informed by the quest book itself.
                unknown++;
                continue;
            }
            if (teamData.isCompleted(task)) {
                alreadyDone++;
                continue;
            }
            // setCompleted only updates internal state — does NOT fire Task.onCompleted,
            // so this loop will not trigger another ObjectCompletedEvent.TASK on us.
            if (teamData.setCompleted(taskId, now)) {
                applied++;
            }
        }

        if (applied > 0 || unknown > 0) {
            CobblehubQuestSyncMod.LOGGER.info(
                    "Replay for {}: applied={}, already_done={}, unknown_tasks={}.",
                    name, applied, alreadyDone, unknown);
        }
    }

    // ---------------- ID encoding helpers ----------------

    /**
     * FTB Quests stores its long object IDs as 16-char uppercase hex strings in SNBT.
     * We use the same format in MySQL so the values are human-recognisable and match
     * what an admin would see when reading the quest book files on disk.
     */
    private static String idToHex(long id) {
        return String.format("%016X", id);
    }

    private static Long hexToId(String hex) {
        try {
            // Use Long.parseUnsignedLong to handle the high bit correctly.
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

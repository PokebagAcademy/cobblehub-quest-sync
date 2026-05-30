package com.cobblehub.questsync;

import dev.architectury.event.EventResult;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bridges FTB Quests progression to the shared MySQL store.
 * <p>
 * Three responsibilities:
 * <ol>
 *   <li><b>Capture:</b> register on {@link ObjectCompletedEvent#TASK}. When a task is
 *       completed on this server, record a row per online team member so the progress
 *       follows them across the network.</li>
 *   <li><b>Replay:</b> at player join, fetch the player's previously completed task IDs
 *       from the database and force-complete the corresponding tasks on this server's
 *       team data via {@link TeamData#setCompleted(long, Date)}, then cascade the
 *       completion up to the parent quest and chapter.</li>
 *   <li><b>Reward-claim bookkeeping (v0.2.3):</b> after the cascade, pre-populate this
 *       server's local {@code claimedRewards} map for every reward of every quest we
 *       just synced. Without this, FTB's own {@code checkQuestBookOnLogin} would see
 *       "quest complete + reward not in claimedRewards" the next login and auto-claim
 *       the reward again — a clean duplicate.</li>
 * </ol>
 */
public final class QuestSyncListener {

    private final CobblehubQuestSyncMod mod;

    public QuestSyncListener(CobblehubQuestSyncMod mod) {
        this.mod = mod;
    }

    public void register() {
        ObjectCompletedEvent.TASK.register(this::onTaskCompleted);
        // Replay hook. We use FTB Teams' own PLAYER_LOGGED_IN event because it is
        // guaranteed to fire AFTER the player's team data has been initialised and
        // synchronised — exactly when we need it. FTB Quests also hooks this same
        // event for its checkQuestBookOnLogin sweep; since FTB Quests is a dependency,
        // it loads (and registers) before us, so its listener runs first on each
        // invocation. That's why we pre-populate claimedRewards on every cascade
        // (see step 3 of the class doc) — so the NEXT login won't trigger duplicate
        // auto-claims.
        TeamEvent.PLAYER_LOGGED_IN.register(event -> replayForPlayer(event.getPlayer()));
        CobblehubQuestSyncMod.LOGGER.info("Registered FTB Quests event listeners (capture + replay).");
    }

    private EventResult onTaskCompleted(ObjectCompletedEvent.TaskEvent event) {
        if (!mod.config().mysql.enabled) return EventResult.pass();
        Database db = mod.database();
        if (db == null) return EventResult.pass();

        Task task = event.getTask();
        if (task == null) return EventResult.pass();
        String taskId = idToHex(task.id);
        String serverName = mod.config().serverName;

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
        return EventResult.pass();
    }

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
            CobblehubQuestSyncMod.LOGGER.warn("TeamData not ready yet for {}; replay deferred.", name);
            return;
        }

        Date now = new Date();
        int tasksApplied = 0;
        int alreadyDone = 0;
        int unknown = 0;

        Set<Quest> affectedQuests = new HashSet<>();

        for (String taskHex : remoteCompleted) {
            Long taskId = hexToId(taskHex);
            if (taskId == null) {
                CobblehubQuestSyncMod.LOGGER.warn("Malformed task id '{}' in DB for {} — skipping.", taskHex, name);
                continue;
            }
            Task task = file.getTask(taskId);
            if (task == null) {
                unknown++;
                continue;
            }
            if (teamData.isCompleted(task)) {
                alreadyDone++;
                affectedQuests.add(task.getQuest());
                continue;
            }
            if (teamData.setCompleted(taskId, now)) {
                tasksApplied++;
                affectedQuests.add(task.getQuest());
            }
        }

        // Cascade quest completion silently.
        Set<Chapter> affectedChapters = new HashSet<>();
        int questsCompleted = 0;
        for (Quest quest : affectedQuests) {
            if (quest == null) continue;
            if (!teamData.isCompleted(quest) && quest.isCompletedRaw(teamData)) {
                if (teamData.setCompleted(quest.id, now)) {
                    questsCompleted++;
                    affectedChapters.add(quest.getChapter());
                }
            }
        }

        int chaptersCompleted = 0;
        for (Chapter chapter : affectedChapters) {
            if (chapter == null) continue;
            if (!teamData.isCompleted(chapter) && chapter.isCompletedRaw(teamData)) {
                if (teamData.setCompleted(chapter.id, now)) {
                    chaptersCompleted++;
                }
            }
        }

        // ---- Mark rewards as already-claimed on this server ----
        // Without this step, the very next time this player logs in, FTB Quests'
        // own checkQuestBookOnLogin scans every quest and unconditionally calls
        // data.checkAutoCompletion(quest). For any quest that's marked complete
        // locally but whose reward isn't in this server's local claimedRewards map,
        // FTB happily auto-claims it again — that's how players were getting
        // duplicate diamonds. We pre-populate the claimedRewards map for every
        // reward of every quest we've touched, as long as that quest is now
        // considered complete locally.
        //
        // We iterate the FULL affectedQuests set (not just newly-completed ones)
        // so that this also heals leftover state from v0.2.2 — quests that ended
        // up complete on this server but whose rewards never got marked. The
        // markRewardAsClaimed call is idempotent (no-op if already in the set).
        //
        // This applies to MANUAL rewards too: by marking them claimed locally,
        // the "Claim Reward" button is disabled on this server. The cost is that
        // a player who completes a task on server A but switches to server B
        // before clicking Claim will only be able to claim on A — they have to
        // go back. That's an acceptable trade-off; the alternative is duplication.
        long nowMs = now.getTime();
        int rewardsMarked = 0;
        UUID uuid = player.getUUID();
        for (Quest quest : affectedQuests) {
            if (quest == null || !teamData.isCompleted(quest)) continue;
            for (Reward reward : quest.getRewards()) {
                if (teamData.markRewardAsClaimed(uuid, reward, nowMs)) {
                    rewardsMarked++;
                }
            }
        }

        if (tasksApplied > 0 || unknown > 0 || questsCompleted > 0 || chaptersCompleted > 0 || rewardsMarked > 0) {
            CobblehubQuestSyncMod.LOGGER.info(
                    "Replay for {}: tasks={}, quests={}, chapters={}, rewards_marked={}, already_done={}, unknown_tasks={}.",
                    name, tasksApplied, questsCompleted, chaptersCompleted, rewardsMarked, alreadyDone, unknown);
        }
    }

    private static String idToHex(long id) {
        return String.format("%016X", id);
    }

    private static Long hexToId(String hex) {
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

# Architecture

Detailed technical reference for how the mod hooks into FTB Quests and what each event listener does. Pairs with the source-level Javadoc in `QuestSyncListener.java`.

## High-level flow

```
  Player completes a task on server A
            │
            ▼
  FTB Quests fires ObjectCompletedEvent.TASK
            │
            ▼                                         (server A's thread)
  QuestSyncListener.onTaskCompleted
            │
            └──▶ dispatch to worker pool
                                                       (off-thread)
                  │
                  ▼
              Database.recordTaskCompleted
                  │
                  ▼
           INSERT INTO cobblehub_quest_progress ...

  --- some time later, player switches to server B ---

  Player joins server B
            │
            ▼
  FTB Teams fires TeamEvent.PLAYER_LOGGED_IN
            │
            ▼                                         (server B's thread)
  QuestSyncListener.replayForPlayer
            │
            └──▶ dispatch to worker pool
                                                       (off-thread)
                  │
                  ▼
              Database.getCompletedTaskIds
                  │
                  ▼
           SELECT task_id FROM cobblehub_quest_progress WHERE player_uuid = ?
                  │
                  └──▶ dispatch result back to server thread
                                                       (server B's thread)
                        │
                        ▼
                    applyOnServerThread:
                      1. setCompleted(taskId) for each task
                      2. setCompleted(quest.id) if quest.isCompletedRaw(teamData)
                      3. setCompleted(chapter.id) if chapter.isCompletedRaw(teamData)
                      4. markRewardClaimedCompat(reward) for each reward of each affected quest
```

## Why we use `setCompleted` directly (not the normal `Task.onCompleted`)

When FTB Quests completes a task through its normal flow (e.g. a player kills the right mob), the call chain is:

```
Task.submitTask
  → TeamData.markTaskCompleted
     → Task.onCompleted
        → ObjectCompletedEvent.TASK.invoker().act(event)   ← our capture hook
        → Quest.onCompleted if all tasks done
           → TeamData.checkAutoCompletion(quest)
              → reward.claim(player)                       ← gives the actual item
           → ObjectCompletedEvent.QUEST.invoker()
           → Chapter.onCompleted if all quests done
              → ObjectCompletedEvent.CHAPTER.invoker()
```

On the replay side, we call `TeamData.setCompleted(id, time)` directly. That function only mutates the internal completed-set; it does NOT invoke `Task.onCompleted`. This is critical for two reasons:

1. **No event loop.** If `setCompleted` fired `ObjectCompletedEvent.TASK`, our own capture handler would record the replay as a new completion in the database. On every login, every task in the database would re-fire the event — forever growing fan-out.
2. **No reward auto-claim.** Without invoking `Task.onCompleted`, we also skip `checkAutoCompletion` and `reward.claim()`. The player already got their rewards on the originating server. We don't want to give them out again.

The price is that we have to redo the cascade work ourselves — marking the quest and chapter complete — because nothing else will do it for us.

## Why we pre-mark rewards as claimed

FTB Quests has a separate code path that runs at every player login: `ServerQuestFile.checkQuestBookOnLogin`. It iterates every quest and unconditionally calls `data.checkAutoCompletion(quest)`. For any quest that's marked complete locally but whose reward isn't in this server's local `claimedRewards` map, `checkAutoCompletion` happily auto-claims it.

This is what caused the v0.2.2 duplication bug. The cascade marked the quest complete on server B but didn't touch `claimedRewards`. At every login, FTB re-claimed the reward.

The fix is to populate `claimedRewards` on the replay side at the same time we cascade the completion. The method to call (`markRewardAsClaimed` or `claimReward` depending on version) writes the entry to `claimedRewards` without calling `reward.claim()`.

This also disables manual-claim buttons on the non-originating server, which is the intentional trade-off documented in the README.

## Reflection: why `markRewardClaimedCompat` exists

FTB Quests renamed `TeamData.claimReward(UUID, Reward, long)` to `TeamData.markRewardAsClaimed(UUID, Reward, long)` between 2101.1.15 and 2101.1.20. Behaviour is identical.

The mod is compiled against 2101.1.24 (newer name). If a server runs 2101.1.3-2101.1.15 (older name), a direct call generates `NoSuchMethodError` at runtime. v0.2.3 hit this on the production CobbleHub server.

Fix: resolve the method by reflection on first call, cache the `Method` reference, log which name was found. Failure mode is graceful (warn and skip the de-dup, never crash).

```java
for (String n : new String[] { "markRewardAsClaimed", "claimReward" }) {
    try {
        Method candidate = TeamData.class.getMethod(n, UUID.class, Reward.class, long.class);
        if (candidate.getReturnType() == boolean.class) {
            // found
            break;
        }
    } catch (NoSuchMethodException ignored) { /* try next */ }
}
```

## Why we hook `TeamEvent.PLAYER_LOGGED_IN` and not `ServerPlayConnectionEvents.JOIN`

At the moment Fabric's join event fires, `TeamData.get(player)` throws because FTB Teams hasn't built the player's solo team yet. FTB Teams fires its own `PLAYER_LOGGED_IN` event specifically AFTER team initialisation, which is exactly what we need.

FTB Quests itself uses the same event to drive `checkQuestBookOnLogin`. Our listener is registered after FTB Quests (because we depend on it), so on the same login, FTB's listener fires first. That's fine for capture, but it means we can't intercept the very first login's auto-claim on a server that already has a quest marked complete — see "One-time zombie state" in the README.

## Thread safety

- The `Database` class wraps a HikariCP pool. All public methods are safe to call from any thread. Connections are returned to the pool via try-with-resources.
- The `WORKER` executor in `CobblehubQuestSyncMod` has 2 threads. Tasks submitted to it never touch the world; they only do DB I/O and then dispatch results back via `server.execute(...)`.
- All world / TeamData mutations are done on the server thread, dispatched via `MinecraftServer.execute(...)`.
- The reflection cache uses double-checked locking with a `volatile` flag.

## File-system writes (FTB side)

We never write to `world/ftbquests/<uuid>.snbt` directly. FTB Quests' own dirty-flag machinery (`markDirty` / `saveIfChanged`) handles persistence: every `setCompleted` and `markRewardAsClaimed` call sets the dirty flag, and FTB writes the SNBT periodically and on shutdown.

This means a server crash between the cascade and the next save could lose the local state — but the source of truth is in MySQL, so the next login will re-replay and re-cascade. No data is permanently lost.

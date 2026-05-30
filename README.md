# CobbleHub Quest Sync

Cross-server quest progression sync, MySQL-backed flags, and first-join teleport for the [CobbleHub](https://cobblehub.fr) Minecraft network.

> **Target**: Minecraft 1.21.1 · Fabric · Java 21 · server-side only
>
> **Status**: v0.2.4 — quest sync feature-complete and validated end-to-end.

---

## What it does

The CobbleHub network runs two parallel Minecraft backends (`main` and `monde`) behind a Velocity proxy. Players hop between them with `/server`, and we want their FTB Quests progression to follow them. This mod is the bridge.

### ✅ Features

- **First-join teleport.** When a player connects to a server for the very first time, they're teleported to a configurable tutorial location. The "first time" flag is persisted in MySQL so it survives restarts and is checked once per join (single indexed lookup).
- **Cross-server quest progression sync.** When a player completes a quest task on one backend, the completion is recorded in MySQL. When they log in on the other backend, the mod replays the completions onto that server's `TeamData` — tasks, quests, and chapters are all marked complete silently.
- **Reward de-duplication.** Rewards are pre-marked as claimed on the replay side, so:
  - Auto-claim rewards are never given twice.
  - Manual-claim rewards have their "Claim" button disabled on servers other than the one the player completed the task on.
- **FTB Quests version-agnostic.** Reflection-based dispatch for the one method that was renamed mid-series (2101.1.15 → 2101.1.20: `claimReward` → `markRewardAsClaimed`). The mod works on any 2101.1.x point release without recompilation.

### 🚫 What it explicitly does NOT do

- **No client-side code.** This is a server-only mod. Players don't need to install anything.
- **No team-level sync.** Tasks are synced **per player**. The deployment assumes solo teams (FTB Teams in solo mode).
- **No quest book file sync.** The SNBT files in `config/ftbquests/quests/` must be kept identical on both servers manually. See [Quest book workflow](#quest-book-workflow) below.
- **No real-time sync.** A player must log out of server A and log into server B for progress to propagate. There's no live cross-server push.

---

## How it works (architecture overview)

Three threads of execution, decoupled:

1. **Capture (server thread)** — `ObjectCompletedEvent.TASK` fires when a player completes any FTB Quests task. We extract the task ID and the team's online members, then queue a DB write on the worker pool.
2. **Worker pool (off-thread)** — a 2-thread executor handles all MySQL I/O. Captures write rows; replays read them. Worker tasks never touch the world directly.
3. **Replay (server thread)** — `TeamEvent.PLAYER_LOGGED_IN` from FTB Teams fires after the player's TeamData is initialised. We load their completed task IDs from MySQL on the worker pool, then dispatch the world mutation (calling `TeamData.setCompleted(...)`) back to the server thread.

The replay phase also **cascades** the completion up the tree:
- For every task we just marked complete, check whether its parent quest is now fully done. If so, mark the quest complete.
- Same for chapter → mark complete if all its quests are done.
- For every affected quest, pre-populate the local `claimedRewards` map for each of its rewards. This is the de-duplication guard.

The cascade is silent: it calls `setCompleted` directly on the TeamData object, which only mutates internal state. It does NOT invoke `Task.onCompleted()` (which fires the event), so the cascade never loops back into our own capture. It also bypasses `checkAutoCompletion`, which is what would normally give the player the reward — because we pre-fill `claimedRewards` first, FTB sees the reward as already claimed and skips it.

Full rationale, including the FTB Quests source paths we hook, is in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## Requirements

### Runtime (on each server)

- Minecraft 1.21.1, Fabric loader 0.16.0+
- Java 21
- Fabric API
- **FTB Quests** (any 2101.1.x — tested against 2101.1.3 and 2101.1.24)
- **FTB Library** (any 2101.1.x)
- **FTB Teams** (any 2101.1.x)
- **Architectury API** (any 13.0.x for Fabric 1.21.1)
- MySQL or MariaDB instance, reachable from each server

### Build

- JDK 21
- Internet access on first build (Fabric Loom downloads MC + mappings)
- The four FTB/Architectury jars in `libs/` (committed to the repo)

We do NOT depend on a Maven repo for FTB. Their `saps.dev` instance currently 404s for Fabric artifacts, so we ship the jars locally at build time. They are `modCompileOnly` only — the build never bundles FTB classes into our output.

---

## Installation

1. **Build the jar** (see [Building](#building)) or grab a release from the GitHub Releases page when one is published.
2. **Drop `cobblehub-quest-sync-<version>.jar`** into the `mods/` folder of each server.
3. **Start each server once** to generate the default config at `config/cobblehub-quest-sync.json`.
4. **Edit each config** with the MySQL credentials and per-server settings (see [Configuration](#configuration)).
5. **Restart** each server.
6. Verify in the log:
   ```
   [cobblehub_quest_sync/]: CobblehubQuestSync v<version> ready.
   [cobblehub_quest_sync/]: MySQL pool ready and schema migrated (prefix='cobblehub_').
   [cobblehub_quest_sync/]: Registered FTB Quests event listeners (capture + replay).
   ```

No database migration is needed — the mod runs `CREATE TABLE IF NOT EXISTS` at startup and is forward-compatible with itself.

---

## Configuration

Auto-generated at `config/cobblehub-quest-sync.json` on first boot. Edit, restart.

```json
{
  "serverName": "main",
  "mysql": {
    "enabled": true,
    "host": "sql.example.com",
    "port": 3306,
    "database": "your_db",
    "username": "your_user",
    "password": "your_pass",
    "tablePrefix": "cobblehub_",
    "poolMaxSize": 4,
    "poolMinIdle": 1,
    "connectionTimeoutMs": 5000
  },
  "firstJoin": {
    "enabled": true,
    "flagKey": "first_join_main",
    "tutorialLocation": {
      "dimension": "minecraft:overworld",
      "x": 0.5, "y": 100.0, "z": 0.5,
      "yaw": 0.0, "pitch": 0.0
    }
  }
}
```

### Field reference

| Field                       | Type    | Purpose                                                                                              |
|-----------------------------|---------|------------------------------------------------------------------------------------------------------|
| `serverName`                | string  | Stored in `last_server` on quest progress rows. Helpful for debugging which backend wrote which row. |
| `mysql.enabled`             | bool    | Set to `true` to actually open the pool. When `false`, the mod boots in no-op mode.                  |
| `mysql.host/port/database`  | strings | Connection target.                                                                                   |
| `mysql.username/password`   | strings | Credentials. *Don't commit your real ones to git.*                                                   |
| `mysql.tablePrefix`         | string  | Default `cobblehub_`. Change if sharing the DB with other prefixed tables.                           |
| `mysql.poolMaxSize`         | int     | Hikari max connections. 4 is generous for this workload.                                             |
| `mysql.poolMinIdle`         | int     | Hikari min idle. 1 keeps cold-start latency low.                                                     |
| `mysql.connectionTimeoutMs` | int     | How long to wait for a connection before giving up. 5000 ms is fine.                                 |
| `firstJoin.enabled`         | bool    | Per-server toggle for the first-join TP. Set `false` on backends where you don't want the TP.        |
| `firstJoin.flagKey`         | string  | **Must be unique per server** (e.g. `first_join_main`, `first_join_monde`). See note below.          |
| `firstJoin.tutorialLocation`| object  | Where to teleport on first join. `dimension` is a full registry ID.                                  |

### Per-server config requirements

- **`flagKey` must differ between servers.** A player's first join on `main` and their first join on `monde` are independent events. Using the same key would make the second backend skip the TP because the flag is already set from the first one.
- **`serverName` should differ between servers.** It's not load-bearing for correctness, but identical values make `last_server` debugging useless.

---

## Quest book workflow

The sync works by referring to FTB Quests' internal task IDs (the 16-character hex strings you see in SNBT). For sync to work, **the quest book SNBT files must be identical on both servers**. Same task IDs, same chapter IDs.

If you create a quest in-game on one server and recreate it from scratch on the other, FTB will generate different random IDs and the sync will silently log `unknown_tasks` instead of replaying.

### Recommended flow

1. **Designate one server as the editor.** `main` is the obvious choice.
2. **Edit the quest book on that server only.** Either in-game with `/ftbquests editing_mode` (recommended for creation), or directly via SFTP for batch edits.
3. **Copy `config/ftbquests/quests/` from main → monde.** Specifically:
   - `data.snbt`
   - `chapter_groups.snbt` (if you use chapter groups)
   - `chapters/*.snbt`
   - `reward_tables/*.snbt` (if you use reward tables)
   - `lang/*.snbt` (if you have translations)
4. **Reload on both servers**: `/ftbquests reload`

Do NOT copy `world/ftbquests/` — that's per-player progression and stays per-server.

### Things to avoid

- Editing the quest book on both servers simultaneously — you'll get divergent IDs.
- Running `/ftbquests reload` while players are interacting with the book — their client may go out of sync.

---

## Database schema

Two tables, both auto-created at startup. Full reference in [`docs/SCHEMA.md`](docs/SCHEMA.md).

### `<prefix>player_flags`

Generic per-player boolean storage. Used today for first-join detection; reusable for any one-shot state.

| Column      | Type        | Notes                       |
|-------------|-------------|-----------------------------|
| player_uuid | CHAR(36)    | PK part 1                   |
| flag_key    | VARCHAR(64) | PK part 2                   |
| flag_value  | TINYINT(1)  | 1 = set                     |
| set_at      | TIMESTAMP   | Default `CURRENT_TIMESTAMP` |

### `<prefix>quest_progress`

FTB Quests task completions, the source of truth for the sync feature.

| Column       | Type        | Notes                                                       |
|--------------|-------------|-------------------------------------------------------------|
| player_uuid  | CHAR(36)    | PK part 1                                                   |
| task_id      | VARCHAR(96) | PK part 2. Hex string from FTB (`String.format("%016X", id)`) |
| progress     | BIGINT      | Raw progress value; 1 for completed checkmark tasks         |
| completed    | TINYINT(1)  | 1 once the task is done                                     |
| last_updated | TIMESTAMP   | `ON UPDATE CURRENT_TIMESTAMP` for conflict resolution       |
| last_server  | VARCHAR(32) | Which backend last wrote this row                           |

Index `idx_player_completed(player_uuid, completed)` for fast "all completed tasks for this player" lookups at login.

---

## Building

```bash
# Clone
git clone https://github.com/PokebagAcademy/cobblehub-quest-sync.git
cd cobblehub-quest-sync

# Build
./gradlew clean build

# Output
ls build/libs/
# → cobblehub-quest-sync-<version>.jar          ← ship this one
# → cobblehub-quest-sync-<version>-shadow-dev.jar  ← intermediate, ignore
```

### Build internals

- **Gradle 8.10.2** pinned via the wrapper. Don't upgrade to 9 — Fabric Loom 1.7 is incompatible.
- **JDK 21** is required. Older JDKs fail at compile time (Java 21 language features used).
- **Fabric Loom 1.7-SNAPSHOT** — official Fabric mod build tool. Uses Mojang mappings.
- **Shadow plugin 8.1.1** — bundles MariaDB connector + HikariCP into the mod jar with relocated package names (`com.cobblehub.questsync.libs.*`) to avoid conflicts with other mods. `mergeServiceFiles()` is enabled so the JDBC `Driver` SPI keeps working post-relocation.
- **SLF4J is intentionally NOT shaded.** It's resolved from Minecraft's runtime classpath. Shading it would relocate the references inside HikariCP/MariaDB and cause them to find no provider — v0.2.0 had exactly this bug (silent NOP logger).
- **`modCompileOnly files('libs/*.jar')`** — FTB Quests, FTB Library, FTB Teams, and Architectury are linked at compile time from local jars in `libs/`. They are NOT bundled in the output (the server already has them installed).

### Repository layout

```
.
├── build.gradle              # plugin setup, dependencies, shadow rules
├── gradle.properties         # mod version, MC version, dep versions
├── libs/                     # FTB + Architectury jars (modCompileOnly)
│   ├── architectury-13.0.8-fabric.jar
│   ├── ftb-library-fabric-2101.1.31.jar
│   ├── ftb-teams-fabric-2101.1.9.jar
│   └── ftb-quests-fabric-2101.1.24.jar
├── src/main/java/com/cobblehub/questsync/
│   ├── CobblehubQuestSyncMod.java   # ModInitializer entry point
│   ├── Config.java                  # JSON config loader
│   ├── Database.java                # HikariCP pool + DDL + DAO methods
│   ├── PlayerJoinHandler.java       # first-join TP
│   └── QuestSyncListener.java       # capture + replay + cascade + reward de-dup
├── src/main/resources/
│   └── fabric.mod.json              # mod metadata, runtime deps
└── docs/
    ├── ARCHITECTURE.md
    ├── SCHEMA.md
    └── RELEASE.md
```

---

## Limitations & known issues

### Reward sync trade-off

When the mod marks a reward as claimed on the replay side, it does so for **all** rewards of the synced quest — both auto-claim and manual. The consequence:

- If you complete a quest's task on server A and want to grab a manual reward, you **must click "Claim Reward" on server A** before switching to server B.
- On server B, the quest will appear complete but the manual reward's "Claim" button will be disabled.

This is intentional. The alternative is duplication (claim the reward on both servers). We picked the conservative side.

### Quest book divergence

If the SNBT files on the two servers ever diverge (different task IDs for the same quest), the sync will log lines like:
```
Replay for <player>: ... unknown_tasks=3
```
and the player won't see their progress on the second server. The fix is always: re-sync the SNBT files. The mod doesn't try to detect or repair this automatically.

### One-time zombie state after upgrade

Versions before v0.2.3 didn't pre-mark rewards as claimed. If you upgrade an existing deployment, players who already have quests marked complete via the cascade but whose rewards aren't in `claimedRewards` will get one re-claim on the next login per server. The state self-stabilises after that single login.

If you want a clean slate, delete `world/ftbquests/<player-uuid>.snbt` on the affected server before the player logs in.

---

## Troubleshooting

### `[cobblehub_quest_sync/]` never appears in the log

The mod failed to load. Check earlier in the log for a stack trace from Fabric loader. Common causes:
- Missing dependency (FTB Quests, FTB Library, FTB Teams, or Architectury).
- Wrong Minecraft / Fabric version.

### `MySQL init failed` at startup

Check the config:
- `host`, `port`, `database`, `username`, `password` must be correct.
- The MySQL user needs `CREATE`, `SELECT`, `INSERT`, `UPDATE` privileges on the database.
- The server's outbound network must reach the MySQL host on the chosen port. Some hosting providers firewall outbound 3306 by default.

The mod will boot in no-op mode if the DB init fails — first-join TP and quest sync will both be inactive until you fix the config and restart.

### Players see quest tasks reset when switching servers

The SNBT files on the two servers have diverged. Re-sync them (see [Quest book workflow](#quest-book-workflow)) and run `/ftbquests reload` on both.

### `NoSuchMethodError: TeamData.markRewardAsClaimed`

You're running v0.2.3 or earlier. Upgrade to v0.2.4+, which uses reflection to handle the rename in FTB Quests 2101.1.20.

### Player keeps getting teleported to the tutorial on every login

The `flagKey` for that server is misconfigured. If it's empty or identical to another server's key, the flag check breaks. Set it to something unique like `first_join_<servername>` and restart.

Also possible: the player has no row in `<prefix>player_flags` for this flag and the DB write is failing silently (look for `Failed to record first-join flag` in the log).

### Rewards getting duplicated

This was the v0.2.2 bug. Make sure you're on v0.2.4+, which pre-marks rewards as claimed on cascade. If you still see duplication on v0.2.4+, send a log and the affected quest's SNBT — there might be a custom reward type we don't handle.

---

## Version history

| Version | Date       | Summary                                                                                              |
|---------|------------|------------------------------------------------------------------------------------------------------|
| v0.1.0  | 2026-05-29 | First-join teleport with MySQL-backed flags.                                                         |
| v0.2.0  | 2026-05-29 | FTB Quests progression sync. Capture + replay. **Silent NOP logger bug** — do not use.               |
| v0.2.1  | 2026-05-30 | Fixed silent logger: stopped shading SLF4J, rely on Minecraft's runtime.                             |
| v0.2.2  | 2026-05-30 | Cascade replay up to parent quest and chapter; first-join `enabled` flag honoured per server.        |
| v0.2.3  | 2026-05-30 | Pre-mark rewards as claimed to prevent duplication. **NoSuchMethodError on FTB 2101.1.x &lt; 1.20** — do not use. |
| v0.2.4  | 2026-05-31 | Reflection-based dispatch for `markRewardAsClaimed` / `claimReward`. Compatible with any 2101.1.x.   |

---

## License

All rights reserved (for now). Internal project for the CobbleHub network.

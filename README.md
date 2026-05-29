# cobblehub-quest-sync

Cross-server quest progression sync and first-join teleport for the CobbleHub network.

Target: **Minecraft 1.21.1 / Fabric / Java 21** on Arclight backends behind a Velocity proxy.

## What it does

- **First-join teleport** — when a player connects to a server for the first time, teleports them to a configurable tutorial location. The "first time" check is backed by MySQL so it persists across restarts and is cheap to query (single indexed lookup per join).
- **Quest progress sync** (planned for v1) — bridges FTB Quests progression between the `main` and `monde` backends via the shared LuckPerms MySQL database.

## Architecture

The mod owns a HikariCP connection pool to a MySQL/MariaDB database. All blocking I/O runs on a dedicated thread pool so the server tick loop is never blocked. World mutations (teleports) are always dispatched back to the server thread.

### Tables

Auto-created at startup via `CREATE TABLE IF NOT EXISTS`. The prefix is configurable (default `cobblehub_`).

- `cobblehub_player_flags(player_uuid, flag_key, flag_value, set_at)` — generic per-player boolean flags. Used by the first-join feature today; reusable for any other one-shot state.
- `cobblehub_quest_progress(player_uuid, task_id, progress, completed, last_updated, last_server)` — quest task state, for the sync feature landing in v1.

### Config

JSON, generated on first boot at `config/cobblehub-quest-sync.json`. Edit, restart.

```json
{
  "serverName": "main",
  "mysql": {
    "enabled": false,
    "host": "sql3.minestrator.com",
    "port": 3306,
    "database": "CHANGE_ME",
    "username": "CHANGE_ME",
    "password": "CHANGE_ME",
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

**The `flagKey` must be unique per server.** `first_join_main` for main, `first_join_monde` for monde, etc. That's how the same player can have a separate "first join" detection on each backend.

## Building

```bash
./gradlew build
# Output: build/libs/cobblehub-quest-sync-<version>.jar
```

The build shadows MariaDB Java Connector, HikariCP, and SLF4J into the jar under relocated package names (`com.cobblehub.questsync.libs.*`) so the mod has no conflicts with libraries shipped by other mods.

Gradle 8.10.2 is pinned via the wrapper because Fabric Loom 1.7 is not compatible with Gradle 9.

## Versions

- **v0.1.0** — first-join teleport with MySQL-backed flags. *Currently deployed.*
- **v0.2.0** (planned) — FTB Quests progress sync between main and monde.
- **v0.3.0** (planned) — admin commands (`/cobblehub settutoloc`, `/cobblehub resetflag <player> <flag>`).

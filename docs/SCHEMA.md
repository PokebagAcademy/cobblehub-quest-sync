# Database schema

Auto-created at startup via `CREATE TABLE IF NOT EXISTS`. The DDL in `Database.java#migrate()` is the source of truth; this file is reference documentation.

Default table prefix is `cobblehub_` (configurable via `mysql.tablePrefix`).

## `<prefix>player_flags`

Generic per-player boolean flags. Today this is used by the first-join feature. It's intentionally generic so we can reuse it for other one-shot state without a migration.

```sql
CREATE TABLE IF NOT EXISTS cobblehub_player_flags (
  player_uuid CHAR(36)    NOT NULL,
  flag_key    VARCHAR(64) NOT NULL,
  flag_value  TINYINT(1)  NOT NULL DEFAULT 1,
  set_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (player_uuid, flag_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

| Column      | Type        | Notes                                  |
|-------------|-------------|----------------------------------------|
| player_uuid | CHAR(36)    | PK part 1. String form, lowercase, with dashes. |
| flag_key    | VARCHAR(64) | PK part 2.                             |
| flag_value  | TINYINT(1)  | Always 1. We never store 0 explicitly. |
| set_at      | TIMESTAMP   | Default `CURRENT_TIMESTAMP`.           |

**Key convention**: `<feature>_<server>` (e.g. `first_join_main`, `first_join_monde`). This is what keeps per-server flags independent.

## `<prefix>quest_progress`

FTB Quests state, mirrored to the database so it can be replayed on the other backend.

```sql
CREATE TABLE IF NOT EXISTS cobblehub_quest_progress (
  player_uuid  CHAR(36)    NOT NULL,
  task_id      VARCHAR(96) NOT NULL,
  progress     BIGINT      NOT NULL DEFAULT 0,
  completed    TINYINT(1)  NOT NULL DEFAULT 0,
  last_updated TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  last_server  VARCHAR(32) NULL,
  PRIMARY KEY (player_uuid, task_id),
  INDEX idx_player_completed (player_uuid, completed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

| Column       | Type        | Notes                                                                                |
|--------------|-------------|--------------------------------------------------------------------------------------|
| player_uuid  | CHAR(36)    | PK part 1.                                                                           |
| task_id      | VARCHAR(96) | PK part 2. The FTB Quests internal id in hex: `String.format("%016X", task.id)`.     |
| progress     | BIGINT      | Raw progress value. Currently we only write 1 for completed tasks; finer granularity is room for a future iteration. |
| completed    | TINYINT(1)  | 1 once the task hits its completion threshold.                                       |
| last_updated | TIMESTAMP   | `ON UPDATE CURRENT_TIMESTAMP`. Used for conflict resolution (last-write-wins).       |
| last_server  | VARCHAR(32) | Free-form server name from config (`main`, `monde`). For debugging.                  |

**Index**: `idx_player_completed(player_uuid, completed)` for fast "all completed tasks for this player" queries at login.

## Conflict resolution policy

Last-write-wins by `last_updated`. Acceptable because:
- A player can only be online on one backend at a time (Velocity routing enforces this).
- Tasks tracked are mostly monotonic (catch counters, advancement flags) — re-applying a stale completion is idempotent.
- The cost of a mistake is minor (an extra task marked complete) and self-heals on the next login.

The sync feature is intentionally **one-way per session**: write on completion (server-of-truth), read on login (replay target). There is no concurrent multi-master writing.

## What we do NOT store

- **Reward claim state** is NOT mirrored to MySQL. It's reconstructed implicitly on replay by `markRewardClaimedCompat` for every reward of every synced quest. This avoids a third table and keeps the sync logic simple, at the cost of the limitation documented in the README: a player who completes a manual-claim task on server A must claim on A; the button is disabled on B.
- **Task progress** (the `progress` column) is reserved for future use. Currently we only write completion-state booleans — finer-grained progress for partial tasks (e.g. 3/10 catches) is not yet synced.
- **Quest started timestamps** are NOT mirrored. FTB Quests stores these locally per server; they only affect the GUI and reset behaviour.

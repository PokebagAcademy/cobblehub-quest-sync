# Database schema

Auto-created by the mod at startup. This file is reference documentation, not the source of truth — see `Database.java#migrate()` for the live DDL.

## `<prefix>player_flags`

Generic per-player boolean flag storage. Used today for first-join detection; reusable for any one-shot state we want to persist across the network.

| Column      | Type        | Notes                                |
|-------------|-------------|--------------------------------------|
| player_uuid | CHAR(36)    | PK part 1                            |
| flag_key    | VARCHAR(64) | PK part 2                            |
| flag_value  | TINYINT(1)  | 1 = set. We never store 0 explicitly. |
| set_at      | TIMESTAMP   | Default CURRENT_TIMESTAMP            |

Key convention: `<feature>_<server>` (e.g. `first_join_main`, `first_join_monde`).

## `<prefix>quest_progress`

FTB Quests state, mirrored to the database so it can be replayed on the other backend. Wired up in v0.2.0.

| Column       | Type        | Notes                                                    |
|--------------|-------------|----------------------------------------------------------|
| player_uuid  | CHAR(36)    | PK part 1                                                |
| task_id      | VARCHAR(96) | PK part 2. Stable id from FTB Quests (chapter:quest:task) |
| progress     | BIGINT      | Raw progress value; meaning depends on task type         |
| completed    | TINYINT(1)  | 1 once the task hits its completion threshold            |
| last_updated | TIMESTAMP   | ON UPDATE CURRENT_TIMESTAMP for conflict resolution      |
| last_server  | VARCHAR(32) | Which backend wrote this row last                        |

Index `idx_player_completed(player_uuid, completed)` for fast "give me all completed tasks for this player" lookups at login.

## Conflict resolution policy (v0.2.0)

Last-write-wins by `last_updated`. Acceptable here because:
- A player can only be online on one backend at a time (Velocity routing).
- Tasks tracked are mostly monotonic (catch counters, advancement flags) — re-applying a stale completion is idempotent.
- The cost of a mistake is minor (extra task completion, never a regression).

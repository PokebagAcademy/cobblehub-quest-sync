# Release process

Manual flow used until CI is set up.

## Build locally

```bash
./gradlew clean build
```

Produces `build/libs/cobblehub-quest-sync-<version>.jar` — the remapped, shaded jar to ship.
A `-shadow-dev` jar is also produced as an intermediate; ignore it.

### Verifying the jar

A correctly-built jar contains:
- Our 5 mod classes under `com/cobblehub/questsync/`
- ~430 shaded classes under `com/cobblehub/questsync/libs/` (MariaDB connector + HikariCP)
- **Zero** classes under `dev/ftb/mods/`, `dev/architectury/`, or `org/slf4j/`

Quick check:
```bash
JAR=build/libs/cobblehub-quest-sync-<version>.jar
unzip -l "$JAR" | grep -cE 'dev/ftb/mods|dev/architectury|org/slf4j'   # should be 0
unzip -l "$JAR" | grep -cE 'libs/(mariadb|hikari)'                       # should be ~430
unzip -p "$JAR" fabric.mod.json | jq .depends                            # ftbquests, ftblibrary, ftbteams, architectury
```

## Deploy to the CobbleHub network

The panel's `sftp_upload_from_url` requires HTTPS. Until releases are hosted on GitHub Releases:

1. Upload the jar to a temp file host accepting `.zip` (e.g. litterbox.catbox.moe with `time=1h`).
2. Delete the previous version on each backend:
   - `CobbleHub` → `mods/cobblehub-quest-sync-<old>.jar`
   - `CobbleHub_Monde` → `mods/cobblehub-quest-sync-<old>.jar`
3. Upload the new jar via `sftp_upload_from_url` to each backend.
4. Restart each backend (main first, then monde).

## First-boot config

The mod regenerates `config/cobblehub-quest-sync.json` only if missing. Existing configs are preserved; new fields backfill via `Config.loadOrCreate()`.

If a new version introduces a new mandatory field, document it here and in the README's version history.

## Validation

### Boot log

```
[cobblehub_quest_sync/]: CobblehubQuestSync v<version> starting...
[cobblehub_quest_sync/]: Loaded config. serverName='<name>', mysql.enabled=true, firstJoin.enabled=<bool>, firstJoin.flagKey='<key>'
[cobblehub_quest_sync/]: MySQL pool ready and schema migrated (prefix='cobblehub_').
[cobblehub_quest_sync/]: Registered FTB Quests event listeners (capture + replay).
[cobblehub_quest_sync/]: CobblehubQuestSync v<version> ready.
```

If MySQL init fails the mod boots in no-op mode — fix config and restart.

### Functional test (quest sync)

With both servers running and a test quest book in place:
1. Connect to `main`, complete a checkmark task with a reward.
2. Verify the reward was given exactly once.
3. `/server monde` (or disconnect and reconnect).
4. Open the quest book — the task should appear complete, the quest's cadre should be green, and the reward should NOT be granted again.
5. Verify the log on monde shows:
   ```
   Replay for <player>: tasks=1, quests=1, chapters=<n>, rewards_marked=1, already_done=0, unknown_tasks=0.
   ```

### Functional test (first-join)

1. Connect with a test account that has never been on the server.
2. The player should be teleported to the configured tutorial coords.
3. Disconnect, reconnect.
4. The player should stay at the last known location (no second TP).

## Deployment history

| Version | Date       | Notes                                                                       |
|---------|------------|-----------------------------------------------------------------------------|
| v0.1.0  | 2026-05-29 | First-join TP. Main only initially, then Monde.                             |
| v0.2.0  | 2026-05-29 | Quest sync MVP. Hit silent-logger bug, output looked like a no-op.          |
| v0.2.1  | 2026-05-30 | Logger fix.                                                                 |
| v0.2.2  | 2026-05-30 | Cascade fix.                                                                |
| v0.2.3  | 2026-05-30 | Pre-mark rewards as claimed. Hit `NoSuchMethodError` on FTB 2101.1.3.       |
| v0.2.4  | 2026-05-31 | Reflection fix. Validated end-to-end on CobbleHub + CobbleHub_Monde.        |

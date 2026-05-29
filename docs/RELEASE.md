# Release process

This document captures the manual release flow used until we set up CI.

## Build locally

```bash
./gradlew clean build
```

Produces `build/libs/cobblehub-quest-sync-<version>.jar` (the remapped, shaded jar — the one to ship). A `-shadow-dev` jar is also produced as an intermediate; ignore it.

## Deploy to the CobbleHub network

The panel's `sftp_upload_from_url` requires an HTTPS source. Until we host releases on GitHub Releases, the flow is:

1. Upload the jar to a temporary file host that accepts `.zip` (e.g. litterbox.catbox.moe with `time=1h`).
2. From the panel, call `sftp_upload_from_url` against each backend:
   - `CobbleHub` → `mods/cobblehub-quest-sync-<version>.jar`
   - `CobbleHub_Monde` → `mods/cobblehub-quest-sync-<version>.jar`
3. Remove the previous version's jar from `mods/` on each backend (file name change is enough — Fabric matches by mod id, not file name, but stale jars cause loader warnings).
4. Restart each backend in turn. **Main first**, then Monde.

## First-boot config wiring

On the very first start with a new version, the mod regenerates the default config only if `config/cobblehub-quest-sync.json` is missing. Existing configs are preserved; new fields fall back to defaults via the backfill logic in `Config.loadOrCreate()`.

If a new version introduces new mandatory fields, document them here.

## Validating a deployment

After restart, verify via RCON:

```
mods                       # 'CobbleHub Quest Sync' should appear in the list
```

And via SFTP:

```
config/cobblehub-quest-sync.json   # exists, contains expected values
```

Then connect with a test account. First connection should teleport to the configured tutorial coords. Second connection should *not* teleport.

## v0.1.0 deployment log

- Built locally in sandbox, Gradle 8.10.2 + Loom 1.7 + Java 21.
- Jar size: 5,530,841 bytes (sha256 d70f61bc8b5fd3419cf1b2c07511f78fff90f028a29ee1a25f5ac52c7ce78f9e).
- Deployed to CobbleHub (main) on 2026-05-29.
- First-join TP and MySQL persistence validated end-to-end.
- CobbleHub_Monde: jar deployed, awaiting per-server config wiring and restart.

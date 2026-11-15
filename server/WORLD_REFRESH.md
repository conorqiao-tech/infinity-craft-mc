# Keeping the existing world through updates

The launcher does not regenerate terrain. On every ordinary start, including after an Infinity mod update (new version or JAR checksum), it leaves every saved world as it is: all dimensions, region (terrain, builds, chests), POI and entity files, player saves and Infinity mod data. Repeated starts behave the same way.

Earlier launchers could regenerate unprotected terrain after a mod update when a private `.world-refresh.json` policy had `"enabled": true`, or when `"refresh_next_start": true` was set. That automatic regeneration has been removed. A policy approved for an earlier version does not approve a refresh now. If the file still requests a refresh, startup prints a notice and does not act on it. The launcher reads the file only to print that notice. It does not edit, reset or delete it, even if the file is unreadable or malformed. There is no replacement setting, background job or panel option that turns regeneration back on. Any future refresh needs its own design and the owner's explicit approval for that operation.

Startup still works as before. The launcher takes its exclusive lock, makes the stopped-world ZIP backup under `backups/` and then starts Java.

Before Java opens, an ordinary start without an interrupted earlier refresh does not regenerate, move or rewrite world files. Recovery of an interrupted earlier refresh may restore or move its previously archived terrain, as described below. It does not prevent Minecraft or mods from changing those files while the world is open. Minecraft saves chunks, players and data as people play, and it can upgrade or rewrite saved records when a new Minecraft version opens the world. Use the startup backups to restore an earlier state.

## Interrupted refreshes from earlier launchers

An older launcher may have been stopped partway through a refresh. In that case, a `.world-refresh-transaction.json` journal and a `backups/Terrain-Refresh-*` folder remain. Recovery runs before the startup backup and before Java opens:

- **Interrupted while moving terrain:** recovery puts the original terrain back from the recovery folder and writes a new `RECOVERY-*.json` record with state `rolled_back`. The original manifest and policy are kept unchanged.
- **Refresh completed but not yet recorded:** recovery writes a new record with state `committed`. The original manifest and `.world-refresh.json` policy are kept unchanged; startup may still report an old refresh request, but does not act on it. The world is left in the state that earlier refresh produced.

Recovery does not start a new refresh, and the next start does not either. If the journal is invalid, or if both the archived and the current copy of some terrain are missing, startup stops before the backup and before Java opens. Restore from the verified full backup recorded in that folder's `MANIFEST.json` and `RECOVERY.md`.

The retained recovery folders, original manifests, journals and the full-backup checksums they record are not deleted. Recovery records are additive and written before journal retirement: several records may exist after interrupted attempts, and records with the same journal hash can belong to separate attempts. A record or retired journal alone does not prove that recovery completed safely. Keep any `.RECOVERY-*.partial` files and launcher marker for host review when recovery is uncertain.

## Minecraft version conversion

Historical record: for explore.48, a separate private world copy was converted from Minecraft 26.3 **DataVersion 5023** to Snapshot 5 **DataVersion 5125**, with no player logins or terrain refresh. Offline player files and mod-state files stayed byte-identical; helper identity, equipment and edited stats, saved difficulty and the untouched original copy were verified. That proves only that private conversion case, not every possible save or any live installation. This document does not state which version a live server currently runs.

**Never open a converted Snapshot 5 world with Minecraft 26.3.** After Snapshot 5 has opened the save, reverting JARs or restoring only a terrain archive is not a rollback. Save and stop, preserve the newer world separately, then restore the verified **complete preupgrade world and its matching old executable stack**, including the old launcher and dependencies. An older launcher still honors its refresh policy, so set `enabled` to `false` before running an older launcher on restored terrain. Keep player, global, entity and helper data from the same backup together; do not combine old chunks with converted metadata.

## Exact-name owner bans

The independent private `.owner-join-rules.json` accepts `{"format":1,"blocked_names":["AccountName"]}`. The launcher watches native authenticated Java login records, matching complete account names without case sensitivity. The existing Floodgate `.` prefix is recognized for Bedrock; a matching joined account receives a native account ban and disconnect. Chat, partial names and unrelated display labels do not trigger it. Remove a name from this policy and use the normal native pardon command if the owner later allows that account. Public packages include no blocked names or policies.

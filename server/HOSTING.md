# Running your community server

The package now has the player features of a small survival community server. Installing it does **not** publish a server address or make your Mac run 24/7. There is no hosting account, purchased domain, or port forwarding configured by this build.

## Set up the community

1. Extract the server ZIP into a permanent folder and open the normal double-click launcher. Set the **server name**, **rules**, **player slots**, and **difficulty** in Server settings. The default slot limit is 32; begin with a few players and measure performance before increasing it.
2. Start the world after accepting the EULA if you agree. Join as Java 1.21.11 or a Bedrock version supported by the pinned bridge.
3. In the host-command box, run `op YourPlayerName` for the trusted owner. A Bedrock name normally begins with a dot, such as `.YourName`. This gives broad administrative powers; ordinary players should remain non-operators.
4. In Minecraft, find an empty, reasonably flat **25×25 grassy area** and run `/community buildspawn`. This reshapes grass/dirt/snow and clears that area to make a lit plaza. It refuses other blocks, water, trees, and containers. Alternatively, build your own welcome area and use `/community setspawn` while standing on solid ground.
5. Walk to places such as a market or arena and run `/community setwarp market` or `/community setwarp arena`. Players use `/warps` and `/warp market`.
6. Read `/rules`, test `/sethome`, `/home`, and a consenting `/tpa` between one Java and one Bedrock player before inviting a wider group.

Vanilla spawn protection is set to 32 blocks on a new configuration. It requires at least one operator and concerns block edits in the Overworld spawn area. This is **not** full land claiming, explosion rollback, an anti-cheat system, or protection for player bases. Staff still need to moderate a public world. Infinity equipment is unusually powerful, so start with trusted players and review its effects before opening access broadly.

## Connect from outside your home

The panel shows a LAN address for devices on your network. That address does not work over the Internet.

On an Apple Silicon Mac, keep the normal world launcher running, then open **Start-Pinggy-Mac.command** and follow [PINGGY_JOINING.md](PINGGY_JOINING.md). It verifies and starts the optional free helper without account sign-in, then requests separate Java TCP and Bedrock UDP addresses. The addresses expire after about an hour; check the panel for their current values and test each from outside your network before inviting friends. Availability depends on the free service.

Alternatively, for home hosting, configure your router and firewall to direct **TCP 25565** and **UDP 19132** to this computer (or your chosen ports). Share the router's public address with invited players. Java uses the Java port; Bedrock uses the Bedrock port. A custom DNS name can point to the same public address. Keep the computer awake and the launcher open. If your ISP uses carrier-grade NAT, normal router port forwarding may not be available; use a host that provides a reachable address and both TCP and UDP ports.

For an always-on server, use a Linux computer or a hosting service that supports **custom Fabric 1.21.11 plus a separate Geyser Java process**, Java 21, enough RAM for both processes, and the two protocol ports. Paper-only plugin hosting will not load the Infinity Fabric mod. The Geyser [standalone setup guide](https://geysermc.org/wiki/geyser/setup/) explains the bridge model; this package configures its local connection and Floodgate key automatically.

The host panel binds to 127.0.0.1 only. Do not forward its browser port. On a remote machine, use console mode or an SSH tunnel to the port printed by `--dashboard --no-browser`. Keep its session URL private.

## Optional Linux service

`infinity.service.example` is a template, not an installed service. Have the host administrator replace the example user and `/srv/infinity` path, make that user own the extracted server folder, and verify a manual start first. Run `bash start.sh --console` interactively, review the EULA, and type `agree` only if you accept it. Stop the world cleanly before enabling a service.

The example starts console mode, restarts after an unexpected process failure, and sends SIGINT to the launcher for saving on shutdown. It does not auto-accept the EULA or expose extra ports. The sample service has not been run on this Mac.

Console mode reads ordinary `fabric/server.properties`. Configure it through the panel first or edit the file while stopped. Community text is in `fabric/config/infinity-community.json`. Player homes/warps/mutes are stored with the world in `infinity-community.json` and backed up with it.

## Staff and backups

The panel shows connected player names and average tick time once the server runs. A healthy 20-tick-per-second server has a 50 ms tick budget; persistent values near or above that budget call for fewer players, lower simulation/view distances, or stronger hosting. The slot count alone is not evidence of capacity. This release has not been load-tested with 32 players.

Panel moderation includes kick, ban/unban, a 10-minute public-chat mute, unmute, and allowlist changes. These issue server commands; inspect the log for their results. Mutes do not block private messages. The built-in public-chat rate limit allows a message about every 1.5 seconds. The console accepts normal Minecraft commands for more advanced administration.

If using the allowlist, add the trusted owner and test names before enabling it. Bedrock players should join once before adding their cached Floodgate names. Apply the allowlist setting on restart. Keep Java account authentication enabled; the launcher enforces that setting.

Before every start of an existing world, the launcher makes a ZIP backup while the world is stopped. The panel can make additional stopped-world backups. Backups contain the selected world and moderation/property files, not server JARs or Floodgate keys. Keep the original server folder for the complete installation, and copy important backups to another disk. Backups are not automatically deleted, so monitor free disk space. There are no periodic live-world backups in this build.

To restore, stop the server, retain the current world under another name, extract a chosen backup **into a separate folder**, inspect its `BACKUP_INFO.json` for the world path, then copy that world into `fabric` and set `level-name` to its directory. Restore moderation files only if you want those older settings as well. Keep the same mod version initially. Never extract over a running world.

## What was checked

Native Minecraft tests cover the new player commands, consent/cooldowns, location safety, persistence, chat moderation, permission checks, and plaza generation. Launcher tests cover configuration, backups, and control-panel authorization. See VALIDATION.md. Real authenticated cross-edition play and large-player-count performance still need testing before a public launch.

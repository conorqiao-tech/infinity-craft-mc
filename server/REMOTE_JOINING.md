# Remote joining from another Wi-Fi network

The optional Mac companion uses the official Playit 1.0.10 agent. It forwards Java and Bedrock connections to the shared world on your Mac. Keep the Mac awake, connected to the Internet, and running Minecraft while friends play. LAN joining continues to work with the existing addresses.

Remote joining remains pending until the agent is claimed in your Playit account and both tunnels have assigned addresses. The supplied source includes the companion launcher; it expects the official native `playitd` and `playit-cli` binaries in `.runtime/playit`. It does not download an agent or install a system service.

1. Open **Start-Mac.command** and start the shared world using the usual panel.
2. Open **Start-Remote-Joining-Mac.command**. It starts a private background agent and prints an official `https://playit.gg/claim/...` link if the account is not connected.
3. Open that link in your browser, sign in to your Playit account, and approve this agent. Complete any account verification Playit requests.
4. The companion requests the two free Minecraft game presets. If Playit restricts creation to the signed-in browser, open [Tunnels](https://playit.gg/account/tunnels), choose **Minecraft Java** and **Minecraft Bedrock**, and enter the local destinations below. Leave PROXY protocol set to **None**. Reopen the companion to read their assigned public addresses. Java players use the **Java** host and port. iPad, iPhone, Android, and Windows Bedrock players add the **Bedrock** host and its separate UDP port in Minecraft's Servers tab.

The Java tunnel is the **Minecraft Java** preset, TCP, with local destination `127.0.0.1:25565`. The Bedrock tunnel is the **Minecraft Bedrock** preset, UDP, with local destination `127.0.0.1:19132`. If you changed the game ports in Settings, setup reads those saved ports. Both use one port, no PROXY protocol, and the existing Geyser/Floodgate connection. No router port forwarding or public control-panel exposure is configured. Setup reuses matching tunnels; it does not rewrite unrelated tunnels. Check current availability and account limits in [Playit's tunnel panel](https://playit.gg/account/tunnels).

Assigned addresses have not passed a connection test until a player connects from a different network. Test Java and Bedrock separately. A phone hotspot is useful for the first outside-network check. If a tunnel is inactive, finish Playit verification or address its account limits. Keep the ordinary Minecraft world running and check the local Java/Bedrock addresses first when troubleshooting.

## Optional terminal controls

Run these from the server folder with its prepared Python runtime:

```sh
.runtime/python/bin/python3 remote_joining.py setup
.runtime/python/bin/python3 remote_joining.py start
.runtime/python/bin/python3 remote_joining.py status
.runtime/python/bin/python3 remote_joining.py stop
```

`setup` is the default and claims/configures the agent when needed. `start` only starts the existing agent; it does not create tunnels or change an account. `status` reports safe agent status and assigned addresses. `stop` checks the saved PID, actual executable, exact private configuration paths, and process start identity before stopping the companion. It leaves Minecraft and LAN joining running. The remote daemon is detached from Terminal and is managed separately from **Save & Stop** in the Minecraft panel. Stop it before moving the server folder; reopening the remote launcher starts it again.

Credentials live only in `.remote/playit.toml`, created by the official agent with permissions `0600`; the `.remote` directory uses `0700`. Logs and the process record use `0600`. Existing credentials are preserved. The companion only prints the claim URL, not guest login/session links or keys. Do not share `.remote`, upload its logs publicly, or include it in a downloadable server bundle. Public joining addresses are safe to share with invited players.

The agent and CLI behavior comes from the [official Playit source, version 1.0.10](https://github.com/playit-cloud/playit-agent/tree/v1.0.10). Remote joining is an optional addition; this companion does not upgrade the existing world, change server versions, or alter the normal launcher.

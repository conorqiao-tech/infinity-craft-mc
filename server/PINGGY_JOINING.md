# Temporary remote joining without sign-in

Keep the ordinary shared Minecraft world running with **Start-Mac.command**. Open **Start-Pinggy-Mac.command** to start separate Java TCP and Bedrock UDP tunnels. No account sign-in is requested. On an Apple Silicon Mac, its first run downloads the official, pinned Pinggy CLI v0.5.9 over HTTPS (about 93 MiB), verifies the full SHA-256 hash and ARM64 executable format, then installs it under `.runtime/pinggy` with owner-only permissions. Later runs verify and reuse that copy. No system install or Minecraft world change is needed.

Open **Start-Mac.command** first so the bundled Python runtime is ready. The first Pinggy setup needs Internet access. If downloading or verification fails, nothing partial is installed; reopen the Pinggy launcher after checking the connection. A file already at `.runtime/pinggy/pinggy` with a different checksum, architecture, or a symlink is refused and preserved for inspection. This companion's automatic installer currently supports Apple Silicon Macs only.

Share the **Java** address with Java players. Bedrock/iPad players add the **Bedrock** host and its separate port in Minecraft's Servers tab. Each edition is reported independently: an allocated Java address does not mean Bedrock is ready. Test each from another network before treating it as verified.

Free tunnels expire after approximately one hour. Keep the Mac awake and online. Open the Pinggy launcher again after expiry to obtain fresh addresses, and share the new host and port. Addresses are temporary; automatic reconnection is disabled. Free-service availability and simultaneous tunnel limits may prevent one edition from starting.

**Stop-Pinggy-Mac.command** stops only `infinity-java` and `infinity-bedrock` belonging to this folder and matching its game ports. It leaves Minecraft, LAN joining, unrelated tunnels, and the default Pinggy daemon alone. The two tunnels forward to `127.0.0.1:25565` (Java TCP) and `127.0.0.1:19132` (Bedrock UDP), or the ports saved in Settings. Existing named configurations with different targets are preserved and refused.

Optional terminal controls, from this server folder:

```sh
.runtime/python/bin/python3 pinggy_joining.py start
.runtime/python/bin/python3 pinggy_joining.py status
.runtime/python/bin/python3 pinggy_joining.py stop
```

Private settings and safe public-address status are in `.pinggy`, using directory permissions `0700` and file permissions `0600`. CLI logs are `.pinggy/Pinggy.log`. The official Mac daemon also writes its native logs under `~/Library/Logs/Pinggy-CLI`; new files inherit the launcher's private umask. Do not publish private configuration or raw logs. The local daemon API binds to loopback, has no account authentication, and is checked against this folder's daemon record and native executable before reading status. No control panel is published through the game tunnels.

The agent source is [Pinggy CLI v0.5.9](https://github.com/Pinggy-io/cli-js/tree/v0.5.9). This optional companion is managed separately from the Minecraft panel's **Save & Stop** button.

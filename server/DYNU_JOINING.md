# A fixed joining name for Infinity Craft

Dynu provides a DNS hostname that you keep using when your home's public IP changes. This optional companion checks that IP every five minutes and updates your existing hostname. It requires a free Dynu account and router access. It does not provide a Minecraft tunnel, host the world, reserve a name automatically, or open router ports.

Choose an available public name such as `infinity-craft.<Dynu-domain>` in Dynu's own panel. This is a placeholder, **not an assigned address**. Share only the exact hostname your account successfully reserved. The account login name does not have to appear in the public server name.

The hostname does not change with each updater session. This is not a guarantee that any name, account, provider, home connection, or server will remain available forever. Keep control of the account and hostname, follow Dynu's current terms, and keep the updater running while hosting. A custom domain purchased elsewhere has its own renewal requirements.

## One-time account and private key setup

1. Open [Dynu](https://www.dynu.com/) and create or sign in to your own account. Complete any verification Dynu requests privately.
2. In **Control Panel → DDNS Services**, add an available free hostname. Enable IPv4 and point it at your home's public IPv4. Use a normal DNS hostname, not URL forwarding. Leave IPv6 disabled unless you have separately configured and tested inbound IPv6; this companion preserves your existing IPv6 settings.
3. Open **API Credentials** in the Dynu control panel and create an API key. This is a credential able to manage your Dynu account. Keep it private; do not paste it into chat, a command argument, Git, the public website, or a friend's joining guide. The [official API guide](https://www.dynu.com/Support/API) describes where to create or revoke it.
4. Open **Start-Mac.command** once so the bundled Python is ready. Open Terminal in this extracted server folder and run the command below. It prompts privately for the key and writes a new owner-only local import file. Its command text contains no key.

```sh
.runtime/python/bin/python3 -c 'import getpass, os; from pathlib import Path; p = Path("dynu-api-key.txt"); fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600); key = getpass.getpass("Dynu API key (hidden): ").strip(); f = os.fdopen(fd, "w"); f.write(key + "\n"); f.close()'
```

If `dynu-api-key.txt` already exists, the command stops and preserves it. If you cancel the prompt or enter the wrong key, remove that import file yourself before starting again. Use the new key from your Dynu account, not the account password or another service's key.

Replace `YOUR-RESERVED-HOSTNAME` with your actual lowercase hostname, then run:

```sh
.runtime/python/bin/python3 dynu_joining.py configure --hostname YOUR-RESERVED-HOSTNAME --key-file dynu-api-key.txt
.runtime/python/bin/python3 dynu_joining.py update
```

The updater reads the authenticated account's hostname list and refuses an absent, duplicate, inactive, or different hostname. It requires IPv4 to be enabled already. A successful update changes only that owned service's IPv4 address and preserves its existing IPv6, TTL, group, wildcard, DNSSEC, and zone-transfer settings. It neither creates nor deletes a DNS service or individual record.

After successful configuration, delete the temporary import file with `rm dynu-api-key.txt`. Your credential remains in `.dynu/.private.dynu` with file permissions `0600` inside a `0700` directory. Do not share or publish `.dynu`. The key is sent only in an HTTPS header to Dynu's API; redirects and environment-selected proxies are refused. The public-IP check is also HTTPS and sends no key.

## Forward the two Minecraft ports

Reserve the Mac's current LAN address in your router's DHCP settings so it stays the same. Forward these two ports to that LAN address and allow the corresponding game listeners through the Mac firewall:

| Edition | Protocol | Public router port | Mac game port |
| --- | --- | --- | --- |
| Java | TCP | 25565 | 25565 |
| Bedrock / iPad | UDP | 19132 | 19132 |

These are the defaults. If you changed game ports in the server panel, use those saved ports in both the router and Minecraft. The updater displays the values in `settings.json`; it does not edit them. Configure **both** protocols. A TCP port checker cannot verify the Bedrock UDP route.

Only forward the game ports. Keep the owner dashboard on the Mac's local address. Dynu's website feature called "port forwarding" is an HTTP redirect and does not replace your router's Java TCP or Bedrock UDP forwarding.

If the router's WAN IPv4 differs from the public IPv4 seen on the Internet, your connection may have another router or carrier-grade NAT in front of it. DNS alone cannot bypass that. You will need forwarding on the upstream router, a public IPv4 from your ISP, or a provider supporting both TCP and UDP tunnels.

## Start, join, and check

Start the world with **Start-Mac.command** and wait for **World is running**. Open **Start-Dynu-Mac.command** and leave its Terminal window open. It shows the saved hostname and both game ports, checks the IP, and keeps checking every five minutes. `Ctrl-C` stops only this updater; stopping the Minecraft world is separate.

Keep the Mac plugged in, awake, and connected to the Internet while friends play. Disable automatic sleep while hosting, or keep another Terminal running `caffeinate -i` until you finish. The DNS name can stay the same when the world is offline, but nobody can join while the Mac or Minecraft server is off.

- **Java:** Multiplayer → Add Server; enter `YOUR-RESERVED-HOSTNAME:25565` (or the configured Java port).
- **Bedrock / iPad:** Servers → Add Server; address `YOUR-RESERVED-HOSTNAME`, port `19132` (or the configured Bedrock port). Accept the Infinity resource pack.

Test **each edition from another Internet connection**, such as an iPad using a phone hotspot. A same-Wi-Fi test may be affected by your router's NAT loopback support. DNS caches may take the hostname's existing TTL to expire after an IP change. The updater always reports external joining as unverified; successful DNS updating alone does not prove that either game is reachable. Keep using the LAN address for local players if your router cannot loop back to its public hostname.

Optional controls from this server folder:

```sh
.runtime/python/bin/python3 dynu_joining.py status
.runtime/python/bin/python3 dynu_joining.py update
.runtime/python/bin/python3 dynu_joining.py update --watch --interval 300
```

`status` reads local metadata only and never contacts Dynu or prints the key. The downloadable companion does not install an automatic startup job or persistent background service: reopen it when you host, unless you have separately configured a background updater. Run only one watcher for the same server folder. [Dynu's Dynamic DNS FAQ](https://www.dynu.com/en-US/FAQ/Dynamic-DNS-Service) explains IP updating, DNS caching, and router requirements.

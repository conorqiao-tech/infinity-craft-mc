# Join Infinity Craft

You need Minecraft on the device you will play on. The server supplies the Infinity resource pack when you join.

## Java

Use **Minecraft Java 26.3**. Open **Multiplayer → Add Server** and enter:

`infinity-craft.remotewire.net:25565`

Save, join, and accept the offered resource pack. A separate Infinity mod installation is not required.

## Bedrock / iPad

Open **Play → Servers → Add Server**:

- Server name: **Infinity Craft**
- Server address: **infinity-craft.remotewire.net**
- Port: **19132**

Enter the port in its separate field. Save, join, and accept the offered resource pack. Use your normal Microsoft/Xbox sign-in with multiplayer enabled; joining does not require a Dynu account.

If Minecraft reports a version mismatch, tell the owner the Minecraft version shown on your device. The server's advertised version alone does not confirm that your device can join.

## Availability and checks

This fixed hostname stays the same when the home's public IP changes. DNS updates automatically, but a changed IP may take several minutes to reach everyone. The host computer must stay awake, online, and running the Minecraft server while people play. Continued availability also depends on the home connection and Dynu account; no provider can guarantee a hostname forever.

Checked **October 2, 2026**: the owner reported a successful iPad/Bedrock join using these connection details. Public DNS, an external Java TCP port check, and local Java and Bedrock status checks also passed. A full authenticated Java player join still needs verification. The TCP check alone does not verify Bedrock's UDP connection.

If the name fails from the host's own Wi-Fi, ask the owner for the LAN address. Some routers cannot connect back through their public hostname. If joining stops after a router restart, ask the owner to recheck the server's local address and both game-port forwarding rules.

To host a separate world yourself, follow the [hosting instructions](README.md#host-your-own-world).

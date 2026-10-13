# Infinity gear in the Creative inventory — explore.37 candidate

The **2.13.0-explore.37 candidate** adds normal Bedrock Creative inventory discovery and retains the Java client Creative tab. The last confirmed live version before this work was **explore.36**; these changes become playable only after the matching mod, bridge and resource packs are installed with approval. Current validation and installation evidence are recorded separately in [VALIDATION.md](VALIDATION.md).

## iPad and Bedrock

Enter Creative, open the normal inventory and search **Convergence** or **Aurora**. Select an entry into an inventory/hotbar slot and equip it normally. The adapter supplies **46 Infinity entries** across existing equipment, construction and item categories, including both complete sets, tools, gadgets, arrows and the ten block styles. It does not create a Java-style custom tab on Bedrock.

The resource catalogue uses temporary `infinity_catalog:<path>` aliases for Creative selection. Before Geyser caches or sends a selected item to Java, the bridge converts it to the existing vanilla Polymer wire item carrying the real `convergence:<path>` identity. The Fabric server restores the registered item and its actual components. Saved gear keeps its real identity; client-provided alias NBT is ignored. Catalogue aliases do not grant items in Survival. Ordinary vanilla primary mappings remain separate.

Static armor aliases and real items share the thirteen original Bedrock armor/backpack models and textures, so temporary equipment prediction has the same resources. The adapter does not change the eight native appearance exceptions: **Convergence Wings, shield, totem, bow, crossbow and the three Infinity arrow variants**. Catalogue icons can use original art while a selected real ranged/shield item returns to its native Bedrock appearance and controls. Wings retain their shared elytra artwork. See [ORIGINAL_ARTWORK.md](ORIGINAL_ARTWORK.md) for the independent armor files, Java mesh limit and full device checklist.

This adapter uses Geyser internal item registries and is deliberately tied to the exact **Geyser 2.11.3 build 1248** artifact. Both its builder and runtime verify the jar checksum. The exported public catalogue is bounded to 128 KiB/128 entries, and registration checks every supported protocol and alias collision. Unsupported jars or invalid catalogue registration stop Bedrock startup rather than accepting unusable entries. Review/rebuild the adapter when upgrading Geyser; copying it to an arbitrary newer bridge is unsupported. Install the extension, catalogue exporter and resource pack together. The launcher regenerates the catalogue from the current Fabric server at startup.

## Java

The **Infinity Armor** tab contains the real Infinity items, including the Convergence and Aurora sets, weapons, tools and blocks. Its entries also appear in Creative search. Players with Minecraft's Creative operator-item permission see the admin blocks in that tab.

For this server's **Minecraft Java 26.3** version, use a Fabric client with matching **Fabric API 0.161.0+26.3** and **Polymer core 0.18.2+26.3**. Polymer's client support receives the server's custom Creative tab and item definitions. Use the matching Polymer package and its required dependencies; the server's Infinity mod is server-only and does not need to be installed on your client. Join the server, accept its Java resource pack, switch to Creative, then open the inventory and select **Infinity Armor** or search for **Convergence** or **Aurora**.

An unmodified Java client can join and use Infinity gear, but cannot receive a new server-defined Creative tab. Accepting the resource pack alone adds artwork, not new Creative categories or search entries. On that client, use **Infinity Menu → Armor & tools → inventory** to put the real gear into your inventory.

The Java client support is optional for joining and playing; it is needed for the new Java Creative tab/search entries. The server's Infinity mod declares a server environment and should not be copied into the client as the tab solution. Bedrock/iPad do not install the Java client support.

## Existing menu fallback and checks

**Infinity Menu → Armor & tools → inventory**, the Creative **Infinity Gear Picker**, and inventory boxes remain available. Those collection controls fill empty slots and report insufficient room. Standard Creative inventory actions retain ordinary Minecraft behavior, including an intentional replacement when you choose an occupied slot.

Actual Geyser request/codec checks, Fabric item reconstruction tests and resource validation are distinct from authenticated device tests. A successful build or startup receipt alone does not establish that iPad search, touch pickup, armor movement or Java client tab rendering looks correct. After installation, test selection into an empty and occupied hotbar slot, cursor movement/drop, armor equip, disconnect/reconnect and saved item names. Inspect ordinary leather/netherite beside custom armor, and check the native appearance exceptions before reporting a texture as missing.

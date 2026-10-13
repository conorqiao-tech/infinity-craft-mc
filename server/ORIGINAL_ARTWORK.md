# Original Infinity artwork and inventory — explore.38 candidate

This describes the candidate **2.13.0-explore.38** source. The live server was **explore.36** before this update; these changes require an approved installation and reconnect. Building or reading this document does not update the playable server. See [VALIDATION.md](VALIDATION.md) for recorded checks and delivery state.

Convergence has pearl-white, midnight-blue and cyan plates with amber stars. Aurora uses jade, mint and lavender; Ember uses charcoal, copper and orange. The backpack has a sand/slate expedition style. All ten Infinity building materials and the registered gear sprites have original artwork. Ordinary leather, netherite and world-block textures stay normal.

## Find the sets

1. On **iPad/Bedrock with the candidate installed**, enter Creative, open the ordinary inventory and search **Convergence** or **Aurora**. The pinned Infinity Creative Bridge adds 46 Infinity entries to the existing Creative categories/search. Select a piece into your hotbar; the bridge converts its temporary catalogue identity into the real server item.
2. Java with matching Fabric/Polymer client support has an **Infinity Armor** Creative tab and search entries. An unmodified Java client uses the existing menu/picker; a resource pack alone cannot add its server-defined Creative tab. See [CREATIVE_INVENTORY.md](CREATIVE_INVENTORY.md).
3. The existing **Infinity Gear Picker** compass remains available in Creative. Choose **Aurora Set** or **Convergence Set** at the bottom, then tap a piece. **All Infinity gear** returns to tools, gadgets and blocks; Next opens page two.
4. **Infinity Menu → Armor & tools → inventory** adds missing equipment directly to empty slots. If there is not enough room, clear slots and select it again. Existing items are preserved.
5. **Armor, tools & blocks → inventory boxes** gives five real shulker boxes: Convergence, Aurora, Ember and two tools/block boxes. Place and open a labeled box to take its contents. Clear five slots first.

These are real player-inventory items. The explore.36 mapping alone did not support normal Bedrock custom Creative pickup. Candidate explore.38 supplies a separate adapter for the exact pinned Geyser artifact; it does not replace vanilla mappings or let Survival players claim catalogue entries. The bridge and the matching mod/resource pack must be installed together. Its protocol and server tests are separate from a real device inventory/rendering check.

## Chestplate and flight

**Convergence Armor Chestplate** (`convergence:cuirass`) is solid chest-slot armor with the existing full Convergence set powers. Its distinct recipe uses seven Infinity Ingots surrounding a normal netherite chestplate. The original winged chestplate recipe is unchanged.

**Convergence Wings** keep the existing `convergence:chestplate` ID, saved items, glider component and flight controls. They are a separate chest-slot choice; both chest-slot items cannot be equipped at once. Either completes the existing helmet/leggings/boots set. Existing gear formerly labeled “Infinity Chestplate” uses this same saved identity.

## Independent armor files and tool poses

The original refresh27 source PNGs and their recorded checksums are retained. In candidate explore.38, Bedrock's thirteen static armor/backpack pieces have their own `geometry.convergence.*_original` models in `models/entity/original_armor/` and their own controllers in `render_controllers/original_static_armor.json`. The plate assemblies, helmet details, articulated shoulders/knees and backpack volume are authored in [resource_models.py](../artwork/refresh27/resource_models.py). No vanilla armor geometry or texture file is copied or modified. Each moving piece binds to the player's existing head, torso, arm or leg joint; the engine supplies those player joints and glint materials.

Each static piece has a real `convergence:` attachable and a matching `infinity_catalog:` alias for temporary Creative prediction. They share the same original model and texture. Rendering selects the actual equipped identifier in its matching slot, rather than drawing another torso merely because an armor item is held. Ordinary leather/netherite textures are excluded from the prepared pack.

On Java, the original equipment textures remain independent, but the unmodified client still draws them on Minecraft's engine humanoid armor mesh. This update does **not** replace Java's worn armor mesh with the new Bedrock geometry. Java bow/crossbow draw or charge states, shield blocking and spear held/inventory contexts now select distinct custom model poses. They reuse the existing original sprites; no redrawn bowstring animation frames are claimed.

The wing item now adds its own independently authored `geometry.convergence.flight_shell_original` torso and shoulder plates. The actual wings retain native elytra geometry, animations and shared elytra artwork: ordinary Bedrock elytra also show that artwork while this pack is active. The eight native Bedrock appearance exceptions are **Convergence Wings, shield, totem, bow, crossbow, Infinity Arrow, Void Arrow and Starfire Arrow**. Their catalogue previews can show custom icons, but the real items return to ordinary native imagery/controls; the wings use the retained shared artwork. Fresh PNGs alone cannot safely replace those native behaviors. Java without its pack retains native fallback equipment.

Aurora, Ember and backpack artwork remains cosmetic. Backpack storage, mode inventories, earned unlocks and both AI approval gates are unchanged. Native Tidewarden/other Studio outfits retain their established trimmed Minecraft equipment; no extra outfit IDs or capabilities are introduced here.

## Device check after installation

After an approved installation, rejoin and accept the updated server resource pack. Each prepared update advances the pack and module revisions so Bedrock can refresh its cached artwork. Inspect all four equipped pieces of each set, the inventory icons and ordinary leather/netherite beside them. Check walking, crouching, arm movement, first/third-person views, enchantment glint and backpack colors. Swap the solid Convergence chestplate for wings, launch with rockets, glide and land, then re-equip the solid armor. Test normal Creative search/pickup, the collection picker, an occupied hotbar and a full inventory. Draw/fire both bows, block with the shield, hold/use the spear and compare the native Bedrock exceptions. Reconnect and check your items.

The new models and catalogue have not been visually verified on an authenticated Java/iPad client in this documentation task. Automated resource/native/bridge checks cannot prove rendered fit, touch input, animations or flight. Report the piece and edition if an appearance is wrong. Source/package and live installation states are reported separately; this document is not proof of installation.

# Creative Studio — 2.13.0-explore.24

Open **Infinity Menu → Creative Studio**, or choose **Creative Studio** at the bottom of the Creative gear picker. The controls use native chest icons on Java and iPad/Bedrock. Choose **Play Creative** to begin. Ordinary players build in the Creative world; OP4 can also use the Studio in another world after switching their actual game mode to Creative. Separate mode inventories remain intact.

## Building and blueprints

Choose a **brush** (Plane, Line, Sphere, Cube or Ring), radius 1–3, and a theme: Aurora, Ember, Sakura, Clockwork or Frost. The Builder Wand places solid cubes from your offhand into empty cells; the Sculptor Wand removes editable blocks without drops. Use **Swap main hand and offhand** in Infinity Menu to select a building block on iPad. Containers, unbreakable blocks, protected maps and occupied placement cells are preserved. A radius-three Cube considers at most 343 cells.

**Get themed building kit** delivers three stacks and both building wands into five empty slots. A full inventory receives nothing rather than a partial kit or dropped items. **Undo last Studio edit** restores the latest brush or blueprint edit in the same world and session. If someone changes any affected block, or restoring a block would enclose a living entity, the entire undo is refused. Undo creates no item drops. It is temporary and expires when you disconnect or leave Creative.

Choose **Arch**, **Pavilion** or **Tree**, then aim at clear ground and select **Preview blueprint**, or use the **Infinity Blueprint Wand**. A particle preview and review screen show the position and block count. **Confirm building** places the reviewed footprint; Cancel places nothing. Approval expires after 30 seconds and checks your current permission, connection, world, distance and every target cell again. These are small native structures, separate from naturally generated Odyssey ruins.

## Gadgets, outfits and flight

- **Starlight Blink Wand:** use to move up to twelve blocks toward your aim. It checks loaded terrain, world borders, collision and a dry destination. It refuses a blocked route.
- **Aurora Party Wand:** use for a small particle burst in your theme. **Personal trail** toggles a lighter repeated effect. Palette and trail preferences save with your player data.
- **Infinity Blueprint Wand:** opens the selected blueprint preview when aimed at clear ground.
- **RC Plane Remote:** use to launch your plane, then use again to toggle hover. You can also launch, hover, adjust speed and recall through the Studio.
- **Infinity Storm Staff:** activates a pulse against up to six visible hostile mobs within twelve blocks. It does not attack players or pets. Obtain it from the gear picker or craft it with three Infinity ingots, a blaze rod and an amethyst shard. Normal players have a four-second cooldown; the existing Admin ability rules apply.

The RC plane is a nine-part native voxel model, not a passenger aircraft. Look to steer while standing nearby. Three speeds range from 2.4 to 6.4 blocks per second at 20 TPS. Obstacles put it into hover; Recall removes all parts without blocks or drops. One plane per owner, eight active server-wide, five-minute flight and a 64-block control range keep the assembly bounded. Disconnecting, losing Creative permission, changing worlds or restarting removes its parts. Ordinary falling blocks are unaffected. Real Java/iPad rendering and smoothness require a client playtest.

Three full wearable outfits use Minecraft's own equipment models and trims: **Storm Sentinel** (diamond with blue Spire trim), **Ember Knight** (netherite with gold Rib trim), and **Sakura Ranger** (pink leather with amethyst Wild trim). Each includes helmet, chestplate, leggings and boots and needs four empty slots. Their native protection matches their material; they do not grant Infinity armor powers. This makes both editions use real equipment controls.

## Ordinary armor and helper controls

The Bedrock server pack no longer replaces ordinary leather/netherite armor textures or their inventory icons. They use Minecraft's normal appearance. Java's dedicated Convergence, Aurora and Ember artwork is preserved. Geyser's native leather fallback means Bedrock Aurora, Ember and backpack clothing now use normal leather shapes with their own dye colors rather than a separate per-item pattern. Their names, equipment slots, powers and personal storage remain intact.

**AI Helpers → Bring loaded squad here** recalls existing loaded helpers to safe ground beside their owner. It preserves their entities, names, health and edited stats, and reports unavailable helpers instead of recreating them. Each roster entry now explains whether it is fighting, ready, staying, unloaded or paused for another reason. As with individual recall, it switches recalled helpers to Follow and clears player-target orders; commands and named-player attacks retain their owner and separate live-review gates.

## TNT safeguards and limits

Primed TNT detonations are queued across all worlds: at most two per server tick, with the second deferred if the first already used the short explosion-work window. Undetonated charges keep ticking and moving until their turn; they are not deleted. Ordinary explosion power stays unchanged; excessive single explosion radii are capped at eight, and invalid non-finite power becomes zero.

This reduces explosion bursts; it cannot make the server or iPad immune to lag. Huge entity counts, generation, many players, TNT minecarts and repeated non-TNT explosions can still overload hardware. If the iPad app itself freezes, fully close Minecraft through the app switcher and reopen it. A responsive server does not prove the client is responsive.

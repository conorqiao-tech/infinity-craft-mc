# Main Hub and game-mode lobbies

Everyone plays on the same Java-hosted server. The new **Main Hub** has five walkable, themed lobby areas: Survival, Creative, Hardcore, Minigames, and Adventure. They share one dedicated hub dimension, separate from all gameplay worlds. A new player begins at the Main Hub; existing players keep their current location until they choose to visit.

Use the same short commands on Java and Bedrock:

| Command | Result |
|---|---|
| `/hub` | Go to the Main Hub. |
| `/lobbies` | List the five mode lobbies. |
| `/lobby survival` | Visit the Survival lobby. |
| `/lobby creative` | Visit the Creative lobby. |
| `/lobby hardcore` | Visit the Hardcore lobby. |
| `/lobby minigames` | Visit the Minigames lobby. |
| `/lobby adventure` | Visit the Adventure lobby. |
| `/play <mode>` | Enter a gameplay world from its lobby or anywhere else. |

`/lobby` by itself returns to the Main Hub. `/spawn` in the hub returns to the Main Hub; in a gameplay world, it returns to that mode's spawn. Each lobby has a sign for entry and the Main Hub has a row of signs for the five halls. You can also walk the bridges between halls. Typing the commands is the dependable way to navigate on either edition; clickable chat buttons are an extra convenience for Java.

Changing from a gameplay world into the hub, or from the hub into another gameplay world, takes three seconds. Stay still. Damage cancels the transfer and starts a ten-second wait. Moving between areas within the hub is immediate.

The hub is Adventure mode, protected from damage, and uses a clean, temporary inventory. Survival, Creative, and Hardcore items, armor, Ender Chest contents, health and experience are saved in their own profiles before a hub visit and restored when the player returns. Items found or granted in the hub do not enter gameplay profiles. The server also returns an item held on the cursor to the old profile before leaving it. Hardcore elimination remains in force even when that player visits its lobby.

Use `/rewards` or `/trades` in a lobby to browse unlocks and exact item costs. A lobby visit does not activate a power or spend items. Earned powers turn off before leaving Survival, and item trades require entering Survival before confirmation. Particle cosmetics are separate and can remain visible in the hub.

The built-in hub appears automatically once and is recorded by `fabric/world/infinity-built-in-lobbies.json`. Keep that marker with the world. The hub is inside the same world backup as all player profiles and game-mode areas. A later restart does not rebuild over changes to the hub. If a third-party mod has already placed blocks in the reserved hub area before its first generation, setup stops without replacing them.

The hub is one shared space with six areas, not six independent server processes or queue instances. The default player-slot limit and host capacity are unchanged. A proxy or extra machines would be a separate hosting upgrade.

# Exploration and scores — 2.12.0-explore.3

Use `/guide` anywhere in the world. It responds to your current place: Main Hub, a mode lobby, a minigame, an adventure map, or a normal game mode. `/hub` returns to the Main Hub, `/lobbies` lists destinations, and `/play` chooses a mode.

The four minigames are Sky Steps (`/minigame parkour`), Switchback Sprint (`/minigame sprint`), Dropper (`/minigame dropper`), and Red Light (`/minigame redlight`). Checkpoint and finish hints appear in the action bar. Use `/retry <map>` to replay. `/best` shows your saved personal times, while `/leaderboard parkour`, `/leaderboard sprint`, `/leaderboard dropper`, or `/leaderboard redlight` shows the saved fastest times across players. Records are stored with the world and are rebuilt from player progress if the index is missing. A temporarily unavailable public index does not erase personal times.

The two starter adventure maps are Five Seals (`/adventure ruins`) and Lantern Labyrinth (`/adventure maze`). Creative has a separate building world and custom tools, and Survival and Hardcore keep their own inventories and progress. See [MODES.md](MODES.md), [LOBBIES.md](LOBBIES.md), and [EXPANSION.md](EXPANSION.md) for the complete commands and rules.

The owner dashboard now presents the modes and maps, live local status, and separate Java and Bedrock join details. Remote addresses appear only when a locally owned Pinggy helper allocates them. They are temporary and need an outside-network test; an allocated address alone does not establish that a friend can join. The public guide site lists content and downloads, but does not run or expose the private dashboard.

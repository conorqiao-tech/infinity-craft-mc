# Helpers, AI chat, and full operator controls — 2.10.0-agents.1

## In-world helpers

A level-4 operator can create a visible iron-golem helper. These helpers use Minecraft navigation and controlled hostile-mob combat; Java and Bedrock players see the same entity. Use lowercase names with 1–24 letters, numbers, underscores or hyphens.

```text
/agent spawn buddy
/agent follow buddy
/agent guard buddy
/agent stay buddy
/agent list
/agent dismiss buddy
```

Use `/agent help` to see the controls. Spawn on open solid ground. Follow moves toward the owner and fights nearby hostile mobs; Guard defends the location where that mode was set; Stay pauses navigation and combat. Helpers do not attack players, pets, or ordinary animals. They pause while their owner is offline, dead, spectating, in another dimension, or no longer a level-4 operator. They do not follow across game worlds or load distant chunks. A following helper pauses if it falls more than 48 blocks behind; return toward it to resume. Each operator has three helper slots, including unloaded helpers; dismiss an old helper to free one.

Ownership and mode are saved in the world's `infinity-agents.json`; the golem is saved in ordinary entity data. Restore the whole matching world backup. Dismissal of an unloaded helper removes its saved record immediately and removes its entity when its chunk naturally loads.

## Chat help

`/ai <question>` provides built-in server help without an API key. Try `/ai how do I join survival`, `/ai how do ranks work`, or `/ai what powers can I get`. The built-in helper answers from the server's actual command catalogue and says when a question is outside that catalogue.

Optional OpenAI model chat uses the Responses API for questions outside built-in server help. The host's private key is read from `fabric/config/infinity-ai-key.txt`, never from in-game chat. The local integration sends the question and server-help instructions; it does not send the key as prompt text or execute model-generated commands. API replies are shown privately to the asking player. Default model chat is available only to level-4 operators, with 50 requests per UTC day. The count is saved with the world, survives restarting, and includes accepted attempts that fail. A zero limit pauses model chat. Built-in help stays available if model chat is unavailable.

In the local host panel, Save & Stop, open **Owner: AI chat**, enter a new API key or leave it blank to keep the existing key, and save the settings. Start again to apply. Keep the key file private (permission 0600 on Mac/Linux). It is excluded from server/source downloads, world backups, and the public website. A fresh download does not contain a key. OpenAI API usage is billed to the host's API account. Model chat config is `fabric/config/infinity-ai.json`; see `/ai status` for the current configuration. Normal Minecraft chat is not automatically sent to the API.

## Full operator access

The host can grant full in-game access with `op ExactPlayerName` in the server console. Bedrock accounts use their exact Floodgate name, including its prefix. Full access means **OP level 4**, the highest Minecraft operator level; the limited code-based Admin rank remains separate.

Level-4 operators can use all mod powers and cosmetics without achievements or item payment, in every game world. Operator powers bypass combat waits and Windstep cooldowns. Operators switch modes immediately, can enter Hardcore after elimination, and can use vanilla `/gamemode` without the mod forcing a mode afterward. Cross-world operator teleports save and restore the matching inventories and mode profiles. Operators bypass community teleport waits, home-slot limits, and chat moderation limits.

```text
/power hacks
/cosmetic dragon
/play creative
/gamemode creative
/give @s minecraft:diamond 64
/agent spawn buddy
```

The server still keeps separate mode profiles and checks valid names, coordinates, and saved data. Commands that require a living player or a clear spawn location keep that requirement. One power preset is active at a time; use vanilla `/effect` for additional effects. The code-based Admin role does not gain operator overrides or model-chat access. Removing OP removes operator-only access without awarding permanent achievement/trade unlocks.

The OpenAI connection follows the [Responses API quickstart](https://developers.openai.com/api/docs/quickstart) and uses the configurable [GPT-6 Luna model](https://developers.openai.com/api/docs/models/gpt-6-luna) by default.

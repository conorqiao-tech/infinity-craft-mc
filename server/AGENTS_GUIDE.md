# Helper squads, AI modes, and full operator controls — 2.12.0-explore.7

Helpers are vanilla iron golems controlled by this server. They work with Java and Bedrock without a client mod. **Only an operator with level 4 can create, change, dismiss, or ask a helper.** The free Admin-code role is separate.

## Manage a squad

Open `/agent menu` for a chest-style menu. Create up to **six named helpers per owner**, choose one to manage it, select an AI profile, or set Follow, Guard, or Stay for the loaded squad. The entire server has **24 slots**, including unloaded helpers. Menu icons are controls rather than collectible items. Dismissal has a confirmation screen.

```text
/agent spawn guide
/agent spawn defender
/agent profile guide primitive
/agent profile defender ultimate_finals
/agent ask guide how do ranks work
/agent status defender
/agent squad follow
/agent menu
```

Names use 1–24 lowercase letters, digits, `_` or `-`. Each helper has its own name, saved owner, AI profile and movement setting. `/agent list` lists your roster. `/agent profile <name> <profile>` changes a saved profile, including an unloaded helper; movement changes need a loaded helper in your dimension. `/agent squad follow`, `guard`, or `stay` affects your loaded helpers in your dimension and reports skipped helpers.

| AI profile | What it does |
| --- | --- |
| `primitive` | Combat-ready: proactively seeks nearby hostile mobs within 12 blocks. Answers basic server questions locally; Follow, Guard and Stay remain available. |
| `regular` | Standard follow/guard hostile-mob defense and local server help. |
| `ultimate_finals` | Shares a focus target, prioritizes hostiles threatening the owner, and approaches from six flank positions when loaded terrain is clear. Primitive and Ultimate Finals can also share one explicitly named player target after both reviews below. Uses ordinary golem stats and deterministic tactics. Pets and ordinary animals remain protected. |
| `debug` | Passive helper with read-only status: health, loaded state, position, movement and pause reason. Does not expose host files or secrets. |
| `cli` | Saves source-change requests for owner approval and live Codex code review, edits and testing. Also previews six fixed Minecraft server actions. It does not launch a terminal, shell or apply model-generated code. |
| `api` | Optional external AI text answers with a limited live Minecraft snapshot. `/agent data <name>` previews the data. Requires the owner's configured connection and shares the server's request budget with `/ai`. It has no tools and cannot run its suggestions. |

AI profile and movement are separate controls. Follow moves toward the owner; Guard anchors a combat helper at its current location; Stay stops navigation and combat. Player targeting requires a separate reviewed order. Helpers pause while the owner is offline, dead, spectating, in another dimension, or no longer OP4. Follow pauses beyond 48 blocks; return nearby to resume ordinary mob defense. Helpers never teleport or load distant chunks.

## Shared player targets

Your Primitive and Ultimate Finals helpers can share an exact player target. They never choose a player automatically. Use `/agent target <player>` to create a proposal, read its target name, UUID and dimension with `/agent pending`, and approve its exact ID with `/agent approve <id>`. Then ask Codex in this live chat to review it. Only the local-console `agent-codex-approve <id>` after that review can activate the order. Chat answers cannot activate it.

An active player order lasts at most five minutes while the target stays within 48 blocks of the owner. Minecraft PvP and team rules apply. Helpers can pursue around obstacles; actual swings still require melee reach, line of sight and a target within 24 blocks. Guard helpers stay within their 14-block anchor area and wait if the player leaves it. Moving outside a helper's immediate attack range does not consume the order. The target must be an eligible living player nearby in the owner's dimension; Creative and Spectator players are excluded. Owner logout, death, dimension change or loss of OP4 clears the order, as do target logout, death, dimension change and other eligibility changes, or having no loaded compatible helper active inside its movement leash. A respawn or reused name does not inherit an order. Pending player orders are cancelled across server restarts. Regular, Debug, CLI and API helpers do not join this player hunt.

Use `/agent ceasefire` or the **Ceasefire** button in `/agent menu` to clear your player order and queued target proposals immediately. This only affects your squad. The menu compass shows your current target status; Stay also pauses a helper's combat. Shared targeting is deterministic golem coordination. Codex participates by reviewing orders in this chat; there is no continuous background connection to Codex while the chat is closed.

The roster is saved as format 2 in the world's `infinity-agents.json`. Existing format-1 helpers upgrade with the Regular profile. Restore the whole matching world backup, including ordinary entity data. Dismissing an unloaded helper frees its roster slot immediately and removes its entity when its chunk next loads.

## Reviewed code changes

Set a helper to CLI, then use `/agent code <name> <request>` to save a plain-text change request (up to 500 characters). It writes a private review record with the world, not an executable patch. Do not include secrets. `/agent code-pending` shows your requests; `/agent code-approve <id>` approves the exact saved text; `/agent code-cancel <id>` cancels it. Requests expire after 24 hours, with three active requests per owner and 16 server-wide.

Ask Codex in this live chat to review the request. Codex reads `agent-code-pending` in the **local** server console and uses `agent-code-review <id>` only after checking your approval. Codex then inspects the repository, edits the source, reviews the resulting diff, runs the needed tests and creates a Git commit. `agent-code-complete <id> <full-40-character-commit>` records the result. Use the stopped-world backup and tested upgrade procedure to install it. No code changes happen while this chat is closed. If a review is already underway, cancelling the request in Minecraft cannot undo source edits made here; tell Codex to stop too.

The queue validates state, helper identity, OP4 and local-console origin. A console completion records the reviewer-supplied commit; Minecraft cannot independently verify Git, test results, or that the console operator is Codex. Those checks happen in the live review. Requests are not sent to an external model automatically.

## Live Minecraft data

`/agent data <name>` reads a bounded snapshot for one of your own helpers. API-mode asks include the snapshot with your question and the server guide, and show a notice before sending. It contains game status such as the owner's current location/mode, helper status, world time/weather and online player count. It excludes IP addresses, keys, private host settings, other players' inventories and host files. API-profile helpers also keep the latest 12 sanitized samples in memory, one every five seconds. `/agent history <name>` shows the latest three with server-tick timestamps; explicit API asks may include those three samples to compare recent changes. Sampling never makes API requests automatically. History clears when the helper leaves API mode, is dismissed, or its owner goes offline/loses OP4; it also resets on server restart. It is a recent game-status history, not a persistent analytics database. All six helpers share the same API request and spending limits; changing helper names or profiles cannot create extra quotas.

## Chat help

`/ai <question>` provides built-in server help without an API key. Try `/ai how do I join survival`, `/ai how do ranks work`, or `/ai what powers can I get`. The built-in helper answers from the server's actual command catalogue and says when a question is outside that catalogue.

Optional OpenAI model chat uses the Responses API for questions outside built-in server help. The host's private key is read from `fabric/config/infinity-ai-key.txt`, never from in-game chat. The local integration sends the question and server-help instructions; it does not send the key as prompt text or execute model-generated commands. API replies are shown privately to the asking player. Default model chat is available only to level-4 operators, with 50 requests per UTC day. The count is saved with the world, survives restarting, and includes accepted attempts that fail. A zero limit pauses model chat. Built-in help stays available if model chat is unavailable.

## Owner-approved helper actions

The helper action queue is separate from `/ai` chat and from the iron-golem follow/guard controls. An OP4 owner can propose exactly six fixed server actions: **set day**, **set night**, **clear weather**, **make it rain**, **list players**, and **save world**. Arbitrary natural-language requests and arbitrary commands are not accepted. Proposing never runs an action.

1. In Minecraft, an OP4 owner enters `/agent suggest set day` (or one of the other supported phrases). The server returns a proposal ID and the exact fixed command it would run.
2. Review the command with `/agent pending`, then use `/agent approve <id>` to approve that ID, or `/agent cancel <id>` to discard it. Owner approval still does not run the command.
3. Ask Codex in a **live session on the host Mac** to review that proposal. Codex must read the local console's `agent-codex-pending` output and independently decide whether to dispatch the exact approved ID through `agent-codex-approve <id>`. These console commands cannot be used by an in-game player. No background Codex session watches the queue or approves it automatically.

Proposals expire after ten minutes. The OP4 owner must still be online when Codex dispatches one. A proposal can dispatch only once; the queue keeps a short saved history in `fabric/world/infinity-agent-actions.json`. At most three active proposals are allowed per owner and 16 across the server. Restore this file with the matching world backup. The fixed command is never generated by a chat model, and the limited code-based Admin rank cannot propose or approve actions.

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

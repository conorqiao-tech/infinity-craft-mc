# Helper squads, AI modes, and full operator controls — 2.12.0-explore.17

Admins can edit a loaded helper through **Infinity Menu → Admin editor • players and AI**. Select its golem icon, change health, damage, speed, armor, size or another supported base attribute, then confirm. On iPad/Bedrock this opens native buttons and a number input field; Java uses Review and Confirm. Manual stat edits do not require Codex approval. Original attribute values can be restored. The edits and reset values save with the entity; helper behavior/profile changes invalidate an open stat review. Follow, guard and profile controls remain in **AI Helpers**, and player-target orders still require both approvals.

Helpers are vanilla iron golems controlled by this server. They work with Java and Bedrock without a client mod. **Only an operator with level 4 can create, change, dismiss, or ask a helper.** The free Admin role now grants actual OP4 when obtained through the private code or a trusted owner grant.

## Manage a squad

Select the **Infinity Menu** recovery compass in your hotbar, then **AI Helpers • open your squad**. If the compass is missing, tap an **INFINITY MENU** sign in the Main Hub or a mode lobby and leave one inventory slot free for a replacement. `/agent menu` remains an optional shortcut.

On your first visit, tap the green **Create your first helper** button. Stand on clear solid ground with enough room for an iron golem. The screen closes before spawning; look nearby for the named iron golem and read the success or failure message. Helpers do not appear automatically when you join. If spawning fails, move to an open area and reopen the menu to try again.

Create up to **six named helpers per owner**, choose one to manage it, select an AI profile, or set **follow**, **guard current area**, or **stay / pause** for the loaded squad. The entire server has **24 slots**, including unloaded helpers. **Unloaded** means a saved helper is outside the currently loaded area; return nearby for movement controls. Menu icons are controls rather than collectible items. Dismissal has a confirmation screen. Everyone can see the Infinity Menu's helper button, but creating and controlling a squad requires **OP level 4**.

Choose a saved helper to see **Status • profile, location, and health** and **Ask Codex** when the Codex provider is enabled (otherwise **Ask helper**). The Ask button sends “Explain my helper's current state and suggest what I should do next.” The screen closes so you can read the answer. **Back to your squad** returns to the roster; its **Back to Infinity Menu** button returns to the main menu. Use `/agent ask <name> <question>` to write a different question.

**AI Helpers → Orders • targets and approvals** opens a player picker and the six fixed action proposals. **Pending orders** opens an exact review with **Approve as owner** and **Cancel**. The menu never provides Codex approval: ask Codex in this chat to review the displayed proposal ID before expiry. Follow, Guard and Stay work directly for loaded helpers in your world.

The following commands remain available for named creation, questions and advanced actions:

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
| `primitive` | Combat-ready: proactively seeks nearby hostile mobs within 12 blocks. Follow, Guard and Stay remain available; questions use Codex when enabled, otherwise local guidance. |
| `regular` | Standard follow/guard hostile-mob defense; questions use Codex when enabled, otherwise local server help. |
| `ultimate_finals` | Shares a focus target, prioritizes hostiles threatening the owner, flanks, and leads moving targets. The squad equips native spears, briefly glides with equipped elytras and switches to maces for falling attacks when the landing and airspace are clear. Primitive and Ultimate Finals can share one explicitly named player target after both reviews below. Pets and ordinary animals remain protected. |
| `debug` | Passive helper with read-only status: health, loaded state, position, movement and pause reason. Does not expose host files or secrets. |
| `cli` | Saves source-change requests for owner approval and live Codex code review, edits and testing. Also previews six fixed Minecraft server actions. It does not launch a terminal, shell or apply model-generated code. |
| `api` | Optional external AI text answers with a limited live Minecraft snapshot. `/agent data <name>` previews the data. Requires the owner's configured connection and shares the server's request budget with `/ai`. It has no tools and cannot run its suggestions. |

When Codex is enabled, questions to **all six profiles** use the Codex connection. The profile still selects the helper's movement/combat behavior and the context for its answer. With the OpenAI API provider, only API-profile helper questions are sent externally; other profiles keep their local responses.

AI profile and movement are separate controls. Follow moves toward the owner; Guard anchors a combat helper at its current location; Stay stops navigation and combat. Player targeting requires a separate reviewed order. Helpers pause while the owner is offline, dead, spectating, in another dimension, or no longer OP4. Follow pauses beyond 48 blocks; return nearby to resume ordinary mob defense. Helpers never teleport automatically or load distant chunks. For a loaded helper left in another world or beyond follow range, choose **AI Helpers → helper → Bring here**, or use `/agent recall <name>`. The owner must be online, alive and OP4, with safe space nearby. This moves the existing golem, preserves its health and edited attributes, switches it to Follow and clears the squad’s active player-target order and pending target approvals. Unloaded helpers must first be loaded by visiting their area; they are not replaced or force-loaded.

Ultimate Finals predicts at most two blocks ahead of a moving target. Eligible squad members take turns attempting a short leap from 4–7 blocks away on roughly level ground; only one helper in that owner's squad performs the aerial sequence against the same target at a time. Each helper has an 80-tick leap cooldown. The path needs loaded, dry, unobstructed airspace and a clear solid landing inside its movement area. Walls, low ceilings or unsuitable ground keep the helper on ordinary ground pursuit.

Ultimate Finals holds a real vanilla netherite spear on ground approaches, winds it up before rushing, and switches to a mace when the target is inside the spear’s minimum reach. Native LivingEntity use ticks run its kinetic charging component. The aerial arc equips an elytra and briefly enters native glide, stops gliding, then equips a mace before a falling attack through Minecraft’s MobEntity weapon path, including the native mace fall bonus. A ground spear follow-up uses the existing cadence and native contact cooldowns. Hits still need actual reach, line of sight and target eligibility; hits are not guaranteed. Helpers now use native player avatars that show equipped weapons and elytra. Their server AI and physical collision remain golem-based; they are not authenticated Minecraft player accounts. Status reports name the actual held item and glide state. These mechanics do not reset damage immunity or guarantee bypassing a totem. Native spear side effects and mace splash knockback reject unapproved players and other ineligible targets before touching them. Stay, ceasefire, a profile change, an invalid target/order or loss of the owner's OP4 cancels the sequence; gravity remains active. No AI request is made for each movement or attack.

## Shared player targets

Your Primitive and Ultimate Finals helpers can share an exact player target. They never choose a player automatically. Use `/agent target <player>` to create a proposal, read its target name, UUID and dimension with `/agent pending`, and approve its exact ID with `/agent approve <id>`. Then ask Codex in this live chat to review it. Only the local-console `agent-codex-approve <id>` after that review can activate the order. Chat answers cannot activate it.

An active player order lasts at most five minutes while the target stays within 48 blocks of the owner. Minecraft PvP and team rules apply. Helpers can pursue around obstacles; actual swings still require melee reach, line of sight and a target within 24 blocks. Guard helpers stay within their 14-block anchor area and wait if the player leaves it. Moving outside a helper's immediate attack range does not consume the order. The target must be an eligible living player nearby in the owner's dimension; Creative and Spectator players are excluded. Owner logout, death, dimension change or loss of OP4 clears the order, as do target logout, death, dimension change and other eligibility changes, or having no loaded compatible helper active inside its movement leash. A respawn or reused name does not inherit an order. Pending player orders are cancelled across server restarts. Regular, Debug, CLI and API helpers do not join this player hunt.

Use `/agent ceasefire` or the **Ceasefire** button in the AI Helpers menu to clear your player order and queued target proposals immediately. This only affects your squad. The menu compass shows your current target status; Stay also pauses a helper's combat. Shared targeting is deterministic golem coordination. This live Codex chat reviews orders when you ask; it does not watch the queue in the background. The separate in-game Codex answer connection does not activate orders or replace that review.

The roster is saved as format 2 in the world's `infinity-agents.json`. Existing format-1 helpers upgrade with the Regular profile. Restore the whole matching world backup, including ordinary entity data. Dismissing an unloaded helper frees its roster slot immediately and removes its entity when its chunk next loads.

## Reviewed code changes

Set a helper to CLI, then use `/agent code <name> <request>` to save a plain-text change request (up to 500 characters). It writes a private review record with the world, not an executable patch. Do not include secrets. `/agent code-pending` shows your requests; `/agent code-approve <id>` approves the exact saved text; `/agent code-cancel <id>` cancels it. Requests expire after 24 hours, with three active requests per owner and 16 server-wide.

Ask Codex in this live chat to review the request. Codex reads `agent-code-pending` in the **local** server console and uses `agent-code-review <id>` only after checking your approval. Codex then inspects the repository, edits the source, reviews the resulting diff, runs the needed tests and creates a Git commit. `agent-code-complete <id> <full-40-character-commit>` records the result. Use the stopped-world backup and tested upgrade procedure to install it. No code changes happen while this chat is closed. If a review is already underway, cancelling the request in Minecraft cannot undo source edits made here; tell Codex to stop too.

The queue validates state, helper identity, OP4 and local-console origin. A console completion records the reviewer-supplied commit; Minecraft cannot independently verify Git, test results, or that the console operator is Codex. Those checks happen in the live review. Requests are not sent to an external model automatically.

## Live Minecraft data

`/agent data <name>` reads a bounded snapshot for one of your own helpers. Codex questions in every profile, and API-profile questions with the OpenAI API provider, include the current snapshot with your question and the server guide. A notice appears before sending these facts to OpenAI. It contains game status such as the owner's current location/mode, helper status, world time/weather and online player count. It excludes IP addresses, keys, private host settings, other players' inventories and host files. API-profile helpers also keep the latest 12 sanitized samples in memory, one every five seconds. `/agent history <name>` shows the latest three with server-tick timestamps; explicit external questions may include those three samples when available to compare recent changes. Sampling never makes API requests automatically. History clears when the helper leaves API mode, is dismissed, or its owner goes offline/loses OP4; it also resets on server restart. It is a recent game-status history, not a persistent analytics database. All six helpers share the same server request budget and selected provider account; changing helper names or profiles cannot create extra quotas.

## Chat help and provider setup

`/ai help` always uses the built-in server guide, without an API key or Codex request. When Codex is enabled, an OP4 player's other `/ai <question>` requests and `/agent ask <name> <question>` in all six profiles go to Codex. Ordinary players retain built-in responses for known server topics and cannot spend the host's Codex allowance. With the OpenAI API provider, `/ai` answers known server topics locally and sends questions outside that catalogue through the optional API; only API-profile helpers use that external connection. `/ai status` shows the configured provider, model, daily request count and readiness to an OP4 owner.

To connect Codex:

1. **Save & Stop** the world and open **Owner: AI chat** in the private host panel.
2. Choose **Codex CLI · signed-in account**. Enter the **absolute path to the native Codex executable**, without command arguments. This integration currently accepts the audited **Codex CLI 0.155.1** only; other versions stop with an error until reviewed for compatibility. The executable must already exist on this computer and be signed in with the host's ChatGPT account. Prefer the native binary over a Node-dependent launcher shim, since the server launcher's environment may not include Node. Saving checks the path; it does not install Codex, sign in, or verify the login/version. Those are checked when a question is sent.
3. Enable AI chat, choose a model available to that account, and set the daily request limit. Codex requires **Only allow operators with level 4**; the panel keeps it checked. No OpenAI API key is needed.
4. Save and start the world. Select a helper's **Ask Codex** button or type your question with `/ai` or `/agent ask`.

Codex uses the host account's usage allowance and availability; it is not a promise of free or unlimited chat. The OpenAI API option remains available: choose **OpenAI API**, enter a key or leave the field blank to retain the saved key, then save and restart. API usage is billed to the host's API account. Switching providers keeps the private API key. A fresh download contains neither a key nor a Codex login.

External questions send your text and the supplied server guide to OpenAI. Helper questions also send the bounded Minecraft facts described above. Responses are private to the asking player and identify the provider used. The integration requests a text answer, with no helper interface for reading the host environment, editing files or running server commands. A Codex answer is a separate request, **not this live Codex conversation**, and cannot count as its review or approval. No environment access or automated approval is granted by choosing a profile called CLI, API or Debug.

Both providers share the server's daily request counter, with 50 requests per UTC day by default. The count is saved with the world, survives restarts, and includes accepted attempts that fail. A zero limit pauses external chat. Codex is always OP4-only; OpenAI API chat is OP4-only by default and uses the owner's configured access setting. `/ai help` remains available when an external connection cannot answer. Normal public Minecraft chat is not automatically sent to either provider.

Settings are stored in `fabric/config/infinity-ai.json` (`provider`, `codexExecutable`, `model`, `enabled`, `ownerOnly`, and `dailyRequestLimit`). The OpenAI API key stays in `fabric/config/infinity-ai-key.txt`, never in the JSON or in-game chat. Keep it private; on Mac/Linux it is written with permission 0600. It is excluded from server/source downloads, world backups and the public website.

## Owner-approved helper actions

The helper action queue is separate from `/ai` chat and from the iron-golem follow/guard controls. An OP4 owner can propose exactly six fixed server actions: **set day**, **set night**, **clear weather**, **make it rain**, **list players**, and **save world**. Arbitrary natural-language requests and arbitrary commands are not accepted. Proposing never runs an action.

1. In Minecraft, an OP4 owner enters `/agent suggest set day` (or one of the other supported phrases). The server returns a proposal ID and the exact fixed command it would run.
2. Review the command with `/agent pending`, then use `/agent approve <id>` to approve that ID, or `/agent cancel <id>` to discard it. Owner approval still does not run the command.
3. Ask Codex in a **live session on the host Mac** to review that proposal. Codex must read the local console's `agent-codex-pending` output and independently decide whether to dispatch the exact approved ID through `agent-codex-approve <id>`. These console commands cannot be used by an in-game player. No background Codex session watches the queue or approves it automatically.

Proposals expire after ten minutes. The OP4 owner must still be online when Codex dispatches one. A proposal can dispatch only once; the queue keeps a short saved history in `fabric/world/infinity-agent-actions.json`. At most three active proposals are allowed per owner and 16 across the server. Restore this file with the matching world backup. The fixed command is never generated by a chat model. An Admin with actual OP4 can propose and give the player approval, but still needs the separate live Codex review and local-console dispatch.

Enabling Codex chat does not change this approval process. An **Ask Codex** reply can explain a proposal, but only the separate live review and local-console approval described above can dispatch it.

## Full operator access

The host can grant full in-game access with `op ExactPlayerName` in the server console. Bedrock accounts use their exact Floodgate name, including its prefix. Full access means **OP level 4**, the highest Minecraft operator level. The free Admin role also grants this vanilla permission level through a verified private code or `membership grantadmin PlayerName`; existing saved Admin roles are promoted on join. This includes vanilla commands such as `/give`, `/gamemode`, `/tp`, `/op` and `/stop`.

Level-4 operators can use all mod powers and cosmetics without achievements or item payment, in every game world. Operator powers bypass combat waits and Windstep cooldowns. Admin also bypasses Infinity weapon ability cooldowns and has the operator unlocks for gear and backpacks. Operators switch modes immediately, can enter Hardcore after elimination, and can use vanilla `/gamemode` without the mod forcing a mode afterward. Cross-world operator teleports save and restore the matching inventories and mode profiles. Operators bypass community teleport waits, home-slot limits, and chat moderation limits.

```text
/power hacks
/cosmetic dragon
/play creative
/gamemode creative
/give @s minecraft:diamond 64
/agent spawn buddy
```

The server still keeps separate mode profiles and backpack storage, checks valid names and coordinates, and requires both players to confirm trades. Commands that require a living player or a clear spawn location keep that requirement. One power preset is active at a time; use vanilla `/effect` for additional effects. Admin does not increase the six-helper/24-server-helper limits, AI request allowance or available host resources. AI actions and named-player attacks keep both approval gates even for Admin.

Remove Admin with `membership revokeadmin PlayerName`. It restores the operator state replaced by the role's own promotion, while retaining a pre-existing OP4 grant or a separately recognized manual operator grant. Remove any remaining independent grant with `deop` if needed. De-opping alone while the Admin role remains can be reversed by the role's permission sync. These privileges do not award permanent achievement or trade unlocks. See [MEMBERSHIPS.md](MEMBERSHIPS.md) for private-code setup, revocation and backup details.

The OpenAI API provider follows the [Responses API quickstart](https://developers.openai.com/api/docs/quickstart) and uses the configurable [GPT-6 Luna model](https://developers.openai.com/api/docs/models/gpt-6-luna) by default.


## Helper robot skin in explore.15

Helpers share a charcoal and cyan robot skin with a silver face. The chest carries a small white ChatGPT knot and the blue-purple Codex cloud containing a tiny terminal prompt. Both are pixel approximations within Minecraft’s 8 by 12 pixel chest, rather than official OpenAI avatars. Native signed Minecraft texture data is sent to Java clients and Geyser; clients download the skin from Minecraft’s texture host. The server does not need a MineSkin account or API key at runtime. Reconnect after the update to refresh tracked helper profiles. Your saved helpers, owners, names, stats, equipment and orders are preserved. A chestplate can cover the chest emblems while equipped.

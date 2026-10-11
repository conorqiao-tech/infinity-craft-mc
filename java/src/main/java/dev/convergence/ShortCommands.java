package dev.convergence;

import java.util.Map;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

/** Aliases reuse native nodes, suggestions, requirements and execution. Existing names win collisions. */
final class ShortCommands {
    static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("gm", "gamemode"), Map.entry("gr", "gamerule"), Map.entry("fx", "effect"),
        Map.entry("ench", "enchant"), Map.entry("loc", "locate"), Map.entry("diff", "difficulty"),
        Map.entry("attr", "attribute"), Map.entry("i", "give"), Map.entry("wx", "weather"),
        Map.entry("tm", "time"), Map.entry("ag", "agent"), Map.entry("ic", "convergence"),
        Map.entry("mg", "minigame"), Map.entry("adv", "adventure"), Map.entry("lb", "leaderboard"));

    static void install(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = dispatcher.getRoot();
        for (var entry : ALIASES.entrySet()) {
            var target = root.getChild(entry.getValue());
            if (target == null || root.getChild(entry.getKey()) != null) continue;
            var alias = Commands.literal(entry.getKey()).requires(target::canUse).redirect(target);
            if (target.getCommand() != null) alias.executes(target.getCommand());
            dispatcher.register(alias);
        }
        var nativeMode = root.getChild("gamemode");
        if (nativeMode != null) {
            for (var preset : Map.of("gmc", "creative", "gms", "survival", "gma", "adventure", "gmsp", "spectator").entrySet()) {
                if (root.getChild(preset.getKey()) != null) continue;
                String command = "gamemode " + preset.getValue();
                dispatcher.register(Commands.literal(preset.getKey()).requires(nativeMode::canUse)
                    .executes(c -> dispatcher.execute(command, c.getSource()))
                    .then(Commands.argument("targets", EntityArgument.players()).executes(c -> {
                        EntityArgument.getPlayers(c, "targets"); // Validate exactly like native gamemode before forwarding.
                        String targets = c.getNodes().getLast().getRange().get(c.getInput());
                        return dispatcher.execute(command + " " + targets, c.getSource());
                    })));
            }
        }
        if (root.getChild("shortcuts") == null) dispatcher.register(Commands.literal("shortcuts").executes(c -> {
            var source = c.getSource();
            source.sendSuccess(() -> Component.literal("Short commands • the original permissions still apply."), false);
            ALIASES.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                var alias = root.getChild(entry.getKey());
                if (alias != null && alias.getRedirect() == root.getChild(entry.getValue()) && alias.canUse(source))
                    source.sendSuccess(() -> Component.literal("/" + entry.getKey() + " = /" + entry.getValue() + " • same arguments"), false);
            });
            if (nativeMode != null && nativeMode.canUse(source))
                source.sendSuccess(() -> Component.literal("/gmc Creative • /gms Survival • /gma Adventure • /gmsp Spectator. Optional player name or selector after each."), false);
            return 1;
        }));
    }

    static void register() { CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> install(dispatcher)); }
}

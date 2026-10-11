package dev.convergence;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.SignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/** A native chest menu so Java and Geyser players can choose every built-in course. */
final class CourseSelector {
    private static final String SIGN_TITLE = "SELECT COURSE";
    private CourseSelector() {}

    static boolean supports(GameModes.Mode mode) {
        return mode == GameModes.Mode.MINIGAMES || mode == GameModes.Mode.ADVENTURE;
    }

    static List<ModeMaps.MapSpec> courses(GameModes.Mode mode) {
        return ModeMaps.MAPS.values().stream().filter(spec -> spec.mode() == mode && ModeMaps.available(spec.id())).toList();
    }

    static int slot(int index, int count) {
        return count <= 2 ? 11 + index * 4 : 10 + index + (index >= 3 ? 1 : 0);
    }

    static Item icon(String id) {
        return switch (id) {
            case "parkour" -> Items.FEATHER;
            case "sprint" -> Items.SUGAR;
            case "dropper" -> Items.WATER_BUCKET;
            case "redlight" -> Items.REDSTONE_TORCH;
            case "crystalhunt" -> Items.AMETHYST_SHARD;
            case "colorrush" -> Items.MAGENTA_GLAZED_TERRACOTTA;
            case "ruins" -> Items.CHISELED_STONE_BRICKS;
            case "maze" -> Items.LANTERN;
            default -> Items.MAP;
        };
    }

    static int open(ServerPlayerEntity player, GameModes.Mode mode) {
        if (!supports(mode) || !player.isAlive()
            || player.currentScreenHandler != player.playerScreenHandler
            || !player.currentScreenHandler.getCursorStack().isEmpty())
            return CommunityServer.say(player, "Close your current screen and empty the cursor before choosing a course.");
        var choices = courses(mode);
        if (choices.isEmpty()) return CommunityServer.say(player, "No verified courses are available in this world. Ask the host to check the server log.");
        if (choices.size() > 6) throw new IllegalStateException("Course selector exceeds six slots");
        var view = new SimpleInventory(27);
        for (int index = 0; index < choices.size(); index++) {
            var spec = choices.get(index);
            var icon = new ItemStack(icon(spec.id()));
            icon.set(DataComponentTypes.CUSTOM_NAME, Text.literal(spec.title()));
            view.setStack(slot(index, choices.size()), icon);
        }
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, inventory, who) ->
            new Handler(sync, inventory, view, player, mode, choices),
            Text.literal(mode == GameModes.Mode.MINIGAMES ? "Choose a Minigame" : "Choose an Adventure")));
        return 1;
    }

    static BlockPos signPos(ModeMaps.MapSpec spec) { return spec.points().getFirst().add(0, 0, 1); }

    static String signLabel(ModeMaps.MapSpec spec) {
        return switch (spec.id()) {
            case "sprint" -> "Switchback";
            case "maze" -> "Lantern Maze";
            default -> spec.title();
        };
    }

    static boolean selectorSign(ServerWorld world, BlockPos pos) {
        return world.getBlockEntity(pos) instanceof SignBlockEntity sign
            && sign.getFrontText().getMessage(0,false).getString().equals(SIGN_TITLE);
    }

    static boolean canPlaceSign(ServerWorld world, BlockPos pos) {
        var support = pos.down();
        return world.isInBuildLimit(pos) && world.getWorldBorder().contains(pos)
            && world.getBlockState(pos).isAir()
            && world.getBlockState(support).isSolidBlock(world, support);
    }

    static Set<String> installed(Path marker) {
        if (!Files.exists(marker)) return new LinkedHashSet<>();
        try {
            var data = JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
            if (data.get("version").getAsInt() != 1 || !data.has("maps")) throw new IllegalArgumentException("Invalid selector marker");
            var ids = new LinkedHashSet<String>();
            for (var id : data.getAsJsonArray("maps")) ids.add(id.getAsString());
            return ids;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot read course selector marker; existing signs were not changed.", error);
        }
    }

    static void installSigns(MinecraftServer server) {
        installSigns(server, server.getSavePath(WorldSavePath.ROOT).resolve("infinity-course-selector-signs.json"));
    }

    static void installSigns(MinecraftServer server, Path marker) {
        var done = installed(marker);
        var ready = new ArrayList<ModeMaps.MapSpec>();
        boolean changed = false;
        for (var spec : ModeMaps.MAPS.values()) {
            if (!ModeMaps.available(spec.id())) continue;
            var world = GameModes.world(server, spec.mode());
            var pos = signPos(spec);
            if (selectorSign(world, pos)) { changed |= done.add(spec.id()); continue; }
            if (!canPlaceSign(world, pos)) {
                System.err.println("[Infinity] Course selector sign for " + spec.id() + " could not be restored at " + pos + "; existing blocks were preserved.");
                continue;
            }
            ready.add(spec);
        }
        for (var spec : ready) {
            var world = GameModes.world(server, spec.mode());
            var pos = signPos(spec);
            world.setBlockState(pos, Blocks.OAK_SIGN.getDefaultState().with(SignBlock.ROTATION, 8), 3);
            if (!(world.getBlockEntity(pos) instanceof SignBlockEntity sign)) throw new IllegalStateException("Course selector sign did not load at " + pos);
            var lines = sign.getFrontText().withMessage(0, Text.literal(SIGN_TITLE))
                .withMessage(1, Text.literal(signLabel(spec)))
                .withMessage(2, Text.literal("RIGHT CLICK"));
            sign.setText(lines, true);
            sign.setText(lines, false);
            done.add(spec.id());
            changed = true;
        }
        if (changed || !Files.exists(marker)) {
            try { CommunityServer.atomicJson(marker, Map.of("version", 1, "maps", done)); }
            catch (java.io.IOException error) { throw new IllegalStateException("Cannot save course selector sign marker", error); }
        }
    }

    static ActionResult useLantern(ServerPlayerEntity player, BlockPos pos) {
        var mode = GameModes.current(player);
        if (!supports(mode)) return ActionResult.PASS;
        for (var spec : courses(mode)) {
            if (pos.equals(signPos(spec)) && selectorSign(player.getEntityWorld(), pos)) {
                open(player, mode); return ActionResult.SUCCESS;
            }
            if (!player.getEntityWorld().getBlockState(pos).isOf(Blocks.SEA_LANTERN)) continue;
            if (spec.kind() == ModeMaps.Kind.DROPPER) {
                if (pos.equals(spec.points().getFirst().down()) || pos.equals(spec.points().getLast().down(2))) {
                    open(player, mode); return ActionResult.SUCCESS;
                }
            } else for (var point : spec.points()) if (pos.equals(point.down())) {
                open(player, mode); return ActionResult.SUCCESS;
            }
        }
        return ActionResult.PASS;
    }

    static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
            return useLantern(serverPlayer, hit.getBlockPos());
        });
    }

    static final class Handler extends GenericContainerScreenHandler {
        final ServerPlayerEntity owner;
        final GameModes.Mode mode;
        final List<ModeMaps.MapSpec> choices;

        Handler(int sync, PlayerInventory inventory, SimpleInventory view, ServerPlayerEntity owner,
                GameModes.Mode mode, List<ModeMaps.MapSpec> choices) {
            super(ScreenHandlerType.GENERIC_9X3, sync, inventory, view, 3);
            this.owner = owner;
            this.mode = mode;
            this.choices = choices;
        }

        @Override public boolean canUse(PlayerEntity player) { return player == owner && owner.isAlive(); }
        @Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
        @Override public void selectBundleStack(int slot, int selected) { }

        @Override public void onSlotClick(int clicked, int button, SlotActionType action, PlayerEntity player) {
            if (player != owner || owner.currentScreenHandler != this || !owner.isAlive()) return;
            if ((action == SlotActionType.PICKUP || action == SlotActionType.QUICK_MOVE) && getCursorStack().isEmpty()) {
                for (int index = 0; index < choices.size(); index++) if (clicked == slot(index, choices.size())) {
                    String id = choices.get(index).id();
                    owner.closeHandledScreen();
                    GameModes.request(owner, mode, id);
                    return;
                }
            }
            syncState();
        }
    }
}

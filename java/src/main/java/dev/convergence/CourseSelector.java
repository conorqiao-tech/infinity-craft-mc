package dev.convergence;

import com.google.gson.JsonParser;
import net.minecraft.world.level.block.entity.SignTextSlot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.storage.LevelResource;

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
        if(count>6)return 10+index%4+(index/4)*9;
        return count <= 2 ? 11 + index * 4 : 10 + index + (index >= 3 ? 1 : 0);
    }

    static Item icon(String id) {
        return switch (id) {
            case "parkour" -> Items.FEATHER;
            case "sprint" -> Items.SUGAR;
            case "dropper" -> Items.WATER_BUCKET;
            case "redlight" -> Items.REDSTONE_TORCH;
            case "crystalhunt" -> Items.AMETHYST_SHARD;
            case "colorrush" -> Items.GLAZED_TERRACOTTA.magenta();
            case "memory" -> Items.ECHO_SHARD;
            case "gatedash" -> Items.REDSTONE_LAMP;
            case "ruins" -> Items.CHISELED_STONE_BRICKS;
            case "maze" -> Items.LANTERN;
            default -> Items.MAP;
        };
    }

    static int open(ServerPlayer player, GameModes.Mode mode) {
        if (!supports(mode) || !player.isAlive()
            || player.containerMenu != player.inventoryMenu
            || !player.containerMenu.getCarried().isEmpty())
            return CommunityServer.say(player, "Close your current screen and empty the cursor before choosing a course.");
        var choices = courses(mode);
        if (choices.isEmpty()) return CommunityServer.say(player, "No verified courses are available in this world. Ask the host to check the server log.");
        if (choices.size() > 8) throw new IllegalStateException("Course selector exceeds eight slots");
        var view = new SimpleContainer(27);
        for (int index = 0; index < choices.size(); index++) {
            var spec = choices.get(index);
            var icon = new ItemStack(icon(spec.id()));
            icon.set(DataComponents.CUSTOM_NAME, Component.literal(spec.title()));
            view.setItem(slot(index, choices.size()), icon);
        }
        player.openMenu(new SimpleMenuProvider((sync, inventory, who) ->
            new Handler(sync, inventory, view, player, mode, choices),
            Component.literal(mode == GameModes.Mode.MINIGAMES ? "Choose a Minigame" : "Choose an Adventure")));
        return 1;
    }

    static BlockPos signPos(ModeMaps.MapSpec spec) { return spec.points().getFirst().offset(0, 0, 1); }

    static String signLabel(ModeMaps.MapSpec spec) {
        return switch (spec.id()) {
            case "sprint" -> "Switchback";
            case "maze" -> "Lantern Maze";
            default -> spec.title();
        };
    }

    static boolean selectorSign(ServerLevel world, BlockPos pos) {
        return world.getBlockEntity(pos) instanceof SignBlockEntity sign
            && sign.getText(SignTextSlot.FRONT).getMessages(false).get(0).getString().equals(SIGN_TITLE);
    }

    static boolean canPlaceSign(ServerLevel world, BlockPos pos) {
        var support = pos.below();
        return world.isInWorldBounds(pos) && world.getWorldBorder().isWithinBounds(pos)
            && world.getBlockState(pos).isAir()
            && world.getBlockState(support).isRedstoneConductor(world, support);
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
        installSigns(server, server.getWorldPath(LevelResource.ROOT).resolve("infinity-course-selector-signs.json"));
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
            world.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8), 3);
            if (!(world.getBlockEntity(pos) instanceof SignBlockEntity sign)) throw new IllegalStateException("Course selector sign did not load at " + pos);
            var lines = sign.getText(SignTextSlot.FRONT).asMutable().setLine(0, Component.literal(SIGN_TITLE))
                .setLine(1, Component.literal(signLabel(spec)))
                .setLine(2, Component.literal("RIGHT CLICK")).asImmutable();
            sign.setText(lines, SignTextSlot.FRONT);
            sign.setText(lines, SignTextSlot.BACK);
            done.add(spec.id());
            changed = true;
        }
        if (changed || !Files.exists(marker)) {
            try { CommunityServer.atomicJson(marker, Map.of("version", 1, "maps", done)); }
            catch (java.io.IOException error) { throw new IllegalStateException("Cannot save course selector sign marker", error); }
        }
    }

    static InteractionResult useLantern(ServerPlayer player, BlockPos pos) {
        var mode = GameModes.current(player);
        if (!supports(mode)) return InteractionResult.PASS;
        for (var spec : courses(mode)) {
            if (pos.equals(signPos(spec)) && selectorSign(player.level(), pos)) {
                open(player, mode); return InteractionResult.SUCCESS;
            }
            if (!player.level().getBlockState(pos).is(Blocks.SEA_LANTERN)) continue;
            if (spec.kind() == ModeMaps.Kind.DROPPER) {
                if (pos.equals(spec.points().getFirst().below()) || pos.equals(spec.points().getLast().below(2))) {
                    open(player, mode); return InteractionResult.SUCCESS;
                }
            } else for (var point : spec.points()) if (pos.equals(point.below())) {
                open(player, mode); return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            return useLantern(serverPlayer, hit.getBlockPos());
        });
    }

    static final class Handler extends ChestMenu {
        final ServerPlayer owner;
        final GameModes.Mode mode;
        final List<ModeMaps.MapSpec> choices;

        Handler(int sync, Inventory inventory, SimpleContainer view, ServerPlayer owner,
                GameModes.Mode mode, List<ModeMaps.MapSpec> choices) {
            super(MenuType.GENERIC_9x3, sync, inventory, view, 3);
            this.owner = owner;
            this.mode = mode;
            this.choices = choices;
        }

        @Override public boolean stillValid(Player player) { return player == owner && owner.isAlive(); }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public void setSelectedBundleItemIndex(int slot, int selected) { }

        @Override public void clicked(int clicked, int button, ContainerInput action, Player player) {
            if (player != owner || owner.containerMenu != this || !owner.isAlive()) return;
            if ((action == ContainerInput.PICKUP || action == ContainerInput.QUICK_MOVE) && getCarried().isEmpty()) {
                for (int index = 0; index < choices.size(); index++) if (clicked == slot(index, choices.size())) {
                    String id = choices.get(index).id();
                    owner.closeContainer();
                    GameModes.request(owner, mode, id);
                    return;
                }
            }
            sendAllDataToRemote();
        }
    }
}

package dev.convergence;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class PlaygroundGameTests {
    @GameTest(maxTicks=80) public void creativeAdminReceivesOneAutomaticToolboxAfterClosingPicker(GameTestHelper c) {
        var fixture = new CreativeStudioGameTests(); var p = fixture.player(c, "admin-toolbox-auto");
        OperatorGameTests.level(p, net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
        c.runAfterDelay(10, () -> p.closeContainer());
        c.runAfterDelay(40, () -> {
            try {
                c.assertValueEqual(java.util.stream.IntStream.range(0,36).filter(i -> GearCrates.isAdminBox(p.getInventory().getItem(i))).count(), 1L, "Normal server ticks deliver the toolbox once after the entry picker closes");
                c.assertTrue(GameModes.state(p).getBooleanOr("admin_blocks_CREATIVE", false), "Automatic delivery saves its mode receipt");
                for (int i=0; i<36; i++) if (GearCrates.isAdminBox(p.getInventory().getItem(i))) p.getInventory().setItem(i, ItemStack.EMPTY);
            } catch (RuntimeException | Error failure) { fixture.cleanup(p); throw failure; }
        });
        c.runAfterDelay(65, () -> {
            try {
                c.assertFalse(java.util.stream.IntStream.range(0,36).anyMatch(i -> GearCrates.isAdminBox(p.getInventory().getItem(i))), "Placing or storing the box does not trigger repeated automatic gifts");
            } finally { fixture.cleanup(p); }
            c.succeed();
        });
    }
    @GameTest public void shortCommandsRunNativeArgumentsSuggestionsAndPermissions(GameTestHelper c) throws Exception {
        var p = new OperatorGameTests().operator(c, "short-commands");
        var dispatcher = c.getLevel().getServer().getCommands().getDispatcher();
        try {
            p.setGameMode(GameType.SURVIVAL);
            var root = dispatcher.getRoot();
            c.assertTrue(root.getChild("gm").getRedirect() == root.getChild("gamemode"), "Alias uses the actual native command node");
            c.assertTrue(root.getChild("ai").getRedirect() == null, "Existing answer-only AI chat command is preserved");
            var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("gm ", p.createCommandSourceStack())).get().getList();
            c.assertTrue(suggestions.stream().anyMatch(s -> s.getText().equals("creative")), "Native mode suggestions work through the alias");
            c.assertValueEqual(dispatcher.execute("gm creative", p.createCommandSourceStack()), 1, "Native gamemode parser executes through alias");
            c.assertTrue(p.isCreative(), "Real abilities change");
            c.assertValueEqual(dispatcher.execute("gms @s", p.createCommandSourceStack()), 1, "Preset accepts a real selector");
            c.assertValueEqual(p.gameMode(), GameType.SURVIVAL, "Preset changes actual selected player's mode");
            dispatcher.execute("i @s minecraft:diamond 3", p.createCommandSourceStack());
            c.assertValueEqual(java.util.stream.IntStream.range(0,36).mapToObj(i -> p.getInventory().getItem(i)).filter(s -> s.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum(), 3, "Native give arguments deliver real inventory items");
            dispatcher.execute("fx give @s minecraft:night_vision 30", p.createCommandSourceStack());
            c.assertTrue(p.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION), "Native effect arguments change actual effects");
            dispatcher.execute("ag", p.createCommandSourceStack());
            c.assertTrue(p.containerMenu instanceof AgentMenu.Handler, "Bare helper shortcut executes the original menu command");
            p.closeContainer(); OperatorGameTests.deop(p);
            var source = p.createCommandSourceStack();
            c.assertFalse(root.getChild("gm").canUse(source) || root.getChild("gmc").canUse(source), "No vanilla permission bypass");
            c.assertFalse(root.getChild("ag").canUse(source), "Helper shortcut loses actual OP4 access");
            try { dispatcher.execute("gmc", source); throw new AssertionError("Deopped player executed Creative preset"); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { }
            c.assertValueEqual(p.gameMode(), GameType.SURVIVAL, "Rejected preset keeps current abilities");
            c.assertValueEqual(dispatcher.execute("shortcuts", source), 1, "Ordinary players can read only their allowed shortcuts");
        } finally { p.closeContainer(); OperatorGameTests.deop(p); p.level().getServer().getPlayerList().remove(p); }
        c.succeed();
    }
    @GameTest public void shortCommandsPreserveExistingCommandNames(GameTestHelper c) throws Exception {
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        dispatcher.register(net.minecraft.commands.Commands.literal("gamemode").executes(context -> 11));
        dispatcher.register(net.minecraft.commands.Commands.literal("gm").executes(context -> 7));
        dispatcher.register(net.minecraft.commands.Commands.literal("gmc").executes(context -> 13));
        ShortCommands.install(dispatcher);
        var source = c.getLevel().getServer().createCommandSourceStack();
        c.assertValueEqual(dispatcher.execute("gm", source), 7, "Preexisting alias name keeps its own behavior");
        c.assertValueEqual(dispatcher.execute("gmc", source), 13, "Preexisting preset name is never overwritten");
        c.assertTrue(dispatcher.getRoot().getChild("fx") == null, "Missing targets do not advertise unusable aliases");
        c.succeed();
    }
    @GameTest public void adminToolboxPreservesFullInventoryCursorAndActualPermission(GameTestHelper c) {
        var fixture = new CreativeStudioGameTests(); var p = fixture.player(c, "admin-toolbox-safe");
        try {
            c.assertValueEqual(GearCrates.giveAdmin(p), 0, "Creative alone cannot obtain restricted toolbox");
            OperatorGameTests.level(p, net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
            for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
            c.assertValueEqual(GearCrates.giveAdmin(p), 0, "Full inventory refuses delivery");
            for (int i = 0; i < 36; i++) c.assertValueEqual(p.getInventory().getItem(i).getCount(), 64, "No owned stacks lost");
            p.getInventory().setItem(0, ItemStack.EMPTY); p.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));
            c.assertValueEqual(GearCrates.giveAdmin(p), 0, "Occupied cursor refuses delivery");
            c.assertTrue(p.inventoryMenu.getCarried().is(Items.DIAMOND), "Cursor diamond is preserved");
            p.inventoryMenu.setCarried(ItemStack.EMPTY); p.setGameMode(GameType.SURVIVAL);
            c.assertValueEqual(GearCrates.giveAdmin(p), 0, "OP4 Survival does not receive Creative blocks");
            p.setGameMode(GameType.CREATIVE); CreativeGearPicker.open(p); var menu = p.containerMenu;
            c.assertTrue(menu.getSlot(CreativeGearPicker.ADMIN_BLOCKS).getItem().is(Items.COMMAND_BLOCK), "Creative inventory menu displays the actual Admin Blocks button");
            menu.clicked(CreativeGearPicker.ADMIN_BLOCKS, 0, ContainerInput.PICKUP, p);
            c.assertTrue(GearCrates.isAdminBox(p.getInventory().getItem(0)), "Menu delivers the native toolbox to the empty slot");
            var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, p.registryAccess()); p.saveWithoutId(output);
            p.getInventory().setItem(0, ItemStack.EMPTY);
            p.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, p.registryAccess(), output.buildResult()));
            c.assertTrue(GearCrates.isAdminBox(p.getInventory().getItem(0)), "Box components survive native player save/reload");
            OperatorGameTests.deop(p); CreativeGearPicker.open(p); menu = p.containerMenu;
            c.assertTrue(menu.getSlot(CreativeGearPicker.ADMIN_BLOCKS).getItem().isEmpty(), "Non-operator no longer sees restricted inventory button");
            menu.clicked(CreativeGearPicker.ADMIN_BLOCKS, 0, ContainerInput.PICKUP, p);
            c.assertValueEqual(java.util.stream.IntStream.range(0,36).filter(i -> GearCrates.isAdminBox(p.getInventory().getItem(i))).count(), 1L, "Forged hidden-button click cannot issue another box");
        } finally { p.inventoryMenu.setCarried(ItemStack.EMPTY); fixture.cleanup(p); }
        c.succeed();
    }
    @GameTest public void adminToolboxPlacesAllTenUsableNativeItems(GameTestHelper c) {
        var p = new OperatorGameTests().operator(c, "admin-toolbox-place"); p.setGameMode(GameType.CREATIVE);
        var base = c.absolutePos(new BlockPos(2,2,2));
        try {
            c.assertValueEqual(GearCrates.giveAdmin(p), 1, "OP4 Creative receives a real box");
            var box = java.util.stream.IntStream.range(0,36).mapToObj(i -> p.getInventory().getItem(i)).filter(GearCrates::isAdminBox).findFirst().orElseThrow();
            c.getLevel().setBlockAndUpdate(base, Blocks.STONE.defaultBlockState()); c.getLevel().setBlockAndUpdate(base.above(), Blocks.AIR.defaultBlockState());
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, box); p.setPos(base.getX()+4.5, base.getY()+1, base.getZ()+.5);
            var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(base).add(0,.5,0), net.minecraft.core.Direction.UP, base, false);
            box.getItem().useOn(new net.minecraft.world.item.context.UseOnContext(p, net.minecraft.world.InteractionHand.MAIN_HAND, hit));
            var placed = (net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)c.getLevel().getBlockEntity(base.above());
            c.assertTrue(placed != null, "Native item use places a real shulker storage block");
            for (int i=0; i<GearCrates.ADMIN_ITEMS.size(); i++) {
                var item = GearCrates.ADMIN_ITEMS.get(i); var stack = placed.getItem(i);
                c.assertTrue(stack.is(item), "Placed box retains native admin item in its correct slot");
                c.assertValueEqual(stack.getCount(), item.getDefaultMaxStackSize(), "Correct native stack size");
            }
            c.assertValueEqual(GearCrates.ADMIN_ITEMS.size(), 10, "All ten supported admin items are present");
        } finally { c.getLevel().removeBlock(base.above(), false); OperatorGameTests.deop(p); p.level().getServer().getPlayerList().remove(p); }
        c.succeed();
    }
    @GameTest public void eliminatedHardcoreCourseBackButtonRoutesToHub(GameTestHelper c) {
        var p = new ModeGameTests().player(c, "course-hardcore-back");
        try {
            GameModes.switchNow(p, GameModes.Mode.HARDCORE, null); GameModes.state(p).putBoolean("eliminated", true); p.setGameMode(GameType.SPECTATOR);
            CourseSelector.open(p, GameModes.Mode.MINIGAMES); var menu = p.containerMenu;
            c.assertTrue(menu.getSlot(CourseSelector.BACK).getItem().getHoverName().getString().equals("Return to Main Hub"), "Eliminated player sees an actual exit route");
            menu.clicked(CourseSelector.BACK, 0, ContainerInput.PICKUP, p);
            c.assertValueEqual(GameModes.PENDING.get(p.getUUID()).mode(), GameModes.Mode.HUB, "Return uses native mode transfer rather than a spectator-blocked menu");
        } finally { p.closeContainer(); GameModes.PENDING.remove(p.getUUID()); p.level().getServer().getPlayerList().remove(p); }
        c.succeed();
    }
    static final class SignalFixture implements AutoCloseable {
        final ServerPlayer player;
        final net.minecraft.server.level.ServerLevel world;
        final net.minecraft.world.level.chunk.LevelChunk chunk;
        final net.minecraft.world.level.levelgen.structure.Structure ruin;
        final net.minecraft.world.level.levelgen.structure.StructureStart previous;
        final OdysseyStructure.Piece piece;
        final BlockPos signal;
        SignalFixture(GameTestHelper c, String name) {
            player = new ModeGameTests().player(c, name); world = c.getLevel();
            piece = new OdysseyStructure.Piece(world.getStructureTemplateManager(), c.absolutePos(new BlockPos(1, 2, 1)), "plains", net.minecraft.world.level.block.Rotation.NONE);
            signal = piece.getBoundingBox().getCenter(); chunk = world.getChunkAt(signal);
            ruin = world.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(OdysseyStructure.KEY).value();
            previous = chunk.getStartForStructure(ruin);
            chunk.setStartForStructure(ruin, net.minecraft.world.level.levelgen.structure.StructureStart.INVALID_START);
            player.setGameMode(GameType.SURVIVAL); player.setPos(Vec3.atBottomCenterOf(signal.offset(24, 0, 0)));
        }
        void install() {
            chunk.setStartForStructure(ruin, new net.minecraft.world.level.levelgen.structure.StructureStart(ruin, chunk.getPos(), 0,
                new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(List.of(piece))));
        }
        public void close() {
            chunk.setStartForStructure(ruin, previous == null ? net.minecraft.world.level.levelgen.structure.StructureStart.INVALID_START : previous);
            player.inventoryMenu.setCarried(ItemStack.EMPTY); player.closeContainer(); world.getServer().getPlayerList().remove(player);
        }
    }
    @GameTest public void circuitCompassFindsRealLoadedRuinsAndPersistsWithoutGrantingProgress(GameTestHelper c) {
        try (var fixture = new SignalFixture(c, "compass-discovery")) {
            var p = fixture.player;
            c.assertTrue(OdysseyCompass.nearest(fixture.world, p.blockPosition()) == null, "No structure start means no invented signal");
            fixture.install();
            c.assertValueEqual(OdysseyCompass.scan(p), 1, "Actual generated piece supplies a circuit compass");
            var compass = java.util.stream.IntStream.range(0, 36).mapToObj(i -> p.getInventory().getItem(i)).filter(s -> OdysseyCompass.owned(s, p)).findFirst().orElseThrow();
            var tracker = compass.get(DataComponents.LODESTONE_TRACKER);
            c.assertValueEqual(tracker.target().orElseThrow().pos(), fixture.signal, "Native compass targets the registered piece");
            c.assertValueEqual(tracker.target().orElseThrow().dimension(), fixture.world.dimension(), "Target keeps its real dimension");
            c.assertFalse(tracker.tracked(), "Pointer survives without a lodestone block");
            c.assertValueEqual(OdysseyJournal.count(p), 0, "Scanning cannot replace physical discovery");
            compass.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("My Keeper Compass"));
            int slot = java.util.stream.IntStream.range(0, 36).filter(i -> p.getInventory().getItem(i) == compass).findFirst().orElseThrow();
            p.getInventory().setItem(slot, ItemStack.EMPTY); p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, compass);
            c.assertValueEqual(OdysseyCompass.scan(p), 1, "Offhand compass is updated rather than duplicated");
            c.assertValueEqual(compass.getHoverName().getString(), "My Keeper Compass", "Anvil name survives rescanning");
            c.assertFalse(java.util.stream.IntStream.range(0, 36).anyMatch(i -> OdysseyCompass.owned(p.getInventory().getItem(i), p)), "Rescanning does not issue another compass");
            var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, p.registryAccess()); p.saveWithoutId(output);
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            p.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, p.registryAccess(), output.buildResult()));
            c.assertValueEqual(p.getOffhandItem().get(DataComponents.LODESTONE_TRACKER), tracker, "Native inventory save/load preserves pointer");
            p.setPos(Vec3.atBottomCenterOf(fixture.signal.offset(OdysseyCompass.RANGE + 1, 0, 0)));
            c.assertTrue(OdysseyCompass.nearest(fixture.world, p.blockPosition()) == null, "Outside the advertised radius produces no signal");
            c.assertTrue(fixture.world.getChunkSource().getChunkNow(1000000, 1000000) == null, "Scanning does not generate distant chunks");
        } c.succeed();
    }
    @GameTest public void compassPreservesFullInventoryAndBusyCursor(GameTestHelper c) {
        try (var fixture = new SignalFixture(c, "compass-inventory")) {
            var p = fixture.player; fixture.install();
            for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
            c.assertValueEqual(OdysseyCompass.scan(p), 0, "Full inventory reports a signal without replacing items");
            for (int i = 0; i < 36; i++) c.assertValueEqual(p.getInventory().getItem(i).getCount(), 64, "Existing stacks stay whole");
            p.getInventory().setItem(0, ItemStack.EMPTY); p.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));
            c.assertValueEqual(OdysseyCompass.scan(p), 0, "Busy cursor rejects delivery");
            c.assertTrue(p.inventoryMenu.getCarried().is(Items.DIAMOND), "Held cursor item stays untouched");
            c.assertTrue(p.getInventory().getItem(0).isEmpty(), "Rejected scan consumes no slot");
            p.inventoryMenu.setCarried(ItemStack.EMPTY);
            c.assertValueEqual(OdysseyCompass.scan(p), 1, "Cleared inventory permits retry");
            c.assertValueEqual(OdysseyCompass.scan(p), 1, "Repeated scan updates the one issued item");
            c.assertValueEqual(java.util.stream.IntStream.range(0, 36).filter(i -> OdysseyCompass.owned(p.getInventory().getItem(i), p)).count(), 1L, "One owner compass, no duplicate");
        } c.succeed();
    }
    @GameTest public void courseMenuShowsRealInstructionsAndRecordsAndRetriesWithoutCommands(GameTestHelper c) {
        var p = new ModeGameTests().player(c, "course-controls");
        try {
            GameModes.switchNow(p, GameModes.Mode.MINIGAMES, "memory");
            var scores = GameModes.state(p).getCompoundOrEmpty("scores"); scores.putLong("memory", 3200); GameModes.state(p).put("scores", scores);
            MinigameRecords.sync(p); CourseSelector.open(p, GameModes.Mode.MINIGAMES);
            var menu = p.containerMenu; var courses = CourseSelector.courses(GameModes.Mode.MINIGAMES);
            int index = java.util.stream.IntStream.range(0, courses.size()).filter(i -> courses.get(i).id().equals("memory")).findFirst().orElseThrow();
            var lore = menu.getSlot(CourseSelector.slot(index, courses.size())).getItem().get(DataComponents.LORE).lines().stream().map(net.minecraft.network.chat.Component::getString).collect(java.util.stream.Collectors.joining(" "));
            c.assertTrue(lore.contains("3.20s") && lore.contains("Memorize the five colors"), "Actual saved best and gameplay instructions appear together");
            menu.clicked(CourseSelector.RECORDS, 0, ContainerInput.PICKUP, p); menu = p.containerMenu;
            c.assertTrue(((CourseSelector.Handler) menu).recordsPage, "Records button opens the native records page");
            var recordLore = menu.getSlot(CourseSelector.slot(index, courses.size())).getItem().get(DataComponents.LORE).lines().stream().map(net.minecraft.network.chat.Component::getString).collect(java.util.stream.Collectors.joining(" "));
            c.assertTrue(recordLore.contains(p.getName().getString()) && recordLore.contains("3.20s"), "Public native leaderboard displays the saved finish");
            var run = ModeMaps.RUNS.get(p.getUUID());
            menu.clicked(CourseSelector.slot(index, courses.size()), 0, ContainerInput.PICKUP, p);
            c.assertTrue(ModeMaps.RUNS.get(p.getUUID()) == run, "Read-only record icons cannot start a run");
            menu.clicked(CourseSelector.RETRY, 0, ContainerInput.PICKUP, p);
            c.assertTrue(ModeMaps.RUNS.get(p.getUUID()) != run && ModeMaps.RUNS.get(p.getUUID()).map.equals("memory"), "Retry resets the actual selected course");
            c.assertValueEqual(CourseSelector.best(p, "memory"), "3.20s", "Retry preserves progress");
            CourseSelector.open(p, GameModes.Mode.MINIGAMES); p.containerMenu.clicked(CourseSelector.BACK, 0, ContainerInput.PICKUP, p);
            c.assertTrue(p.containerMenu instanceof ServerMenu.Handler, "Back returns to the actual Infinity Menu");
        } finally { p.closeContainer(); GameModes.PENDING.remove(p.getUUID()); ModeMaps.RUNS.remove(p.getUUID()); p.level().getServer().getPlayerList().remove(p); }
        c.succeed();
    }
    @GameTest public void courseMenuRejectsForeignMalformedBusyAndWorldStaleClicks(GameTestHelper c) {
        var p = new ModeGameTests().player(c, "course-session"); var other = new ModeGameTests().player(c, "course-other");
        try {
            CourseSelector.open(p, GameModes.Mode.MINIGAMES); var menu = p.containerMenu;
            int slot = CourseSelector.slot(0, CourseSelector.courses(GameModes.Mode.MINIGAMES).size());
            menu.clicked(slot, 0, ContainerInput.PICKUP, other); menu.clicked(slot, 9, ContainerInput.PICKUP, p);
            c.assertFalse(GameModes.PENDING.containsKey(p.getUUID()), "Foreign and invalid mouse buttons cannot route a player");
            menu.setCarried(new ItemStack(Items.DIAMOND)); menu.clicked(slot, 0, ContainerInput.QUICK_MOVE, p);
            c.assertTrue(menu.getCarried().is(Items.DIAMOND), "Icons cannot overwrite an occupied cursor");
            menu.setCarried(ItemStack.EMPTY); GameModes.TRANSITIONS.add(p.getUUID());
            c.assertFalse(menu.stillValid(p), "A pending transition invalidates the course session");
            menu.clicked(slot, 0, ContainerInput.PICKUP, p); GameModes.TRANSITIONS.remove(p.getUUID());
            c.assertFalse(GameModes.PENDING.containsKey(p.getUUID()), "Transition-time click cannot queue another transfer");
            CourseSelector.open(p, GameModes.Mode.MINIGAMES); menu = p.containerMenu;
            GameModes.switchNow(p, GameModes.Mode.CREATIVE, null);
            c.assertFalse(menu.stillValid(p), "Moving to another world invalidates the old screen");
            menu.clicked(slot, 0, ContainerInput.PICKUP, p);
            c.assertValueEqual(GameModes.current(p), GameModes.Mode.CREATIVE, "Delayed old click cannot change the new mode");
        } finally { p.closeContainer(); GameModes.TRANSITIONS.remove(p.getUUID()); GameModes.PENDING.remove(p.getUUID()); ModeMaps.RUNS.remove(p.getUUID()); p.level().getServer().getPlayerList().remove(p); other.level().getServer().getPlayerList().remove(other); }
        c.succeed();
    }
    @GameTest(structure="convergence_tests:combat_arena") public void squadProfileControlsPreserveNativeStatsAndBothPlayerCombatApprovals(GameTestHelper c) {
        try (var arena = new AgentTacticsGameTests.Arena(c, "squad-controls")) {
            var peer = arena.add("beta", arena.start.add(2, 0, 0));
            arena.helpers.mode(arena.owner, "beta", AgentCompanions.Mode.STAY);
            peer.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000); peer.setHealth(90);
            AgentMenu.open(arena.owner); var menu = arena.owner.containerMenu;
            menu.clicked(AgentMenu.SQUAD_REGULAR, 0, ContainerInput.PICKUP, arena.owner);
            c.assertValueEqual(arena.record(arena.golem).profile(), AgentCompanions.Profile.REGULAR, "Squad button changes first helper");
            c.assertValueEqual(arena.record(peer).profile(), AgentCompanions.Profile.REGULAR, "Squad button changes second helper");
            c.assertValueEqual(arena.record(peer).mode(), AgentCompanions.Mode.STAY, "Profile does not silently resume Stay");
            c.assertValueEqual(peer.getHealth(), 90F, "Current health is preserved");
            c.assertValueEqual(peer.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).getBaseValue(), 1000D, "Edited native attributes are preserved");
            menu.clicked(AgentMenu.SQUAD_ULTIMATE, 0, ContainerInput.PICKUP, arena.owner);
            c.assertTrue(arena.helpers.playerTargets.isEmpty(), "Aggressive profile does not authorize player targeting");
            var saved = AgentCompanions.read(arena.helpers.file);
            c.assertValueEqual(saved.agents.get(peer.getStringUUID()).profile(), AgentCompanions.Profile.ULTIMATE_FINALS, "Squad profile persists to actual saved roster");
            OperatorGameTests.deop(arena.owner); menu.clicked(AgentMenu.SQUAD_PRIMITIVE, 0, ContainerInput.PICKUP, arena.owner);
            c.assertValueEqual(arena.record(peer).profile(), AgentCompanions.Profile.ULTIMATE_FINALS, "Lost OP4 access cannot change roster");
        } c.succeed();
    }
    @GameTest(structure="convergence_tests:combat_arena") public void helperMenuCannotControlTheSquadAfterChangingWorld(GameTestHelper c) {
        try (var arena = new AgentTacticsGameTests.Arena(c, "helper-session")) {
            AgentMenu.open(arena.owner); var menu = arena.owner.containerMenu;
            GameModes.switchNow(arena.owner, GameModes.Mode.CREATIVE, null);
            c.assertFalse(menu.stillValid(arena.owner), "Helper controls retain their original world session");
            menu.clicked(AgentMenu.SQUAD_PRIMITIVE, 0, ContainerInput.PICKUP, arena.owner);
            c.assertValueEqual(arena.record(arena.golem).profile(), AgentCompanions.Profile.ULTIMATE_FINALS, "Old window cannot reconfigure a helper");
        } c.succeed();
    }
    @GameTest public void nativeGadgetsWorkThroughHeldPowerAndRespectCreativeAccess(GameTestHelper c){
        var fixture=new CreativeStudioGameTests();var p=fixture.player(c,"playground-gadgets");
        try{
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,CreativeStudio.control(p,"gust"));
            c.assertValueEqual(CrossplaySupport.usePower(p,false),1,"Native feather gadget works through the menu's actual power dispatch");
            c.assertTrue(p.getDeltaMovement().y>0,"Real player receives the upward gust");
            c.assertValueEqual(CreativeStudio.controlKind(p,new ItemStack(Items.FEATHER)),"","Ordinary feathers keep native behavior");
            p.setGameMode(GameType.SURVIVAL);
            c.assertValueEqual(CreativeStudio.useControl(p,CreativeStudio.control(p,"anchor")),0,"Survival cannot use Creative teleport tools");
        }finally{fixture.cleanup(p);}c.succeed();
    }
    @GameTest public void anchorReturnsSafelyAndRefusesBlockedOrDistantLocations(GameTestHelper c){
        var fixture=new CreativeStudioGameTests();var p=fixture.player(c,"playground-anchor");
        try{
            var origin=p.position();CreativeStudio.setAnchor(p);p.setPos(origin.add(4,0,0));
            c.assertValueEqual(CreativeStudio.returnAnchor(p),1,"Same-world loaded anchor returns the real player");
            c.assertTrue(p.position().distanceToSqr(origin)<.001,"Position restored exactly");
            p.setPos(origin.add(4,0,0));var block=BlockPos.containing(origin);
            p.level().setBlock(block,Blocks.STONE.defaultBlockState(),3);
            c.assertValueEqual(CreativeStudio.returnAnchor(p),0,"Occupied destination cannot trap player");
            c.assertTrue(p.position().distanceToSqr(origin)>1,"Rejected teleport preserves position");
            p.level().removeBlock(block,false);p.setPos(origin.add(49,0,0));
            c.assertValueEqual(CreativeStudio.returnAnchor(p),0,"Distance limit cannot load faraway terrain");
        }finally{fixture.cleanup(p);}c.succeed();
    }
    @GameTest public void tidewardenHasAllFourNativeArmorSlotsAndAtomicDelivery(GameTestHelper c){
        var fixture=new CreativeStudioGameTests();var p=fixture.player(c,"playground-tide");
        try{
            var stacks=CreativeStudio.outfit(p,3);c.assertValueEqual(stacks.size(),4,"All four pieces exist");
            var slots=new java.util.HashSet<net.minecraft.world.entity.EquipmentSlot>();
            for(var stack:stacks){slots.add(stack.get(DataComponents.EQUIPPABLE).slot());c.assertTrue(stack.has(DataComponents.TRIM),"Native trim is present");c.assertTrue(stack.getHoverName().getString().startsWith("Tidewarden"),"Literal readable name");}
            c.assertValueEqual(slots.size(),4,"Helmet, chestplate, leggings and boots use different slots");
            for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.DIRT,64));
            for(int i=0;i<3;i++)p.getInventory().setItem(i,ItemStack.EMPTY);
            c.assertValueEqual(CreativeStudio.deliver(p,stacks),0,"Three slots cannot partially deliver four pieces");
            for(int i=0;i<3;i++)c.assertTrue(p.getInventory().getItem(i).isEmpty(),"No partial reward or duplication");
        }finally{fixture.cleanup(p);}c.succeed();
    }
    @GameTest public void journalRequiresAnActualGeneratedPieceAndPersistsNativePlayerData(GameTestHelper c){
        var p=new ModeGameTests().player(c,"playground-discovery");var world=c.getLevel();
        var at=c.absolutePos(new BlockPos(1,2,1));var piece=new OdysseyStructure.Piece(world.getStructureTemplateManager(),at,"plains",net.minecraft.world.level.block.Rotation.NONE);
        var pos=piece.getBoundingBox().getCenter();var chunk=world.getChunkAt(pos);
        var ruin=world.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(OdysseyStructure.KEY).value();
        var old=chunk.getStartForStructure(ruin);var refs=new it.unimi.dsi.fastutil.longs.LongOpenHashSet(chunk.getReferencesForStructure(ruin));
        try{
            p.setGameMode(GameType.SURVIVAL);p.setPos(Vec3.atBottomCenterOf(pos));
            c.assertFalse(OdysseyJournal.discover(p),"No structure start means no discovery");
            var start=new net.minecraft.world.level.levelgen.structure.StructureStart(ruin,chunk.getPos(),0,new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(List.of(piece)));
            chunk.setStartForStructure(ruin,start);chunk.addReferenceForStructure(ruin,chunk.getPos().pack());
            c.assertTrue(OdysseyJournal.discover(p),"Standing inside the original generated piece unlocks its realm");
            c.assertFalse(OdysseyJournal.discover(p),"Remaining inside cannot count repeatedly");
            var output=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,p.registryAccess());p.saveWithoutId(output);
            GameModes.state(p).remove(OdysseyJournal.KEY);
            p.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,p.registryAccess(),output.buildResult()));
            c.assertValueEqual(OdysseyJournal.count(p),1,"Native save/load keeps realm progress");
        }finally{chunk.setStartForStructure(ruin,old==null?net.minecraft.world.level.levelgen.structure.StructureStart.INVALID_START:old);chunk.getReferencesForStructure(ruin).clear();chunk.getReferencesForStructure(ruin).addAll(refs);p.level().getServer().getPlayerList().remove(p);}c.succeed();
    }
    @GameTest public void journalRewardRetriesFullInventoryWithoutDuplicatingGifts(GameTestHelper c){
        var p=new ModeGameTests().player(c,"playground-gifts");
        try{
            p.setGameMode(GameType.SURVIVAL);OdysseyJournal.record(p,0,p.blockPosition(),"plains");OdysseyJournal.record(p,1,p.blockPosition(),"warped");
            for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.DIRT,64));
            c.assertValueEqual(OdysseyJournal.claim(p,2),0,"Full inventory refuses complete outfit");
            c.assertFalse(OdysseyJournal.state(p).getBooleanOr("claimed_2",false),"Gift remains claimable");
            for(int i=0;i<4;i++)p.getInventory().setItem(i,ItemStack.EMPTY);
            c.assertValueEqual(OdysseyJournal.claim(p,2),4,"Exactly four empty slots receive the full set");
            c.assertValueEqual(OdysseyJournal.claim(p,2),0,"Repeated claim does not duplicate gear");
            c.assertFalse(OdysseyJournal.earned(p,4),"Trials reward cannot skip three-realm journey");
        }finally{p.level().getServer().getPlayerList().remove(p);}c.succeed();
    }
    @GameTest public void journalMenuPreservesCursorAndRejectsStaleOrForeignClicks(GameTestHelper c){
        var p=new ModeGameTests().player(c,"journal-safe-menu");var other=new ModeGameTests().player(c,"journal-foreign");
        try{
            p.setGameMode(GameType.SURVIVAL);OdysseyJournal.record(p,0,p.blockPosition(),"plains");OdysseyJournal.open(p);
            var menu=p.containerMenu;
            menu.clicked(10,0,ContainerInput.QUICK_MOVE,p);
            c.assertTrue(menu.getCarried().isEmpty(),"Realm icon cannot be stolen into the cursor");
            menu.clicked(19,0,ContainerInput.PICKUP,other);
            c.assertFalse(OdysseyJournal.state(p).getBooleanOr("claimed_1",false),"Another player cannot claim the owner's gift");
            menu.setCarried(new ItemStack(Items.DIAMOND));menu.clicked(19,0,ContainerInput.PICKUP,p);
            c.assertTrue(menu.getCarried().is(Items.DIAMOND),"Occupied cursor is preserved rather than discarded");
            c.assertFalse(OdysseyJournal.state(p).getBooleanOr("claimed_1",false),"Busy cursor cannot consume a gift");
            menu.setCarried(ItemStack.EMPTY);p.closeContainer();menu.clicked(19,0,ContainerInput.PICKUP,p);
            c.assertFalse(OdysseyJournal.state(p).getBooleanOr("claimed_1",false),"Closed window cannot act on a delayed click");
        }finally{p.closeContainer();p.level().getServer().getPlayerList().remove(p);other.level().getServer().getPlayerList().remove(other);}c.succeed();
    }
    @GameTest public void expandedLeaderboardRetainsOldAndNewCoursesAfterReload(GameTestHelper c){
        try{
            var file=java.nio.file.Files.createTempDirectory("playground-records-").resolve("records.json");
            var records=new MinigameRecords(file);var id=java.util.UUID.randomUUID();
            records.upsert("parkour",new MinigameRecords.Score(id,"Runner",1800));records.save();
            var upgraded=new MinigameRecords(file);
            c.assertTrue(upgraded.upsert("memory",new MinigameRecords.Score(id,"Runner",2500)),"Memory course is registered in the actual public index");
            c.assertTrue(upgraded.upsert("gatedash",new MinigameRecords.Score(id,"Runner",3000)),"Laser course is registered in the actual public index");
            upgraded.save();var loaded=new MinigameRecords(file);
            c.assertValueEqual(loaded.ordered("parkour").getFirst().millis(),1800L,"Old course survives upgrade");
            c.assertValueEqual(loaded.ordered("memory").getFirst().millis(),2500L,"Memory score survives restart");
            c.assertValueEqual(loaded.ordered("gatedash").getFirst().millis(),3000L,"Laser score survives restart");
        }catch(java.io.IOException e){throw new RuntimeException(e);}c.succeed();
    }
    @GameTest public void memoryCourseRequiresSequenceAndPersistsARealFinish(GameTestHelper c){
        var p=new ModeGameTests().player(c,"playground-memory");
        try{
            GameModes.switchNow(p,GameModes.Mode.MINIGAMES,"memory");p.setNoGravity(true);p.setOnGround(true);
            var run=ModeMaps.RUNS.get(p.getUUID());var spec=ModeMaps.MAPS.get("memory");
            int tick=run.colorDeadline+1;
            for(int i=0;i<ModeMaps.COLOR_ROUNDS;i++){
                p.setPos(Vec3.atBottomCenterOf(spec.points().getFirst()));ModeMaps.tick(p,tick++);
                p.setPos(Vec3.atBottomCenterOf(spec.points().get(run.colors[i]+1)));ModeMaps.tick(p,tick++);
            }
            c.assertFalse(ModeMaps.RUNS.containsKey(p.getUUID()),"Physical correct pad sequence completes the course");
            c.assertTrue(GameModes.state(p).getCompoundOrEmpty("scores").getLongOr("memory",0)>0,"Personal best saved with real player state");
            ModeMaps.begin(p,"memory");run=ModeMaps.RUNS.get(p.getUUID());
            p.setPos(Vec3.atBottomCenterOf(spec.points().get((run.colors[0]+1)%4+1)));ModeMaps.tick(p,run.colorDeadline+1);
            c.assertTrue(ModeMaps.RUNS.get(p.getUUID())!=run,"Wrong first color actually restarts");
        }finally{p.closeContainer();ModeMaps.RUNS.remove(p.getUUID());p.level().getServer().getPlayerList().remove(p);}c.succeed();
    }
    @GameTest public void laserGateCourseRejectsRedAndCompletesGreenCheckpoints(GameTestHelper c){
        var p=new ModeGameTests().player(c,"playground-lasers");
        try{
            GameModes.switchNow(p,GameModes.Mode.MINIGAMES,"gatedash");p.setNoGravity(true);p.setOnGround(true);
            var run=ModeMaps.RUNS.get(p.getUUID());var spec=ModeMaps.MAPS.get("gatedash");
            p.setPos(Vec3.atBottomCenterOf(spec.points().get(1)));ModeMaps.tick(p,run.startedTick+70);
            c.assertTrue(ModeMaps.RUNS.get(p.getUUID())!=run,"A red gate resets the player and timer");
            run=ModeMaps.RUNS.get(p.getUUID());
            for(int i=1;i<spec.points().size();i++){
                p.setPos(Vec3.atBottomCenterOf(spec.points().get(i)));ModeMaps.tick(p,run.startedTick+i);
            }
            c.assertFalse(ModeMaps.RUNS.containsKey(p.getUUID()),"Green checkpoint crossings complete the real course");
            c.assertTrue(GameModes.state(p).getCompoundOrEmpty("scores").getLongOr("gatedash",0)>0,"New game participates in score persistence");
        }finally{p.closeContainer();ModeMaps.RUNS.remove(p.getUUID());p.level().getServer().getPlayerList().remove(p);}c.succeed();
    }
    @GameTest(structure="convergence_tests:combat_arena") public void helperFormationsUseDistinctSafeGroundAndKeepApprovalGates(GameTestHelper c){
        try(var arena=new AgentTacticsGameTests.Arena(c,"playground-formation")){
            arena.target.discard();var peer=arena.add("beta",arena.start.add(2,0,0));
            AgentCompanions.cycleFormation(arena.owner);
            var a=arena.helpers.formationPoint(arena.golem,arena.record(arena.golem),arena.owner);
            var b=arena.helpers.formationPoint(peer,arena.record(peer),arena.owner);
            c.assertTrue(a.distanceToSqr(b)>4,"Wedge gives different followers distinct slots");
            c.assertTrue(arena.helpers.landingClear(arena.golem,a),"Follower destination has solid clear loaded ground");
            c.assertTrue(arena.helpers.playerTargets.isEmpty(),"Formation does not authorize player combat");
            AgentMenu.open(arena.owner);var menu=arena.owner.containerMenu;
            menu.clicked(AgentMenu.FORMATION,0,ContainerInput.PICKUP,arena.owner);
            c.assertValueEqual(AgentCompanions.formation(arena.owner),AgentCompanions.Formation.RING,"Actual menu cycles saved formation");
            arena.owner.closeContainer();
            var output=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,arena.owner.registryAccess());arena.owner.saveWithoutId(output);
            GameModes.state(arena.owner).remove("helper_formation");
            arena.owner.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,arena.owner.registryAccess(),output.buildResult()));
            c.assertValueEqual(AgentCompanions.formation(arena.owner),AgentCompanions.Formation.RING,"Formation survives native player save and load");
            OperatorGameTests.deop(arena.owner);AgentCompanions.cycleFormation(arena.owner);
            c.assertValueEqual(AgentCompanions.formation(arena.owner),AgentCompanions.Formation.RING,"Permission loss cannot change helper controls");
        }c.succeed();
    }
}

package dev.convergence;

import java.nio.file.Files;
import net.minecraft.world.level.block.entity.SignTextSlot;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** One protected hub dimension with a central plaza and five physical mode lobbies. */
final class LobbyServer {
    record Lobby(String id, String label, GameModes.Mode mode, BlockPos center, Block floor, Block trim) {}
    static final Map<String, Lobby> LOBBIES = new LinkedHashMap<>();
    static {
        LOBBIES.put("main", new Lobby("main", "Main Hub", GameModes.Mode.HUB, new BlockPos(0, 81, 0), Blocks.POLISHED_DEEPSLATE, Blocks.QUARTZ_BLOCK));
        LOBBIES.put("survival", new Lobby("survival", "Survival Lobby", GameModes.Mode.SURVIVAL, new BlockPos(0, 81, -48), Blocks.MOSSY_STONE_BRICKS, Blocks.OAK_LOG));
        LOBBIES.put("creative", new Lobby("creative", "Creative Lobby", GameModes.Mode.CREATIVE, new BlockPos(48, 81, 0), Blocks.SMOOTH_QUARTZ, Blocks.AMETHYST_BLOCK));
        LOBBIES.put("hardcore", new Lobby("hardcore", "Hardcore Lobby", GameModes.Mode.HARDCORE, new BlockPos(0, 81, 48), Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.RED_NETHER_BRICKS));
        LOBBIES.put("minigames", new Lobby("minigames", "Minigames Lobby", GameModes.Mode.MINIGAMES, new BlockPos(-48, 81, 0), Blocks.CONCRETE.lightBlue(), Blocks.CONCRETE.yellow()));
        LOBBIES.put("adventure", new Lobby("adventure", "Adventure Lobby", GameModes.Mode.ADVENTURE, new BlockPos(48, 81, 48), Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS));
    }
    static final Map<BlockPos, String> MAIN_SIGNS = new HashMap<>();
    static final Map<BlockPos, String> ENTRY_SIGNS = new HashMap<>();
    static final Set<BlockPos> MENU_SIGNS = new LinkedHashSet<>();
    static {
        int x = -8;
        for (String id : LOBBIES.keySet()) if (!id.equals("main")) { MAIN_SIGNS.put(new BlockPos(x, 81, -5), id); x += 4; }
        for (var lobby : LOBBIES.values()) if (!lobby.id.equals("main")) ENTRY_SIGNS.put(lobby.center.offset(0, 0, -4), lobby.id);
        for (var lobby : LOBBIES.values()) MENU_SIGNS.add(lobby.center.offset(4, 0, -4));
    }
    record Palette(Block pillar, Block glass, Block light, Block banner, Block leaves, Block plant) {}
    record Decoration(Lobby lobby, BlockState state) {}
    static final Map<BlockPos, Decoration> DECORATIONS = decorations();
    private static final Map<ServerLevel, Map<ChunkPos, LevelChunk>> PENDING_DECOR = new IdentityHashMap<>();

    static Palette palette(String id) {
        return switch (id) {
            case "survival" -> new Palette(Blocks.SPRUCE_LOG, Blocks.STAINED_GLASS.lime(), Blocks.SHROOMLIGHT, Blocks.BANNER.lime(), Blocks.OAK_LEAVES, Blocks.POTTED_FERN);
            case "creative" -> new Palette(Blocks.QUARTZ_PILLAR, Blocks.STAINED_GLASS.purple(), Blocks.PEARLESCENT_FROGLIGHT, Blocks.BANNER.purple(), Blocks.FLOWERING_AZALEA_LEAVES, Blocks.POTTED_ALLIUM);
            case "hardcore" -> new Palette(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.STAINED_GLASS.red(), Blocks.SHROOMLIGHT, Blocks.BANNER.red(), Blocks.DARK_OAK_LEAVES, Blocks.POTTED_CRIMSON_FUNGUS);
            case "minigames" -> new Palette(Blocks.CONCRETE.yellow(), Blocks.STAINED_GLASS.lightBlue(), Blocks.SEA_LANTERN, Blocks.BANNER.lightBlue(), Blocks.AZALEA_LEAVES, Blocks.POTTED_DANDELION);
            case "adventure" -> new Palette(Blocks.CHISELED_STONE_BRICKS, Blocks.STAINED_GLASS.orange(), Blocks.OCHRE_FROGLIGHT, Blocks.BANNER.orange(), Blocks.JUNGLE_LEAVES, Blocks.POTTED_BAMBOO);
            default -> new Palette(Blocks.QUARTZ_PILLAR, Blocks.STAINED_GLASS.cyan(), Blocks.SEA_LANTERN, Blocks.BANNER.cyan(), Blocks.FLOWERING_AZALEA_LEAVES, Blocks.POTTED_AZURE_BLUET);
        };
    }

    /** Fixed vanilla accents: no entities, resource pack, terrain replacement, or generated coordinates. */
    static Map<BlockPos, Decoration> decorations() {
        var result = new LinkedHashMap<BlockPos, Decoration>();
        for (var lobby : LOBBIES.values()) {
            var colors = palette(lobby.id);
            // The north gateway frames the entry sign with a five-block-high clear opening.
            for (int side : new int[]{-1, 1}) {
                for (int y = 0; y <= 5; y++) accent(result, lobby, side * 5, y, -10, colors.pillar.defaultBlockState());
                accent(result, lobby, side * 5, 6, -10, colors.light.defaultBlockState());
                accent(result, lobby, side * 5, 7, -10, colors.banner.defaultBlockState().setValue(BannerBlock.ROTATION, 8));
            }
            for (int x = -4; x <= 4; x++) {
                accent(result, lobby, x, 5, -10, colors.pillar.defaultBlockState());
                accent(result, lobby, x, 6, -10, (Math.abs(x) == 4 ? colors.light : colors.glass).defaultBlockState());
            }
            // Side gardens leave all cross paths, the diagonal bridge, and sign approaches open.
            for (int x : new int[]{-9, 9}) for (int z : new int[]{-5, 5}) {
                accent(result, lobby, x, 0, z, lobby.trim.defaultBlockState());
                accent(result, lobby, x, 1, z, colors.plant.defaultBlockState());
                for (int offset : new int[]{-1, 1})
                    accent(result, lobby, x, 0, z + offset, colors.leaves.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
            }
            // Glass lanterns and flags turn the original corner columns into colored landmarks.
            for (int x : new int[]{-10, 10}) for (int z : new int[]{-10, 10}) {
                accent(result, lobby, x, 5, z, colors.glass.defaultBlockState());
                accent(result, lobby, x, 6, z, colors.light.defaultBlockState());
                accent(result, lobby, x, 7, z, colors.banner.defaultBlockState().setValue(BannerBlock.ROTATION, 8));
            }
            if (lobby.id.equals("main")) {
                // An elevated eight-point crown provides a focal point above the untouched arrival.
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                    if (Math.abs(x) != 3 && Math.abs(z) != 3) continue;
                    accent(result, lobby, x, 7, z, ((x + z) % 3 == 0 ? colors.light : colors.glass).defaultBlockState());
                    if ((Math.abs(x) == 3 && Math.abs(z) == 3) || x == 0 || z == 0)
                        accent(result, lobby, x, 8, z, colors.pillar.defaultBlockState());
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static void accent(Map<BlockPos, Decoration> result, Lobby lobby, int x, int y, int z, BlockState state) {
        if (Math.abs(x) > 11 || Math.abs(z) > 11 || y < 0 || y > 8)
            throw new IllegalArgumentException("Lobby decoration outside its fixed platform");
        // Keep three blocks of headroom, including the diagonal Main Hub–Adventure approach.
        boolean crossPath = Math.abs(x) <= 2 || Math.abs(z) <= 2;
        boolean diagonal = Math.abs(x - z) <= 4 && (lobby.id.equals("main") && x >= 0 && z >= 0
            || lobby.id.equals("adventure") && x <= 0 && z <= 0);
        if (y <= 3 && (crossPath || diagonal)) return;
        result.put(lobby.center.offset(x, y, z), new Decoration(lobby, state));
    }

    static boolean loadedSite(ServerLevel world, BlockPos pos) {
        return world != null && world.isInWorldBounds(pos) && world.getWorldBorder().isWithinBounds(pos)
            // isChunkLoaded can be true while CHUNK_LOAD is still completing its future.
            // This lookup returns only a completed chunk and never waits or requests one.
            && world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    /** Upgrade loaded original platforms only. Never replace player blocks or rebuild a saved hub. */
    static int installDecor(ServerLevel world) {
        return installDecor(world, null);
    }

    private static int installDecor(ServerLevel world, ChunkPos onlyChunk) {
        if (world == null || GameModes.of(world) != GameModes.Mode.HUB) return 0;
        int changed = 0;
        for (var entry : DECORATIONS.entrySet()) {
            var pos = entry.getKey();
            if (onlyChunk != null && (pos.getX() >> 4 != onlyChunk.x() || pos.getZ() >> 4 != onlyChunk.z())) continue;
            if (!loadedSite(world, pos) || !world.getBlockState(pos).isAir()) continue;
            var lobby = entry.getValue().lobby;
            var floor = new BlockPos(pos.getX(), lobby.center.getY() - 1, pos.getZ());
            if (!loadedSite(world, floor)) continue;
            var base = world.getBlockState(floor);
            if (!base.is(lobby.floor) && !base.is(Blocks.QUARTZ_BLOCK) && !base.is(Blocks.SEA_LANTERN)) continue;
            var state = entry.getValue().state;
            var colors = palette(lobby.id);
            if (state.is(colors.plant) || state.is(colors.banner)) {
                var support = pos.below();
                // Use the actual block's support rule: sea lanterns support banners despite
                // their false opaque/solidBlock predicate (which is not placement support).
                if (!loadedSite(world, support) || !state.canSurvive(world, pos)) continue;
            }
            // Skip neighbor callbacks and shape updates; inert accents must not touch adjacent chunks.
            if (world.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) changed++;
        }
        for (var entry : MAIN_SIGNS.entrySet()) if (inChunk(entry.getKey(), onlyChunk)) changed += refreshSign(world, entry.getKey(), entry.getValue(), false);
        for (var entry : ENTRY_SIGNS.entrySet()) if (inChunk(entry.getKey(), onlyChunk)) changed += refreshSign(world, entry.getKey(), entry.getValue(), true);
        for (var pos : MENU_SIGNS) if (inChunk(pos, onlyChunk)) changed += installMenuSign(world, pos);
        return changed;
    }

    private static boolean inChunk(BlockPos pos, ChunkPos chunk) {
        return chunk == null || (pos.getX() >> 4 == chunk.x() && pos.getZ() >> 4 == chunk.z());
    }

    private static void tickDecor(ServerLevel world) {
        var pending = PENDING_DECOR.get(world);
        if (pending == null) return;
        // Process a snapshot: any chunks loaded by ordinary block/sign notifications
        // enqueue work for a later tick instead of recursively installing decorations.
        for (var entry : new LinkedHashMap<>(pending).entrySet()) {
            var pos = entry.getKey();
            var ready = world.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (ready == null) continue;
            if (!pending.remove(pos, entry.getValue())) continue;
            // Do not apply an old load notification to a replacement chunk instance.
            if (ready == entry.getValue()) installDecor(world, pos);
        }
        if (pending.isEmpty()) PENDING_DECOR.remove(world, pending);
    }
    static CommunityServer.Place place(MinecraftServer server, String id) {
        var world = GameModes.world(server, GameModes.Mode.HUB);
        var lobby = LOBBIES.get(id);
        if (world == null || lobby == null) throw new IllegalStateException("Hub lobby is missing: " + id);
        return new CommunityServer.Place(world.dimension().identifier().toString(), lobby.center.getX() + .5,
            lobby.center.getY(), lobby.center.getZ() + .5, 180, 0);
    }
    static void build(MinecraftServer server) {
        var world = GameModes.world(server, GameModes.Mode.HUB);
        if (world == null) throw new IllegalStateException("Infinity hub dimension is missing. Check the mod data pack and restart.");
        var marker = server.getWorldPath(LevelResource.ROOT).resolve("infinity-built-in-lobbies.json");
        if (Files.exists(marker)) { installDecor(world); return; }
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        for (var lobby : LOBBIES.values()) {
            var center = lobby.center;
            for (int dx=-12; dx<=12; dx++) for (int dz=-12; dz<=12; dz++) {
                var floor = center.offset(dx,-1,dz);
                boolean path = Math.abs(dx)<=2 || Math.abs(dz)<=2;
                blocks.put(floor, (dx%6==0 && dz%6==0 ? Blocks.SEA_LANTERN : path ? Blocks.QUARTZ_BLOCK : lobby.floor).defaultBlockState());
                boolean edge = Math.abs(dx)==12 || Math.abs(dz)==12;
                boolean gate = Math.abs(dx)<=2 || Math.abs(dz)<=2;
                if (edge && !gate) blocks.put(center.offset(dx,0,dz), lobby.trim.defaultBlockState());
            }
            for (int dx : new int[]{-10,10}) for (int dz : new int[]{-10,10}) {
                for (int dy=0;dy<=3;dy++) blocks.put(center.offset(dx,dy,dz), lobby.trim.defaultBlockState());
                blocks.put(center.offset(dx,4,dz), Blocks.SEA_LANTERN.defaultBlockState());
            }
        }
        var main = LOBBIES.get("main").center;
        for (var lobby : LOBBIES.values()) if (!lobby.id.equals("main")) {
            var end = lobby.center;
            for(int step=0; step<=48;step++) {
                int x=Math.round(end.getX()*step/48f), z=Math.round(end.getZ()*step/48f);
                for(int side=-2;side<=2;side++) {
                    int offsetX=end.getZ()!=0?side:0;
                    int offsetZ=end.getX()!=0 && end.getZ()!=0?-side:end.getX()!=0?side:0;
                    var at=new BlockPos(x+offsetX,80,z+offsetZ);
                    blocks.put(at,(step%8==0 && side==0 ? Blocks.SEA_LANTERN : Blocks.QUARTZ_BLOCK).defaultBlockState());
                }
            }
        }
        for (var pos : MAIN_SIGNS.keySet()) blocks.put(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8));
        for (var pos : ENTRY_SIGNS.keySet()) blocks.put(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8));
        for (var pos : blocks.keySet()) {
            if (!world.isInWorldBounds(pos) || !world.getWorldBorder().isWithinBounds(pos) || !world.getBlockState(pos).isAir())
                throw new IllegalStateException("Hub build would replace existing blocks at " + pos + ". Original data was not changed; restore a matching backup or move the lobby area.");
        }
        for (var entry : blocks.entrySet()) world.setBlock(entry.getKey(),entry.getValue(),3);
        for (var entry : MAIN_SIGNS.entrySet()) sign(world,entry.getKey(),entry.getValue(),false);
        for (var entry : ENTRY_SIGNS.entrySet()) sign(world,entry.getKey(),entry.getValue(),true);
        try {CommunityServer.atomicJson(marker,Map.of("version",1,"lobbies",LOBBIES.keySet()));}
        catch(java.io.IOException e){throw new IllegalStateException("Cannot save lobby generation marker",e);}
        installDecor(world);
    }
    static void sign(ServerLevel world, BlockPos pos, String id, boolean entry) {
        if (!(world.getBlockEntity(pos) instanceof SignBlockEntity sign)) throw new IllegalStateException("Lobby sign did not load at " + pos);
        applySignText(sign, lobbyLines(id, entry));
    }

    private static String[] lobbyLines(String id, boolean entry) {
        String detail = switch (id) {
            case "survival" -> "SURVIVE + CRAFT";
            case "creative" -> "BUILD + CREATE";
            case "hardcore" -> "ONE LIFE";
            case "minigames" -> "CHOOSE A GAME";
            case "adventure" -> "CHOOSE A MAP";
            default -> "WELCOME";
        };
        return new String[]{LOBBIES.get(id).label.replace(" Lobby", ""), entry ? "ENTER WORLD" : "VISIT LOBBY", detail, "TAP TO OPEN"};
    }

    private static boolean matches(SignText text, String[] lines) {
        for (int i = 0; i < 4; i++) if (!text.getMessages(false).get(i).getString().equals(lines[i])) return false;
        return true;
    }

    private static int applySignText(SignBlockEntity sign, String[] lines) {
        if (matches(sign.getText(SignTextSlot.FRONT), lines) && matches(sign.getText(SignTextSlot.BACK), lines)
            && sign.getText(SignTextSlot.FRONT).hasGlowingText() && sign.getText(SignTextSlot.BACK).hasGlowingText()
            && sign.getText(SignTextSlot.FRONT).getColor() == DyeColor.WHITE && sign.getText(SignTextSlot.BACK).getColor() == DyeColor.WHITE) return 0;
        var text = SignText.EMPTY.asMutable().setTextGlowing(true).setColor(DyeColor.WHITE);
        for (int i = 0; i < 4; i++) text.setLine(i, Component.literal(lines[i]));
        sign.setText(text.asImmutable(), SignTextSlot.FRONT);
        sign.setText(text.asImmutable(), SignTextSlot.BACK);
        return 1;
    }

    private static int refreshSign(ServerLevel world, BlockPos pos, String id, boolean entry) {
        if (!loadedSite(world, pos) || !world.getBlockState(pos).is(Blocks.OAK_SIGN)
            || !(world.getBlockEntity(pos) instanceof SignBlockEntity sign)) return 0;
        var old = new String[]{LOBBIES.get(id).label.replace(" Lobby", ""), entry ? "ENTER WORLD" : "VISIT LOBBY", entry ? "/play " + id : "/lobby " + id, "RIGHT CLICK"};
        var current = lobbyLines(id, entry);
        if ((!matches(sign.getText(SignTextSlot.FRONT), old) && !matches(sign.getText(SignTextSlot.FRONT), current))
            || (!matches(sign.getText(SignTextSlot.BACK), new String[]{"", "", "", ""}) && !matches(sign.getText(SignTextSlot.BACK), old) && !matches(sign.getText(SignTextSlot.BACK), current))) return 0;
        return applySignText(sign, current);
    }

    static boolean isMenuSign(ServerLevel world, BlockPos pos) {
        return world.getBlockEntity(pos) instanceof SignBlockEntity sign
            && sign.getText(SignTextSlot.FRONT).getMessages(false).get(0).getString().equals("INFINITY MENU");
    }

    /** Add a menu recovery point without rebuilding lobbies or replacing a player's blocks. */
    static void installMenuSigns(MinecraftServer server) {
        var world = GameModes.world(server, GameModes.Mode.HUB);
        for (var pos : MENU_SIGNS) installMenuSign(world, pos);
    }

    private static int installMenuSign(ServerLevel world, BlockPos pos) {
        if (!loadedSite(world, pos) || !loadedSite(world, pos.below())) return 0;
        var lines = new String[]{"INFINITY MENU", "GEAR + POWERS", "AI HELPERS", "TAP TO OPEN"};
        if (world.getBlockEntity(pos) instanceof SignBlockEntity existing) {
            if (matches(existing.getText(SignTextSlot.FRONT), lines) && matches(existing.getText(SignTextSlot.BACK), lines)) return applySignText(existing, lines);
            return 0;
        }
        if (!CourseSelector.canPlaceSign(world, pos)) return 0;
        world.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        if (!(world.getBlockEntity(pos) instanceof SignBlockEntity sign)) return 0;
        applySignText(sign, lines);
        return 1;
    }
    static int arrive(ServerPlayer p, String id) {
        if (!LOBBIES.containsKey(id)) return CommunityServer.say(p,"Unknown lobby. /lobbies lists the available areas.");
        if (GameModes.current(p) != GameModes.Mode.HUB) return CommunityServer.say(p,"Use /lobby " + id + " first.");
        var target=place(p.level().getServer(),id);
        if (!Memberships.operator(p)&&!CommunityServer.safe(p.level(),target)) return CommunityServer.say(p,"That lobby platform is blocked. Ask the host to inspect it.");
        if (!p.teleportTo(p.level(),target.x(),target.y(),target.z(),Set.of(),target.yaw(),target.pitch(),true)) return CommunityServer.say(p,"Could not reach that lobby.");
        p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;GameModes.state(p).putString("last_lobby",id);
        var label=LOBBIES.get(id).label;
        return CommunityServer.say(p,"Welcome to " + label + ". " + (id.equals("main")?"/lobbies lists the five mode lobbies.":"Use /play " + id + " to enter, or /hub to return to Main Hub.")+" /guide shows what to do here.");
    }
    static int menu(ServerPlayer p) {
        CommunityServer.say(p,"Main Hub: /hub. Mode lobbies: /lobby survival, creative, hardcore, minigames, adventure. /play <mode> enters its world.");
        for (var lobby : LOBBIES.values()) if (!lobby.id.equals("main")) p.sendSystemMessage(
            Component.literal("[ " + lobby.label + " ] /lobby " + lobby.id).withStyle(ChatFormatting.AQUA)
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand("/lobby " + lobby.id))));
        return 1;
    }
    static int request(ServerPlayer p, String id) {
        if (!LOBBIES.containsKey(id)) return CommunityServer.say(p,"Unknown lobby. Use /lobbies for the list.");
        return GameModes.request(p,GameModes.Mode.HUB,id);
    }
    static InteractionResult useSign(ServerPlayer p, BlockPos pos) {
        if (GameModes.current(p)!=GameModes.Mode.HUB || (p.isSpectator()&&!Memberships.operator(p))) return InteractionResult.PASS;
        if (MENU_SIGNS.contains(pos) && isMenuSign(p.level(), pos)) {
            ServerMenu.ensureNavigator(p);
            ServerMenu.open(p);
            return InteractionResult.SUCCESS;
        }
        var hall=MAIN_SIGNS.get(pos);
        if (hall!=null) {arrive(p,hall);return InteractionResult.SUCCESS;}
        hall=ENTRY_SIGNS.get(pos);
        if (hall!=null) {
            var mode=LOBBIES.get(hall).mode;
            if (CourseSelector.supports(mode)) CourseSelector.open(p,mode);
            else GameModes.request(p,mode,null);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
    static void tick(ServerPlayer p) {
        if (Memberships.operator(p)||GameModes.current(p)!=GameModes.Mode.HUB) return;
        if (p.getY()<74) arrive(p,GameModes.state(p).getStringOr("last_lobby","main"));
    }
    static void register() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk, generated) -> {
            if (GameModes.of(world) != GameModes.Mode.HUB) return;
            // Existing saves load their hub lazily. Upgrade only our fixed platform chunks.
            boolean known = DECORATIONS.keySet().stream().anyMatch(pos -> inChunk(pos, chunk.getPos()));
            if (known && Files.exists(world.getServer().getWorldPath(LevelResource.ROOT).resolve("infinity-built-in-lobbies.json")))
                // Never read/write world blocks here: the chunk's FULL future may still
                // be completing, and even native sign setters can wait for that future.
                PENDING_DECOR.computeIfAbsent(world, key -> new LinkedHashMap<>()).put(chunk.getPos(), chunk);
        });
        ServerTickEvents.END_LEVEL_TICK.register(LobbyServer::tickDecor);
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            var pending = PENDING_DECOR.get(world);
            if (pending == null) return;
            pending.remove(chunk.getPos(), chunk);
            if (pending.isEmpty()) PENDING_DECOR.remove(world, pending);
        });
        ServerLevelEvents.UNLOAD.register((server, world) -> PENDING_DECOR.remove(world));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING_DECOR.keySet().removeIf(world -> world.getServer() == server));
        UseBlockCallback.EVENT.register((player,world,hand,hit)-> {
            if (!(player instanceof ServerPlayer p) || world.isClientSide() || hand!=InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            return useSign(p,hit.getBlockPos());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            dispatcher.register(Commands.literal("hub").executes(ctx->request(ctx.getSource().getPlayerOrException(),"main")));
            dispatcher.register(Commands.literal("lobbies").executes(ctx->menu(ctx.getSource().getPlayerOrException())));
            var lobby=Commands.literal("lobby").executes(ctx->request(ctx.getSource().getPlayerOrException(),"main"));
            for(var id:LOBBIES.keySet()) if(!id.equals("main")) lobby.then(Commands.literal(id).executes(ctx->request(ctx.getSource().getPlayerOrException(),id)));
            dispatcher.register(lobby);
        });
    }
}

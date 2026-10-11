package dev.convergence;

import java.nio.file.Files;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** One protected hub dimension with a central plaza and five physical mode lobbies. */
final class LobbyServer {
    record Lobby(String id, String label, GameModes.Mode mode, BlockPos center, Block floor, Block trim) {}
    static final Map<String, Lobby> LOBBIES = new LinkedHashMap<>();
    static {
        LOBBIES.put("main", new Lobby("main", "Main Hub", GameModes.Mode.HUB, new BlockPos(0, 81, 0), Blocks.POLISHED_DEEPSLATE, Blocks.QUARTZ_BLOCK));
        LOBBIES.put("survival", new Lobby("survival", "Survival Lobby", GameModes.Mode.SURVIVAL, new BlockPos(0, 81, -48), Blocks.MOSSY_STONE_BRICKS, Blocks.OAK_LOG));
        LOBBIES.put("creative", new Lobby("creative", "Creative Lobby", GameModes.Mode.CREATIVE, new BlockPos(48, 81, 0), Blocks.SMOOTH_QUARTZ, Blocks.AMETHYST_BLOCK));
        LOBBIES.put("hardcore", new Lobby("hardcore", "Hardcore Lobby", GameModes.Mode.HARDCORE, new BlockPos(0, 81, 48), Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.RED_NETHER_BRICKS));
        LOBBIES.put("minigames", new Lobby("minigames", "Minigames Lobby", GameModes.Mode.MINIGAMES, new BlockPos(-48, 81, 0), Blocks.LIGHT_BLUE_CONCRETE, Blocks.YELLOW_CONCRETE));
        LOBBIES.put("adventure", new Lobby("adventure", "Adventure Lobby", GameModes.Mode.ADVENTURE, new BlockPos(48, 81, 48), Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS));
    }
    static final Map<BlockPos, String> MAIN_SIGNS = new HashMap<>();
    static final Map<BlockPos, String> ENTRY_SIGNS = new HashMap<>();
    static {
        int x = -8;
        for (String id : LOBBIES.keySet()) if (!id.equals("main")) { MAIN_SIGNS.put(new BlockPos(x, 81, -5), id); x += 4; }
        for (var lobby : LOBBIES.values()) if (!lobby.id.equals("main")) ENTRY_SIGNS.put(lobby.center.add(0, 0, -4), lobby.id);
    }
    static CommunityServer.Place place(MinecraftServer server, String id) {
        var world = GameModes.world(server, GameModes.Mode.HUB);
        var lobby = LOBBIES.get(id);
        if (world == null || lobby == null) throw new IllegalStateException("Hub lobby is missing: " + id);
        return new CommunityServer.Place(world.getRegistryKey().getValue().toString(), lobby.center.getX() + .5,
            lobby.center.getY(), lobby.center.getZ() + .5, 180, 0);
    }
    static void build(MinecraftServer server) {
        var world = GameModes.world(server, GameModes.Mode.HUB);
        if (world == null) throw new IllegalStateException("Infinity hub dimension is missing. Check the mod data pack and restart.");
        var marker = server.getSavePath(WorldSavePath.ROOT).resolve("infinity-built-in-lobbies.json");
        if (Files.exists(marker)) return;
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        for (var lobby : LOBBIES.values()) {
            var center = lobby.center;
            for (int dx=-12; dx<=12; dx++) for (int dz=-12; dz<=12; dz++) {
                var floor = center.add(dx,-1,dz);
                boolean path = Math.abs(dx)<=2 || Math.abs(dz)<=2;
                blocks.put(floor, (dx%6==0 && dz%6==0 ? Blocks.SEA_LANTERN : path ? Blocks.QUARTZ_BLOCK : lobby.floor).getDefaultState());
                boolean edge = Math.abs(dx)==12 || Math.abs(dz)==12;
                boolean gate = Math.abs(dx)<=2 || Math.abs(dz)<=2;
                if (edge && !gate) blocks.put(center.add(dx,0,dz), lobby.trim.getDefaultState());
            }
            for (int dx : new int[]{-10,10}) for (int dz : new int[]{-10,10}) {
                for (int dy=0;dy<=3;dy++) blocks.put(center.add(dx,dy,dz), lobby.trim.getDefaultState());
                blocks.put(center.add(dx,4,dz), Blocks.SEA_LANTERN.getDefaultState());
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
                    blocks.put(at,(step%8==0 && side==0 ? Blocks.SEA_LANTERN : Blocks.QUARTZ_BLOCK).getDefaultState());
                }
            }
        }
        for (var pos : MAIN_SIGNS.keySet()) blocks.put(pos, Blocks.OAK_SIGN.getDefaultState().with(SignBlock.ROTATION, 8));
        for (var pos : ENTRY_SIGNS.keySet()) blocks.put(pos, Blocks.OAK_SIGN.getDefaultState().with(SignBlock.ROTATION, 8));
        for (var pos : blocks.keySet()) {
            if (!world.isInBuildLimit(pos) || !world.getWorldBorder().contains(pos) || !world.getBlockState(pos).isAir())
                throw new IllegalStateException("Hub build would replace existing blocks at " + pos + ". Original data was not changed; restore a matching backup or move the lobby area.");
        }
        for (var entry : blocks.entrySet()) world.setBlockState(entry.getKey(),entry.getValue(),3);
        for (var entry : MAIN_SIGNS.entrySet()) sign(world,entry.getKey(),entry.getValue(),false);
        for (var entry : ENTRY_SIGNS.entrySet()) sign(world,entry.getKey(),entry.getValue(),true);
        try {CommunityServer.atomicJson(marker,Map.of("version",1,"lobbies",LOBBIES.keySet()));}
        catch(java.io.IOException e){throw new IllegalStateException("Cannot save lobby generation marker",e);}
    }
    static void sign(ServerWorld world, BlockPos pos, String id, boolean entry) {
        if (!(world.getBlockEntity(pos) instanceof SignBlockEntity sign)) throw new IllegalStateException("Lobby sign did not load at " + pos);
        var lobby=LOBBIES.get(id);
        var text = sign.getFrontText().withMessage(0,Text.literal(lobby.label.replace(" Lobby","")))
            .withMessage(1,Text.literal(entry?"ENTER WORLD":"VISIT LOBBY"))
            .withMessage(2,Text.literal(entry?"/play "+id:"/lobby "+id))
            .withMessage(3,Text.literal("RIGHT CLICK"));
        sign.setText(text,true);
    }
    static int arrive(ServerPlayerEntity p, String id) {
        if (!LOBBIES.containsKey(id)) return CommunityServer.say(p,"Unknown lobby. /lobbies lists the available areas.");
        if (GameModes.current(p) != GameModes.Mode.HUB) return CommunityServer.say(p,"Use /lobby " + id + " first.");
        var target=place(p.getEntityWorld().getServer(),id);
        if (!Memberships.operator(p)&&!CommunityServer.safe(p.getEntityWorld(),target)) return CommunityServer.say(p,"That lobby platform is blocked. Ask the host to inspect it.");
        if (!p.teleport(p.getEntityWorld(),target.x(),target.y(),target.z(),Set.of(),target.yaw(),target.pitch(),true)) return CommunityServer.say(p,"Could not reach that lobby.");
        p.setVelocity(Vec3d.ZERO);p.fallDistance=0;GameModes.state(p).putString("last_lobby",id);
        var label=LOBBIES.get(id).label;
        return CommunityServer.say(p,"Welcome to " + label + ". " + (id.equals("main")?"/lobbies lists the five mode lobbies.":"Use /play " + id + " to enter, or /hub to return to Main Hub.")+" /guide shows what to do here.");
    }
    static int menu(ServerPlayerEntity p) {
        CommunityServer.say(p,"Main Hub: /hub. Mode lobbies: /lobby survival, creative, hardcore, minigames, adventure. /play <mode> enters its world.");
        for (var lobby : LOBBIES.values()) if (!lobby.id.equals("main")) p.sendMessage(
            Text.literal("[ " + lobby.label + " ] /lobby " + lobby.id).formatted(Formatting.AQUA)
                .styled(style -> style.withClickEvent(new ClickEvent.RunCommand("/lobby " + lobby.id))),false);
        return 1;
    }
    static int request(ServerPlayerEntity p, String id) {
        if (!LOBBIES.containsKey(id)) return CommunityServer.say(p,"Unknown lobby. Use /lobbies for the list.");
        return GameModes.request(p,GameModes.Mode.HUB,id);
    }
    static ActionResult useSign(ServerPlayerEntity p, BlockPos pos) {
        if (GameModes.current(p)!=GameModes.Mode.HUB || (p.isSpectator()&&!Memberships.operator(p))) return ActionResult.PASS;
        var hall=MAIN_SIGNS.get(pos);
        if (hall!=null) {arrive(p,hall);return ActionResult.SUCCESS;}
        hall=ENTRY_SIGNS.get(pos);
        if (hall!=null) {
            var mode=LOBBIES.get(hall).mode;
            if (CourseSelector.supports(mode)) CourseSelector.open(p,mode);
            else GameModes.request(p,mode,null);
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }
    static void tick(ServerPlayerEntity p) {
        if (Memberships.operator(p)||GameModes.current(p)!=GameModes.Mode.HUB) return;
        if (p.getY()<74) arrive(p,GameModes.state(p).getString("last_lobby","main"));
    }
    static void register() {
        UseBlockCallback.EVENT.register((player,world,hand,hit)-> {
            if (!(player instanceof ServerPlayerEntity p) || world.isClient() || hand!=Hand.MAIN_HAND) return ActionResult.PASS;
            return useSign(p,hit.getBlockPos());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            dispatcher.register(CommandManager.literal("hub").executes(ctx->request(ctx.getSource().getPlayerOrThrow(),"main")));
            dispatcher.register(CommandManager.literal("lobbies").executes(ctx->menu(ctx.getSource().getPlayerOrThrow())));
            var lobby=CommandManager.literal("lobby").executes(ctx->request(ctx.getSource().getPlayerOrThrow(),"main"));
            for(var id:LOBBIES.keySet()) if(!id.equals("main")) lobby.then(CommandManager.literal(id).executes(ctx->request(ctx.getSource().getPlayerOrThrow(),id)));
            dispatcher.register(lobby);
        });
    }
}

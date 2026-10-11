package dev.convergence;

import com.mojang.serialization.MapCodec;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Native world generation: no chunk-load searches, retroactive placement or per-tick terrain scans. */
public final class OdysseyStructure extends Structure {
    public static final MapCodec<OdysseyStructure> CODEC=simpleCodec(OdysseyStructure::new);
    static final StructureType<OdysseyStructure> TYPE=()->CODEC;
    static final StructurePieceType PIECE=(StructurePieceType.StructureTemplateType)Piece::new;
    static final int HEIGHT=7;
    static final ResourceKey<Structure> KEY=ResourceKey.create(Registries.STRUCTURE,Identifier.parse("convergence:odyssey_ruin"));
    static final String HOST_CONFIG="convergence-odyssey.json";
    private static final Pattern TRUSTABLE=Pattern.compile("/ban [A-Za-z0-9_]{3,16}");
    /** Empty unless this host opts in; a public install never raises a generated repeater's permission. */
    private static volatile String trustedCommand="";
    public OdysseyStructure(StructureSettings settings) { super(settings); }

    static void register() {
        Registry.register(BuiltInRegistries.STRUCTURE_TYPE,Identifier.parse("convergence:odyssey_ruin"),TYPE);
        Registry.register(BuiltInRegistries.STRUCTURE_PIECE,Identifier.parse("convergence:odyssey_ruin"),PIECE);
        ServerLifecycleEvents.SERVER_STARTING.register(server->trust(hostCommand(FabricLoader.getInstance().getConfigDir().resolve(HOST_CONFIG))));
    }

    /** Reads {"trusted_ruin_command": "/ban <name>"}; anything else, or no file, trusts nothing. */
    static String hostCommand(Path file) {
        if(!Files.isRegularFile(file))return "";
        try {
            var value=JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("trusted_ruin_command");
            String command=value==null||!value.isJsonPrimitive()?"":value.getAsString();
            if(TRUSTABLE.matcher(command).matches())return command;
            System.err.println("[Infinity Odyssey] "+file.getFileName()+" ignored: trusted_ruin_command must be exactly /ban <player name>.");
        } catch(java.io.IOException|RuntimeException error) {
            System.err.println("[Infinity Odyssey] "+file.getFileName()+" ignored: "+error.getMessage());
        }
        return "";
    }
    static void trust(String command) {trustedCommand=command;}

    static String theme(String biome) {
        String b=biome.substring(biome.indexOf(':')+1);
        if(b.equals("crimson_forest"))return "crimson";
        if(b.equals("warped_forest"))return "warped";
        if(b.equals("soul_sand_valley"))return "soul";
        if(b.equals("basalt_deltas"))return "basalt";
        if(b.equals("nether_wastes"))return "nether_wastes";
        if(b.contains("end")||b.equals("small_end_islands")||b.equals("the_void"))return "end";
        if(b.contains("ocean"))return "ocean";
        if(b.contains("snow")||b.contains("frozen")||b.contains("ice")||b.equals("grove"))return "snow";
        if(b.contains("desert")||b.equals("beach"))return "desert";
        if(b.contains("badlands"))return "badlands";
        if(b.contains("jungle"))return "jungle";
        if(b.contains("swamp"))return "swamp";
        if(b.contains("mushroom"))return "mushroom";
        if(b.contains("cherry"))return "cherry";
        if(b.contains("pale_garden"))return "pale_garden";
        if(b.contains("dark_forest"))return "dark_forest";
        if(b.contains("savanna"))return "savanna";
        if(b.contains("cave")||b.equals("deep_dark"))return "caves";
        if(b.contains("peak")||b.contains("hill")||b.equals("stony_shore"))return "mountain";
        if(b.contains("forest")||b.contains("taiga"))return "forest";
        return "plains";
    }

    /** Search inside the Nether rather than placing a surface structure above its bedrock roof. */
    static int netherFloor(NoiseColumn column,int min,int max) {
        for(int y=max-HEIGHT;y>=min;y--) {
            var floor=column.getBlock(y-1);
            if(floor.isAir()||!floor.getFluidState().isEmpty()||floor.is(Blocks.BEDROCK))continue;
            boolean clear=true;
            for(int dy=0;dy<HEIGHT;dy++) if(!column.getBlock(y+dy).isAir()){clear=false;break;}
            if(clear)return y;
        }
        return Integer.MIN_VALUE;
    }

    @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext c) {
        int x=c.chunkPos().getMinBlockX()+8,z=c.chunkPos().getMinBlockZ()+8;
        var sample=c.biomeResolver().getNoiseBiome(x>>2,16,z>>2);
        String biome=sample.unwrapKey().orElseThrow().identifier().toString();
        String theme=theme(biome);
        boolean nether=theme.equals("crimson")||theme.equals("warped")||theme.equals("soul")||theme.equals("basalt")||theme.equals("nether_wastes");
        int y=nether?netherFloor(c.chunkGenerator().getBaseColumn(x,z,c.heightAccessor(),c.randomState()),32,112):
            c.chunkGenerator().getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,c.heightAccessor(),c.randomState());
        if(!nether&&!theme.equals("end")&&c.random().nextBoolean()) {
            var column=c.chunkGenerator().getBaseColumn(x,z,c.heightAccessor(),c.randomState());
            for(int ceiling=Math.min(48,y-8);ceiling>c.heightAccessor().getMinY()+HEIGHT+2;) {
                int cave=netherFloor(column,c.heightAccessor().getMinY()+2,ceiling);
                if(cave==Integer.MIN_VALUE)break;
                var caveBiome=c.biomeResolver().getNoiseBiome(x>>2,cave>>2,z>>2);
                if(theme(caveBiome.unwrapKey().orElseThrow().identifier().toString()).equals("caves")){y=cave;break;}
                ceiling=cave-1;
            }
        }
        if(y==Integer.MIN_VALUE||y<=c.heightAccessor().getMinY()+1||y+HEIGHT>=c.heightAccessor().getMaxY())return Optional.empty();
        // End void columns have no solid island beneath the candidate.
        if(c.chunkGenerator().getBaseColumn(x,z,c.heightAccessor(),c.randomState()).getBlock(y-1).isAir())return Optional.empty();
        var at=new BlockPos(x,y,z);
        var actual=c.biomeResolver().getNoiseBiome(x>>2,y>>2,z>>2);
        String actualTheme=theme(actual.unwrapKey().orElseThrow().identifier().toString());
        Rotation rotation=Rotation.getRandom(c.random());
        return Optional.of(new GenerationStub(at,builder->builder.addPiece(new Piece(c.structureTemplateManager(),at,actualTheme,rotation))));
    }
    @Override public StructureType<?> type() { return TYPE; }

    /** Vanilla blocks are OP2; permit only the host-trusted exact ban in a generated ruin's original repeater. */
    public static CommandSourceStack repeatingSource(BaseCommandBlock command,CommandSourceStack source) {
        String trusted=trustedCommand;
        if(trusted.isEmpty()||!command.getCommand().equals(trusted)||source.getEntity()!=null)return source;
        var level=source.getLevel();var pos=BlockPos.containing(source.getPosition());
        if(!level.getBlockState(pos).is(Blocks.REPEATING_COMMAND_BLOCK)
            ||!(level.getBlockEntity(pos) instanceof CommandBlockEntity block)||block.getCommandBlock()!=command)return source;
        var ruin=level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(KEY).value();
        var start=level.structureManager().getStructureAt(pos,ruin);
        if(start.isValid()&&start.getPieces().stream().anyMatch(piece->piece instanceof Piece p&&p.repeaterPosition().equals(pos)))
            return source.withPermission(LevelBasedPermissionSet.ADMIN);
        return source;
    }

    static final class Piece extends TemplateStructurePiece {
        Piece(StructureTemplateManager manager,BlockPos at,String theme,Rotation rotation) {
            super(PIECE,0,manager,Identifier.parse("convergence:odyssey/"+theme),"convergence:odyssey/"+theme,settings(rotation),at);
        }
        Piece(StructureTemplateManager manager,CompoundTag nbt) {
            super(PIECE,nbt,manager,id->settings(Rotation.valueOf(nbt.getStringOr("OdysseyRotation","NONE"))));
        }
        private static StructurePlaceSettings settings(Rotation rotation) {
            return new StructurePlaceSettings().setRotation(rotation).setIgnoreEntities(true);
        }
        BlockPos repeaterPosition() {
            return templatePosition.offset(StructureTemplate.calculateRelativePosition(placeSettings,new BlockPos(4,1,4)));
        }
        @Override public void postProcess(WorldGenLevel world,net.minecraft.world.level.StructureManager structures,ChunkGenerator generator,
                                          RandomSource random,BoundingBox bounds,ChunkPos chunk,BlockPos pivot) {
            super.postProcess(world,structures,generator,random,bounds,chunk,pivot);
            var repeater=repeaterPosition();
            // Proto-chunk block entities have no Level during NBT loading, so auto=true cannot schedule itself.
            // Persist the first native block tick during generation; native scheduling takes over afterwards.
            if(bounds.isInside(repeater)&&world.getBlockState(repeater).is(Blocks.REPEATING_COMMAND_BLOCK))
                world.scheduleTick(repeater,Blocks.REPEATING_COMMAND_BLOCK,1);
        }
        @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context,CompoundTag tag) {
            super.addAdditionalSaveData(context,tag);tag.putString("OdysseyRotation",getRotation().name());
        }
        @Override protected void handleDataMarker(String marker,BlockPos pos,ServerLevelAccessor world,RandomSource random,BoundingBox bounds) {}
    }
}

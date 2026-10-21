package dev.convergence;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;

/** Completed native chunks alone may award discovery; a FULL ticket is not a readiness check. */
public class OdysseyJournalChunkGameTests {
    /** Temporarily suspend the real manager's FULL generation future, rather than spoofing its lookup. */
    private static final class PendingFull implements AutoCloseable {
        final ServerChunkCache source;
        final AtomicReferenceArray<Object> futures;
        final int index=ChunkStatus.FULL.getIndex();
        final Object previous;
        final CompletableFuture<?> incomplete=new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        PendingFull(GameTestHelper c,ServerChunkCache source,LevelChunk chunk) throws ReflectiveOperationException {
            this.source=source;
            var visible=ServerChunkCache.class.getDeclaredMethod("getVisibleChunkIfPresent",long.class);visible.setAccessible(true);
            var holder=(ChunkHolder)visible.invoke(source,chunk.getPos().pack());
            c.assertTrue(holder!=null&&holder.getChunkIfPresent(ChunkStatus.FULL)==chunk,"The fixture has a real completed visible native holder");
            var field=GenerationChunkHolder.class.getDeclaredField("futures");field.setAccessible(true);
            futures=(AtomicReferenceArray<Object>)field.get(holder);previous=futures.get(index);
            c.assertTrue(previous instanceof CompletableFuture<?> future&&future.isDone(),"The original native FULL future is complete");
            futures.set(index,incomplete);
            try {clear(source);} catch(ReflectiveOperationException failure){futures.set(index,previous);throw failure;}
        }
        static void clear(ServerChunkCache source) throws ReflectiveOperationException {
            var clear=ServerChunkCache.class.getDeclaredMethod("clearCache");clear.setAccessible(true);clear.invoke(source);
        }
        @Override public void close() throws ReflectiveOperationException {
            if(!futures.compareAndSet(index,incomplete,previous))throw new IllegalStateException("Native FULL fixture changed during synchronous observation");
            clear(source);
        }
    }

    /** GameTestServer disables automatic structures, so use the registered native generation API. */
    private static final class GeneratedRuin implements AutoCloseable {
        final Structure ruin;
        final StructureStart start;
        final OdysseyStructure.Piece piece;
        final LevelChunk origin,local;
        final BlockPos inside;
        final StructureStart oldOrigin,oldLocal;
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet oldReferences;
        GeneratedRuin(GameTestHelper c) {
            var world=c.getLevel();var registry=world.registryAccess();
            var holder=registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(OdysseyStructure.KEY);ruin=holder.value();
            var generator=world.getChunkSource().getGenerator();var random=world.getChunkSource().randomState();
            var sampler=random.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED);
            var placement=(net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)registry.lookupOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE_SET,net.minecraft.resources.Identifier.parse("convergence:odyssey_ruins"))).value().placement();
            StructureStart chosenStart=null;OdysseyStructure.Piece chosenPiece=null;BlockPos crossing=null;ChunkPos at=null;
            // Some native rotations keep the whole template inside its origin chunk.
            // Select a genuine generated cross-boundary piece without moving or fabricating it.
            for(int attempt=0;attempt<16&&crossing==null;attempt++){
                var candidate=placement.getPotentialStructureChunk(world.getSeed(),20_000+40*attempt,20_000);
                var generated=ruin.generate(holder,world.dimension(),registry,generator,generator.getBiomeSource(),sampler,random,
                    world.getStructureTemplateManager(),world.getSeed(),candidate,world,biome->ruin.biomes().contains(biome));
                if(generated==null)continue;
                var generatedPiece=generated.getPieces().stream().filter(OdysseyStructure.Piece.class::isInstance).map(OdysseyStructure.Piece.class::cast).findFirst().orElseThrow();
                var bounds=generatedPiece.getBoundingBox();
                for(int x=bounds.minX();x<=bounds.maxX()&&crossing==null;x++)for(int z=bounds.minZ();z<=bounds.maxZ();z++)
                    if((x>>4)!=candidate.x()||(z>>4)!=candidate.z()){crossing=new BlockPos(x,bounds.getCenter().getY(),z);break;}
                if(crossing!=null){chosenStart=generated;chosenPiece=generatedPiece;at=candidate;}
            }
            c.assertTrue(crossing!=null,"The registered native generator produces a cross-boundary ruin");
            start=chosenStart;piece=chosenPiece;inside=crossing;
            origin=world.getChunk(at.x(),at.z());local=world.getChunkAt(inside);
            c.assertTrue(local!=origin,"Discovery must follow a native structure reference into another chunk");
            oldOrigin=origin.getStartForStructure(ruin);oldLocal=local.getStartForStructure(ruin);
            oldReferences=new it.unimi.dsi.fastutil.longs.LongOpenHashSet(local.getReferencesForStructure(ruin));
            // Install only metadata produced by native generation, restoring it after the test.
            origin.setStartForStructure(ruin,start);NativeStructureFixture.replaceStart(local,ruin,null);
            c.assertFalse(local.getAllStarts().containsKey(ruin),"A reference-only chunk has no null-valued native structure start");
            local.getReferencesForStructure(ruin).clear();local.addReferenceForStructure(ruin,origin.getPos().pack());
        }
        @Override public void close(){
            NativeStructureFixture.replaceStart(origin,ruin,oldOrigin);
            NativeStructureFixture.replaceStart(local,ruin,oldLocal);
            local.getReferencesForStructure(ruin).clear();local.getReferencesForStructure(ruin).addAll(oldReferences);
        }
    }

    @GameTest public void unloadedCurrentChunkNeverStartsTerrainOrChangesPlayerState(GameTestHelper c){
        var p=new ModeGameTests().player(c,"journal-cold-local");var world=c.getLevel();var source=world.getChunkSource();
        var distant=new ChunkPos(800_000,800_000);
        try {
            p.setGameMode(GameType.SURVIVAL);GameModes.state(p).putString("active","SURVIVAL");OdysseyJournal.state(p);
            p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));p.inventoryMenu.setCarried(new ItemStack(Items.EMERALD,3));
            p.setPos(distant.getMinBlockX()+.5,p.getY(),distant.getMinBlockZ()+.5);
            c.assertTrue(source.getChunkNow(distant.x(),distant.z())==null,"The remote current chunk really starts unavailable");
            var before=GameModes.state(p).copy();int loaded=source.getLoadedChunksCount();
            try(var sample=OdysseyJournalChunkProbe.begin(source)){
                c.assertFalse(OdysseyJournal.discover(p),"An unavailable local chunk does not invent discovery");
                c.assertValueEqual(sample.lookups(distant),1,"One completed-only current-chunk lookup");
                c.assertValueEqual(sample.totalLookups(),1,"No reference scan starts before the local chunk is ready");
                c.assertValueEqual(sample.synchronousFullRequests,0,"Discovery requests no synchronous FULL chunks");
            }
            c.assertValueEqual(source.getLoadedChunksCount(),loaded,"Discovery never loads remote terrain");
            c.assertTrue(source.getChunkNow(distant.x(),distant.z())==null,"The remote chunk remains unavailable");
            c.assertValueEqual(GameModes.state(p),before,"Journal, mode and saved profiles remain unchanged");
            c.assertTrue(p.getInventory().getItem(0).is(Items.DIAMOND)&&p.getInventory().getItem(0).getCount()==7,"Existing inventory is preserved");
            c.assertTrue(p.inventoryMenu.getCarried().is(Items.EMERALD)&&p.inventoryMenu.getCarried().getCount()==3,"Busy cursor is preserved");
        } finally {p.inventoryMenu.setCarried(ItemStack.EMPTY);world.getServer().getPlayerList().remove(p);}
        c.succeed();
    }

    @GameTest public void pendingNativeReferenceRetriesWithoutWaitingThenDiscoversGeneratedPiece(GameTestHelper c) throws ReflectiveOperationException {
        var p=new ModeGameTests().player(c,"journal-pending-ref");var world=c.getLevel();var source=world.getChunkSource();
        try(var generated=new GeneratedRuin(c)){
            p.setGameMode(GameType.SURVIVAL);GameModes.state(p).putString("active","SURVIVAL");p.setPos(Vec3.atBottomCenterOf(generated.inside));
            c.assertTrue(generated.local.getReferencesForStructure(generated.ruin).contains(generated.origin.getPos().pack()),"The local chunk references the actual native generated start");
            c.assertValueEqual(OdysseyJournal.count(p),0,"The fixture has granted no journal progress");
            var before=OdysseyJournal.state(p).copy();int loaded=source.getLoadedChunksCount();
            try(var pending=new PendingFull(c,source,generated.origin)){
                c.assertTrue(world.isLoaded(new BlockPos(generated.origin.getPos().getMinBlockX(),generated.inside.getY(),generated.origin.getPos().getMinBlockZ())),"A FULL ticket still satisfies vanilla isLoaded while its future is incomplete");
                c.assertTrue(source.getChunkNow(generated.origin.getPos().x(),generated.origin.getPos().z())==null,"The native manager itself refuses the incomplete FULL future");
                c.assertTrue(source.getChunkNow(generated.local.getPos().x(),generated.local.getPos().z())==generated.local,"The player's local chunk remains genuinely ready");
                try(var sample=OdysseyJournalChunkProbe.begin(source)){
                    c.assertFalse(OdysseyJournal.discover(p),"An incomplete referenced start waits for a later journal check");
                    c.assertValueEqual(sample.lookups(generated.origin.getPos()),1,"The reference uses one completed-only native lookup");
                    c.assertValueEqual(sample.synchronousFullRequests,0,"The incomplete native future is never synchronously requested");
                }
                c.assertFalse(pending.incomplete.isDone(),"Discovery does not complete or schedule the suspended native future");
                c.assertValueEqual(OdysseyJournal.state(p),before,"Deferred discovery preserves all journal data");
                c.assertValueEqual(source.getLoadedChunksCount(),loaded,"The reference check requests no additional terrain");
            }
            c.assertTrue(source.getChunkNow(generated.origin.getPos().x(),generated.origin.getPos().z())==generated.origin,"Restoring the real completed native future makes the same start available");
            p.setGameMode(GameType.CREATIVE);
            try(var sample=OdysseyJournalChunkProbe.begin(source)){
                c.assertFalse(OdysseyJournal.discover(p),"Creative abilities still cannot discover ruins");
                c.assertValueEqual(sample.totalLookups(),0,"Ineligible players never begin chunk lookup");
            }
            p.setGameMode(GameType.SURVIVAL);p.setPos(Vec3.atBottomCenterOf(generated.inside.offset(0,generated.piece.getBoundingBox().getYSpan()+1,0)));
            c.assertFalse(OdysseyJournal.discover(p),"The same ready structure reference cannot grant progress outside its real piece bounds");
            p.setPos(Vec3.atBottomCenterOf(generated.inside));
            try(var sample=OdysseyJournalChunkProbe.begin(source)){
                c.assertTrue(OdysseyJournal.discover(p),"The unchanged player position discovers the real native generated piece once its future completes");
                c.assertValueEqual(sample.synchronousFullRequests,0,"Successful discovery also uses completed-only FULL reads");
            }
            c.assertValueEqual(OdysseyJournal.count(p),1,"Only the current native realm is awarded");
            c.assertTrue(OdysseyJournal.state(p).getStringOr("shrine_0","").startsWith(generated.inside.toShortString()+" • "),"Discovery preserves the actual entered position and native biome theme");
            c.assertTrue(generated.origin.getStartForStructure(generated.ruin)==generated.start,"Discovery leaves the generated structure start unchanged");
            try(var sample=OdysseyJournalChunkProbe.begin(source)){
                c.assertFalse(OdysseyJournal.discover(p),"The same native ruin cannot grant duplicate progress");
                c.assertValueEqual(sample.totalLookups(),0,"Already discovered realms retain their fast guard");
            }
        } finally {world.getServer().getPlayerList().remove(p);}
        c.succeed();
    }
}

package dev.convergence;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Exclusive native batch, serialized within the batch to isolate exact shared-world budget assertions. */
public class AstralRemovalGameTests {
    @GameTest(maxTicks=1200) public void twelveNativeTerminalRemovalsRetireRuinReceiptsAndFreeWorldBudget(GameTestHelper c){
        NativeSessionFixture.startSerialized(c,()->new NativeSessionFixture(c){
            AstralGameTests.Fixture f;
            @Override void start(){
                f=new AstralGameTests.Fixture(c,"astral-terminal");
                String dimension=c.getLevel().dimension().identifier().toString();
                c.assertValueEqual(f.manager.encounters.active(dimension),0,"The serialized removal fixture starts with no other live guardian reservation");
                for(int i=0;i<AstralWilds.WORLD_CAP;i++)f.recorded(AstralWilds.Variant.CINDER);
                c.assertValueEqual(f.manager.encounters.active(dimension),AstralWilds.WORLD_CAP,"Twelve actual native guardians fill the saved active-world budget");
                // Discard is the native removal reason used by Peaceful despawns and conversions.
                // Exercise ENTITY_UNLOAD itself; do not call Manager.unload as a substitute.
                for(var mob:f.mobs)mob.discard();
                later(3,()->{
                    c.assertTrue(f.mobs.stream().noneMatch(mob->f.manager.loaded.containsKey(mob.getUUID())),"Actual terminal removals clear their live entity index");
                    c.assertValueEqual(f.manager.encounters.active(dimension),0,"Terminal guardians cannot permanently occupy all twelve budget slots");
                    for(String key:f.keys){var receipt=f.manager.encounters.entry(key);c.assertTrue(receipt.getBooleanOr("ended",false),"Each removed guardian retains an ended ruin tombstone");c.assertValueEqual(receipt.getStringOr("retired_reason",""),"DISCARDED","Exact native terminal reason is recorded");}
                    var spentKey=f.keys.getFirst();var center=f.p.blockPosition().offset(10,0,0);
                    var spent=new OdysseyJournal.LoadedRuin(spentKey,center,new BoundingBox(center.getX()-5,center.getY(),center.getZ()-5,center.getX()+5,center.getY()+5,center.getZ()+5),"plains");
                    c.assertTrue(f.manager.spawn(f.p,spent)==null,"A terminal encounter still cannot be repeated at the same ruin");
                    f.spawn(f.ruin());c.assertValueEqual(f.manager.encounters.active(dimension),1,"A different loaded ruin can create a new native encounter after budget recovery");
                    succeed();
                });
            }
            @Override void cleanup(){if(f!=null)f.close();}
        });
    }
    @GameTest(maxTicks=1200) public void nativeChunkUnloadRetainsLivingEncounterAndBlocksDuplicate(GameTestHelper c){
        NativeSessionFixture.startSerialized(c,()->new NativeSessionFixture(c){
            AstralGameTests.Fixture f;
            @Override void start(){
                f=new AstralGameTests.Fixture(c,"astral-unload");
                String dimension=c.getLevel().dimension().identifier().toString();
                c.assertValueEqual(f.manager.encounters.active(dimension),0,"The serialized unload fixture starts with no other live guardian reservation");
                var ruin=f.ruin();var mob=f.spawn(ruin);mob.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
                later(3,()->{
                    c.assertFalse(f.manager.loaded.containsKey(mob.getUUID()),"Actual unloaded-to-chunk removal clears only the live index");
                    c.assertValueEqual(f.manager.encounters.active(dimension),1,"An unloaded living guardian retains its active-world reservation");
                    c.assertFalse(f.manager.encounters.entry(ruin.key()).getBooleanOr("ended",false),"Native chunk unload does not become a terminal tombstone");
                    c.assertTrue(f.manager.spawn(f.p,ruin)==null,"Revisiting an unloaded living encounter cannot create a duplicate guardian");
                    succeed();
                });
            }
            @Override void cleanup(){if(f!=null)f.close();}
        });
    }

    /** Native section visibility is controlled only in this disposable fixture.
     * Full terrain without entity tracking is the pre-callback startup state.
     */
    private record HiddenSection(net.minecraft.world.level.entity.PersistentEntitySectionManager<?> entities,
            net.minecraft.world.level.ChunkPos chunk,net.minecraft.world.level.entity.Visibility previous) implements AutoCloseable {
        @SuppressWarnings("unchecked")
        static HiddenSection at(net.minecraft.server.level.ServerLevel world,net.minecraft.core.BlockPos at){
            try{
                var entityField=net.minecraft.server.level.ServerLevel.class.getDeclaredField("entityManager");entityField.setAccessible(true);
                var entities=(net.minecraft.world.level.entity.PersistentEntitySectionManager<?>)entityField.get(world);
                var visibilityField=net.minecraft.world.level.entity.PersistentEntitySectionManager.class.getDeclaredField("chunkVisibility");visibilityField.setAccessible(true);
                var visibility=(it.unimi.dsi.fastutil.longs.Long2ObjectMap<net.minecraft.world.level.entity.Visibility>)visibilityField.get(entities);
                var chunk=new net.minecraft.world.level.ChunkPos(at.getX()>>4,at.getZ()>>4);
                var previous=visibility.get(chunk.pack());
                if(previous==null)previous=net.minecraft.world.level.entity.Visibility.HIDDEN;
                entities.updateChunkStatus(chunk,net.minecraft.world.level.entity.Visibility.HIDDEN);
                return new HiddenSection(entities,chunk,previous);
            }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native private fixture visibility access changed",failure);}
        }
        @Override public void close(){entities.updateChunkStatus(chunk,previous);}
    }

    @GameTest(maxTicks=1200) public void untrackedNativeRemovalRetiresTerminalReceiptsButKeepsChunkUnloadReservation(GameTestHelper c){
        NativeSessionFixture.startSerialized(c,()->new NativeSessionFixture(c){
            AstralGameTests.Fixture f;
            net.minecraft.world.entity.Mob unloaded;
            String unloadedKey;
            @Override void start(){
                f=new AstralGameTests.Fixture(c,"astral-untracked");
                String dimension=c.getLevel().dimension().identifier().toString();
                c.assertValueEqual(f.manager.encounters.active(dimension),0,"The deterministic untracked fixture starts without other live receipts");
                try(var visibility=HiddenSection.at(c.getLevel(),f.p.blockPosition().offset(8,0,0))){
                    for(int i=0;i<AstralWilds.WORLD_CAP;i++)f.recorded(AstralWilds.Variant.CINDER);
                    for(var mob:f.mobs){
                        c.assertTrue(c.getLevel().getChunkSource().getChunkNow(mob.blockPosition().getX()>>4,mob.blockPosition().getZ()>>4)!=null,
                            "The native terrain chunk is full while entity visibility is not yet accessible");
                        c.assertTrue(c.getLevel().getEntity(mob.getUUID())==null&&f.manager.recorded(mob),
                            "The actual inserted guardian has a trusted receipt but no native tracking entry");
                        mob.discard();
                        c.assertTrue(mob.getRemovalReason()==net.minecraft.world.entity.Entity.RemovalReason.DISCARDED
                            &&f.manager.loaded.get(mob.getUUID())==mob,
                            "Native terminal removal before tracking emits no unload callback to clear the cached guardian");
                    }
                }
                later(3,()->{
                    c.assertValueEqual(f.manager.encounters.active(dimension),0,"The normal manager tick retires every untracked terminal reservation");
                    c.assertTrue(f.mobs.stream().noneMatch(mob->f.manager.loaded.containsKey(mob.getUUID())),"Normal tick cleanup removes all terminated cache entries");
                    for(String key:f.keys){var receipt=f.manager.encounters.entry(key);c.assertTrue(receipt.getBooleanOr("ended",false),"Each terminal guardian retains its permanent ruin tombstone");c.assertValueEqual(receipt.getStringOr("retired_reason",""),"DISCARDED","Fallback retirement preserves the real native reason");}
                    try(var visibility=HiddenSection.at(c.getLevel(),f.p.blockPosition().offset(8,0,0))){
                        unloaded=f.recorded(AstralWilds.Variant.CINDER);unloadedKey=AstralWilds.ruinKey(unloaded);
                        c.assertTrue(c.getLevel().getEntity(unloaded.getUUID())==null,"The nonterminal case also begins before native tracking");
                        unloaded.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
                        c.assertTrue(f.manager.loaded.get(unloaded.getUUID())==unloaded,"The nonterminal case has no fake unload callback either");
                    }
                    later(3,()->{
                        c.assertFalse(f.manager.loaded.containsKey(unloaded.getUUID()),"Tick removes an untracked chunk-unload cache entry");
                        c.assertValueEqual(f.manager.encounters.active(dimension),1,"A nonterminal chunk unload still reserves its living encounter");
                        var receipt=f.manager.encounters.entry(unloadedKey);
                        c.assertFalse(receipt.getBooleanOr("ended",false),"Nonterminal fallback never becomes a tombstone");
                        c.assertValueEqual(receipt.getStringOr("retired_reason",""),"","Nonterminal removal never records a terminal reason");
                        f.manager.tick();
                        c.assertValueEqual(f.manager.encounters.active(dimension),1,"Repeated tick cleanup is idempotent for the retained reservation");
                        succeed();
                    });
                });
            }
            @Override void cleanup(){if(f!=null)f.close();}
        });
    }
}

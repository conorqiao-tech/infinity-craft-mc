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
}

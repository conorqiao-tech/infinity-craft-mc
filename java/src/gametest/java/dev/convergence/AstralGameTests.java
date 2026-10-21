package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Disposable native-world tests. Register this class in the exclusive suite, not alongside legacy mock profiles. */
public class AstralGameTests {
    @GameTest public void nativeFormTransportFailureLeavesNoStaleAction(GameTestHelper c){
        try(var f=new Fixture(c,"astral-form-io")){
            c.assertValueEqual(AstralJournal.form(f.p,AstralJournal.Page.JOURNAL,form->{throw new IllegalStateException("injected transport failure");}),0,"Transport exception is contained on the server thread");
            c.assertFalse(AstralJournal.ACTIVE.containsKey(f.p.getUUID()),"Failed form retains no action authority");
            c.assertFalse(AstralJournal.DELIVERIES.containsKey(f.p.getUUID()),"Failed form retains no delivery reference");
        }c.succeed();
    }
    static ItemStack tool(String id){return new ItemStack(Convergence.ITEMS.get("convergence:"+id));}
    static void eligible(ServerPlayer p){p.closeContainer();p.setGameMode(GameType.SURVIVAL);GameModes.state(p).putString("active","SURVIVAL");p.getInventory().clearContent();}
    static void aim(ServerPlayer p,Vec3 at){var d=at.subtract(p.getEyePosition());p.setYRot((float)Math.toDegrees(Math.atan2(d.z,d.x))-90);p.setYHeadRot(p.getYRot());p.setXRot((float)-Math.toDegrees(Math.atan2(d.y,Math.sqrt(d.x*d.x+d.z*d.z))));}
    static void aimAtCrop(ServerPlayer p,BlockPos at){
        var shape=p.level().getBlockState(at).getShape(p.level(),at);
        if(shape.isEmpty())throw new IllegalStateException("Fixture crop must have a native ray-selection shape");
        var bounds=shape.bounds();aim(p,bounds.getCenter().add(at.getX(),at.getY(),at.getZ()));
    }
    static void checkTrowelUsePreconditions(GameTestHelper c,ServerPlayer p,BlockPos at){
        c.assertTrue(AstralItems.operable(p),"Crop use begins in a ready native inventory session");
        c.assertValueEqual(AstralItems.held(p),"keepers_trowel","Native main hand holds the real registered trowel");
        c.assertFalse(p.getCooldowns().isOnCooldown(p.getMainHandItem()),"Fresh native trowel has no cooldown");
        c.assertTrue(p.mayBuild()&&p.level().mayInteract(p,at)&&p.mayInteract(p.level(),at),"Native crop permissions allow the Survival fixture");
        c.assertTrue(AstralItems.loadedAim(p),"Native ray's complete room is already loaded");
        var hit=p.pick(6,1,false);
        c.assertTrue(hit instanceof net.minecraft.world.phys.BlockHitResult b&&hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK&&b.getBlockPos().equals(at),"Native ray must hit crop "+at+" from "+p.position()+" eye "+p.getEyePosition()+" toward "+p.getViewVector(1)+"; actual "+(hit instanceof net.minecraft.world.phys.BlockHitResult b?hit.getType()+" "+b.getBlockPos():hit.getType()));
        c.assertTrue(p.mayUseItemAt(at,((net.minecraft.world.phys.BlockHitResult)hit).getDirection(),p.getMainHandItem()),"Native held-tool permissions allow the exact crop face");
        c.assertTrue(AstralItems.plainSlot(p,Items.BONE_MEAL)>=0,"One real unmodified bone meal slot is available");
    }
    static void reply(org.geysermc.cumulus.form.Form form,String text){try{org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form,text);}catch(Exception e){throw new RuntimeException(e);}}
    static final class Fixture implements AutoCloseable {
        final GameTestHelper c;final ServerPlayer p;final AstralWilds.Manager manager;final List<Mob> mobs=new ArrayList<>();final List<String> keys=new ArrayList<>();
        final Difficulty oldDifficulty;final boolean oldSpawn,oldMonsters;
        Fixture(GameTestHelper c,String name){
            this.c=c;p=new ModeGameTests().player(c,name.length()>16?name.substring(0,16):name);eligible(p);manager=AstralWilds.get(c.getLevel().getServer());
            oldDifficulty=c.getLevel().getDifficulty();oldSpawn=c.getLevel().getGameRules().get(GameRules.SPAWN_MOBS);oldMonsters=c.getLevel().getGameRules().get(GameRules.SPAWN_MONSTERS);
            c.getLevel().getServer().setDifficulty(Difficulty.NORMAL,true);c.getLevel().getGameRules().set(GameRules.SPAWN_MOBS,true,manager.server);c.getLevel().getGameRules().set(GameRules.SPAWN_MONSTERS,true,manager.server);
        }
        OdysseyJournal.LoadedRuin ruin(){
            var center=p.blockPosition().offset(10,0,0);String key="test:"+p.getStringUUID()+"@"+keys.size();keys.add(key);
            for(int x=-7;x<=7;x++)for(int z=-7;z<=7;z++)for(int y=-1;y<=3;y++)c.getLevel().setBlock(center.offset(x,y,z),(y==-1?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
            return new OdysseyJournal.LoadedRuin(key,center,new BoundingBox(center.getX()-5,center.getY(),center.getZ()-5,center.getX()+5,center.getY()+5,center.getZ()+5),"plains");
        }
        Mob spawn(OdysseyJournal.LoadedRuin ruin){var mob=manager.spawn(p,ruin);c.assertTrue(mob!=null,"A real native guardian spawns into checked loaded terrain");mob.setNoAi(true);mobs.add(mob);return mob;}
        Mob recorded(AstralWilds.Variant variant){
            var mob=variant.type.create(c.getLevel(),EntitySpawnReason.COMMAND);c.assertTrue(mob!=null,"Native type exists");mob.setPos(p.position().add(8,0,0));mob.setNoAi(true);
            String key="test:"+p.getStringUUID()+"@"+keys.size();keys.add(key);mob.addTag(AstralWilds.TAG);mob.addTag(AstralWilds.KIND+variant.id);mob.addTag(AstralWilds.RUIN+key);
            var entry=new net.minecraft.nbt.CompoundTag();entry.putString("uuid",mob.getStringUUID());entry.putString("dimension",c.getLevel().dimension().identifier().toString());entry.putString("variant",variant.id);entry.putBoolean("ended",false);manager.encounters.put(key,entry);
            c.assertTrue(c.getLevel().addFreshEntity(mob),"Native entity is inserted");manager.load(mob);mobs.add(mob);return mob;
        }
        @Override public void close(){for(var mob:mobs){manager.unload(mob);mob.discard();}for(String key:keys)manager.encounters.data.remove(key);manager.encounters.setDirty();AstralItems.ANCHORS.remove(p.getUUID());AstralJournal.ACTIVE.remove(p.getUUID());AstralJournal.DELIVERIES.remove(p.getUUID());p.closeContainer();manager.server.getPlayerList().remove(p);
            c.getLevel().getGameRules().set(GameRules.SPAWN_MOBS,oldSpawn,manager.server);c.getLevel().getGameRules().set(GameRules.SPAWN_MONSTERS,oldMonsters,manager.server);manager.server.setDifficulty(oldDifficulty,true);}
    }
    @GameTest public void originalItemsHaveLiteralNamesNativeComponentsAndNbt(GameTestHelper c){
        var ops=c.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for(String path:AstralItems.PATHS){var stack=tool(path);c.assertFalse(stack.isEmpty(),"Real registered Astral item: "+path);c.assertValueEqual(stack.getMaxStackSize(),1,"One relic per stack");
            c.assertFalse(stack.getItemName().getString().startsWith("item.convergence."),"Literal crossplay name is present");
            var encoded=ItemStack.CODEC.encodeStart(ops,stack).getOrThrow();var restored=ItemStack.CODEC.parse(ops,encoded).getOrThrow();c.assertTrue(ItemStack.isSameItemSameComponents(stack,restored),"Real item survives native item NBT");}
        c.assertTrue(tool("keepers_trowel").isDamageableItem(),"Trowel retains native iron-hoe durability");c.assertTrue(tool("riftspool").isDamageableItem(),"Riftspool has real durability");c.succeed();
    }
    @GameTest public void loadedRuinLookupUsesActualNativeMetadataWithoutLoadingOrMutating(GameTestHelper c){
        var world=c.getLevel();var at=c.absolutePos(new BlockPos(0,2,0));var piece=new OdysseyStructure.Piece(world.getStructureTemplateManager(),at,"plains",net.minecraft.world.level.block.Rotation.NONE);var inside=piece.getBoundingBox().getCenter();
        var chunk=world.getChunkAt(inside);var ruin=world.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(OdysseyStructure.KEY).value();var oldStart=chunk.getStartForStructure(ruin);var oldRefs=new it.unimi.dsi.fastutil.longs.LongOpenHashSet(chunk.getReferencesForStructure(ruin));
        var start=new net.minecraft.world.level.levelgen.structure.StructureStart(ruin,chunk.getPos(),0,new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(List.of(piece)));
        try(var f=new Fixture(c,"astral-lookup")){
            chunk.setStartForStructure(ruin,start);chunk.addReferenceForStructure(ruin,chunk.getPos().pack());var before=GameModes.state(f.p).copy();int count=world.getChunkSource().getLoadedChunksCount();
            try(var sample=OdysseyJournalChunkProbe.begin(world.getChunkSource())){var found=OdysseyJournal.loadedRuin(world,inside,0);c.assertTrue(found!=null,"Loaded-only helper reads the actual native Odyssey piece");c.assertValueEqual(found.center(),inside,"Actual native piece center is returned");c.assertValueEqual(sample.synchronousFullRequests,0,"Ready lookup never requests a synchronous chunk");}
            c.assertValueEqual(GameModes.state(f.p),before,"Pure lookup changes no player progression");c.assertValueEqual(world.getChunkSource().getLoadedChunksCount(),count,"Pure lookup generates no new terrain");
            var remote=new BlockPos(12_800_000,inside.getY(),12_800_000);try(var sample=OdysseyJournalChunkProbe.begin(world.getChunkSource())){c.assertTrue(OdysseyJournal.loadedRuin(world,remote,32)==null,"An unavailable current chunk refuses the scan");c.assertValueEqual(sample.synchronousFullRequests,0,"Unavailable lookup never requests a synchronous chunk");c.assertValueEqual(sample.totalLookups(),1,"Unavailable current chunk exits before scanning neighbors");}
        }finally{NativeStructureFixture.replaceStart(chunk,ruin,oldStart);chunk.getReferencesForStructure(ruin).clear();chunk.getReferencesForStructure(ruin).addAll(oldRefs);}c.succeed();
    }
    @GameTest public void actualRecipesMatchAndNativeCraftCallbacksGrantOnlyRealProgress(GameTestHelper c){
        var patterns=List.of(List.of(" A ","GEG"," A "),List.of(" S ","PEP"," A "),List.of(" A "," H "," E "));
        var ingredients=Map.of('A',Items.AMETHYST_SHARD,'G',Items.GLOWSTONE_DUST,'E',Items.ECHO_SHARD,'S',Items.STRING,'P',Items.ENDER_PEARL,'H',Items.IRON_HOE);
        try(var f=new Fixture(c,"astral-crafting")){
            for(int i=0;i<patterns.size();i++){var grid=new ArrayList<ItemStack>();for(String row:patterns.get(i))for(char symbol:row.toCharArray())grid.add(symbol==' '?ItemStack.EMPTY:new ItemStack(ingredients.get(symbol)));
                var input=CraftingInput.of(3,3,grid);var recipe=f.manager.server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,input,c.getLevel()).orElseThrow();
                c.assertValueEqual(recipe.id().identifier(),Identifier.parse("convergence:"+AstralItems.PATHS.get(i)),"Native manager matches exact Astral recipe");var result=recipe.value().assemble(input);
                c.assertTrue(result.is(Convergence.ITEMS.get("convergence:"+AstralItems.PATHS.get(i))),"Recipe creates the registered item");result.onCraftedBy(f.p,1);
                if(i<2)c.assertFalse(AstralJournal.has(f.p,4),"Partial crafting cannot finish the three-tool milestone");}
            c.assertTrue(AstralJournal.has(f.p,4),"Actual native crafted callbacks finish the workshop");
            int before=AstralJournal.state(f.p).getIntOr("crafted",0);f.p.setGameMode(GameType.CREATIVE);tool("starshard_lantern").onCraftedBy(f.p,1);c.assertValueEqual(AstralJournal.state(f.p).getIntOr("crafted",0),before,"Creative does not manufacture Survival progression");
        }c.succeed();
    }
    @GameTest public void oneRuinEncounterSurvivesUnloadLostIndexAndNativeEntityNbt(GameTestHelper c){
        try(var f=new Fixture(c,"astral-guardian")){
            var ruin=f.ruin();var mob=f.spawn(ruin);c.assertTrue(AstralWilds.variant(mob)!=null,"Only a tagged matching native type is a guardian");
            c.assertTrue(f.manager.spawn(f.p,ruin)==null,"A live guardian prevents duplicate spawn");
            mob.setHealth(7);mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(41);mob.setCustomName(Component.literal("Owner's edited guardian"));
            var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,c.getLevel().registryAccess());mob.saveWithoutId(out);
            var restored=mob.getType().create(c.getLevel(),EntitySpawnReason.LOAD);c.assertTrue(restored instanceof Mob,"Native mob reload exists");var loaded=(Mob)restored;
            loaded.load(TagValueInput.create(ProblemReporter.DISCARDING,c.getLevel().registryAccess(),out.buildResult()));
            f.manager.unload(mob);c.assertFalse(f.manager.loaded.containsKey(mob.getUUID()),"Unloading removes only the live index");
            c.assertTrue(f.manager.spawn(f.p,ruin)==null,"An unloaded or missing guardian remains a consumed encounter");
            f.manager.load(loaded);c.assertValueEqual(loaded.getHealth(),7F,"Load does not heal a damaged guardian");c.assertValueEqual(loaded.getAttribute(Attributes.MAX_HEALTH).getBaseValue(),41D,"Load preserves edited native attributes");c.assertValueEqual(loaded.getCustomName().getString(),"Owner's edited guardian","Load preserves edited names");
            c.assertTrue(f.manager.loaded.get(mob.getUUID())==loaded,"Saved exact UUID reconstructs index without reconfiguration");f.manager.unload(loaded);loaded.discard();
            var encoded=AstralWilds.Encounters.TYPE.codec().encodeStart(NbtOps.INSTANCE,f.manager.encounters).getOrThrow();var saved=AstralWilds.Encounters.TYPE.codec().parse(NbtOps.INSTANCE,encoded).getOrThrow();c.assertTrue(saved.contains(ruin.key()),"Ruin tombstone survives native SavedData codec");
        }c.succeed();
    }
    @GameTest public void naturalSpawningRespectsMobRulesDifficultyModesAndWorldBudget(GameTestHelper c){
        try(var f=new Fixture(c,"astral-spawnrule")){
            var ruin=f.ruin();c.getLevel().getGameRules().set(GameRules.SPAWN_MOBS,false,f.manager.server);c.assertTrue(f.manager.spawn(f.p,ruin)==null,"Disabled mob spawning is respected");c.getLevel().getGameRules().set(GameRules.SPAWN_MOBS,true,f.manager.server);
            c.getLevel().getGameRules().set(GameRules.SPAWN_MONSTERS,false,f.manager.server);c.assertTrue(f.manager.spawn(f.p,ruin)==null,"Disabled monster spawning is respected");c.getLevel().getGameRules().set(GameRules.SPAWN_MONSTERS,true,f.manager.server);
            f.manager.server.setDifficulty(Difficulty.PEACEFUL,true);c.assertTrue(f.manager.spawn(f.p,ruin)==null,"Peaceful creates no guardian");f.manager.server.setDifficulty(Difficulty.NORMAL,true);
            f.p.setGameMode(GameType.CREATIVE);c.assertTrue(f.manager.spawn(f.p,ruin)==null,"Creative cannot trigger an encounter");f.p.setGameMode(GameType.SURVIVAL);
            var dimension=c.getLevel().dimension().identifier().toString();for(int i=0;i<AstralWilds.WORLD_CAP;i++){String key="test:"+f.p.getStringUUID()+"cap"+i;f.keys.add(key);var e=new net.minecraft.nbt.CompoundTag();e.putString("dimension",dimension);e.putString("uuid",UUID.randomUUID().toString());f.manager.encounters.put(key,e);}
            c.assertTrue(f.manager.spawn(f.p,ruin)==null,"Unloaded unresolved guardians count conservatively toward the per-world cap");
        }c.succeed();
    }
    @GameTest public void actualPlayerKillCreditsOnlyRecordedVariantsAndCannotReplayLoot(GameTestHelper c){
        try(var f=new Fixture(c,"astral-kills")){
            for(var v:AstralWilds.Variant.values()){
                var mob=f.recorded(v);mob.setHealth(1);mob.hurtServer(c.getLevel(),c.getLevel().damageSources().playerAttack(f.p),10);
                c.assertTrue(f.manager.encounters.entry(AstralWilds.ruinKey(mob)).getBooleanOr("ended",false),"Actual native death closes the exact encounter");
                c.assertTrue((AstralJournal.state(f.p).getIntOr("kills",0)&1<<v.ordinal())!=0,"Actual credited player receives the matching kill");
                int drops=c.getLevel().getEntitiesOfClass(ItemEntity.class,mob.getBoundingBox().inflate(5)).stream().mapToInt(e->e.getItem().getCount()).sum();
                f.manager.died(mob,c.getLevel().damageSources().playerAttack(f.p));c.assertValueEqual(c.getLevel().getEntitiesOfClass(ItemEntity.class,mob.getBoundingBox().inflate(5)).stream().mapToInt(e->e.getItem().getCount()).sum(),drops,"Repeated death callback cannot duplicate bonus loot");
            }
            var ordinary=EntityTypes.HUSK.create(c.getLevel(),EntitySpawnReason.COMMAND);ordinary.setPos(f.p.position().add(4,0,0));c.getLevel().addFreshEntity(ordinary);int before=AstralJournal.earned(f.p);f.manager.load(ordinary);c.assertFalse(f.manager.loaded.containsKey(ordinary.getUUID()),"Ordinary native mobs never enter the Astral index");ordinary.setHealth(1);ordinary.hurtServer(c.getLevel(),c.getLevel().damageSources().playerAttack(f.p),10);c.assertValueEqual(AstralJournal.earned(f.p),before,"Ordinary kills grant no Astral progress");ordinary.discard();
        }c.succeed();
    }
    @GameTest public void lanternUsesNativeCooldownAndPreservesStrongerNightVision(GameTestHelper c){
        try(var f=new Fixture(c,"astral-lantern")){
            f.p.setItemInHand(InteractionHand.MAIN_HAND,tool("starshard_lantern"));f.p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,1000,2));
            c.assertValueEqual(AstralItems.use(f.p),1,"Actual registered item performs a native use");c.assertValueEqual(f.p.getEffect(MobEffects.NIGHT_VISION).getAmplifier(),2,"Existing stronger effect remains intact");c.assertValueEqual(f.p.getEffect(MobEffects.NIGHT_VISION).getDuration(),1000,"Existing stronger effect duration is preserved");
            c.assertValueEqual(AstralItems.use(f.p),0,"Native cooldown blocks immediate repeat use");c.assertValueEqual(AstralJournal.state(f.p).getIntOr("used",0),1,"Only successful use is recorded");
            var fake=new ItemStack(Items.LANTERN);fake.set(DataComponents.CUSTOM_NAME,Component.literal("Starshard Lantern"));f.p.setItemInHand(InteractionHand.MAIN_HAND,fake);c.assertValueEqual(AstralItems.use(f.p),0,"Renamed vanilla item cannot invoke the Astral power");
        }c.succeed();
    }
    @GameTest public void trowelActuallyGrowsOneCropConsumesRealMealAndDurability(GameTestHelper c){
        try(var f=new Fixture(c,"astral-trowel")){
            var at=f.p.blockPosition().offset(2,0,0);c.getLevel().setBlock(at.below(),Blocks.FARMLAND.defaultBlockState(),3);c.getLevel().setBlock(at,Blocks.WHEAT.defaultBlockState(),3);
            f.p.setItemInHand(InteractionHand.MAIN_HAND,tool("keepers_trowel"));f.p.getInventory().setItem(1,new ItemStack(Items.BONE_MEAL,4));aimAtCrop(f.p,at);
            checkTrowelUsePreconditions(c,f.p,at);
            c.assertValueEqual(AstralItems.trowel(f.p,at),1,"Checked native raycast grows one real wheat block");c.assertTrue(((CropBlock)Blocks.WHEAT).getAge(c.getLevel().getBlockState(at))>0,"Native crop age actually advances");c.assertValueEqual(f.p.getInventory().getItem(1).getCount(),3,"One actual bone meal is consumed");c.assertValueEqual(f.p.getMainHandItem().getDamageValue(),1,"Real tool loses one durability");
            c.assertValueEqual(AstralItems.trowel(f.p,at),0,"Immediate replay is blocked by native cooldown");c.assertValueEqual(f.p.getInventory().getItem(1).getCount(),3,"Rejected replay consumes nothing");c.assertTrue((AstralJournal.state(f.p).getIntOr("used",0)&4)!=0,"Successful crop interaction progresses the journal");
        }c.succeed();
    }
    @GameTest public void trowelPreservesNamedResourcesAndWorldInteractionPermissions(GameTestHelper c){
        try(var f=new Fixture(c,"astral-trowel-no")){
            var at=f.p.blockPosition().offset(2,0,0);c.getLevel().setBlock(at.below(),Blocks.FARMLAND.defaultBlockState(),3);c.getLevel().setBlock(at,Blocks.WHEAT.defaultBlockState(),3);var before=c.getLevel().getBlockState(at);
            f.p.setItemInHand(InteractionHand.MAIN_HAND,tool("keepers_trowel"));var meal=new ItemStack(Items.BONE_MEAL,4);meal.set(DataComponents.CUSTOM_NAME,Component.literal("Saved keepsake"));f.p.getInventory().setItem(1,meal);aimAtCrop(f.p,at);
            c.assertValueEqual(AstralItems.trowel(f.p,at),0,"Named resources are not silently consumed");c.assertValueEqual(meal.getCount(),4,"Keepsake remains intact");
            f.p.getInventory().setItem(1,new ItemStack(Items.BONE_MEAL,4));f.p.setGameMode(GameType.ADVENTURE);c.assertValueEqual(AstralItems.trowel(f.p,at),0,"Adventure mayBuild denial is respected");f.p.setGameMode(GameType.SPECTATOR);c.assertValueEqual(AstralItems.trowel(f.p,at),0,"Spectator cannot edit crops");c.assertValueEqual(c.getLevel().getBlockState(at),before,"Refused uses leave crop state intact");
        }c.succeed();
    }
    @GameTest public void creativeKitIsAllOrNothingAndDoesNotAwardSurvivalCrafting(GameTestHelper c){
        try(var f=new Fixture(c,"astral-fullpack")){
            f.p.setGameMode(GameType.CREATIVE);for(int i=0;i<36;i++)f.p.getInventory().setItem(i,new ItemStack(Items.DIRT,64));f.p.getInventory().setItem(0,ItemStack.EMPTY);f.p.getInventory().setItem(1,ItemStack.EMPTY);
            c.assertValueEqual(AstralItems.creativeKit(f.p),0,"Two slots cannot partially deliver three tools");c.assertTrue(f.p.getInventory().getItem(0).isEmpty()&&f.p.getInventory().getItem(1).isEmpty(),"Failed kit replaces nothing");
            f.p.getInventory().setItem(2,ItemStack.EMPTY);c.assertValueEqual(AstralItems.creativeKit(f.p),3,"Three slots receive three real tools");c.assertValueEqual(AstralJournal.earned(f.p),0,"Creative delivery cannot earn Survival milestones");
        }c.succeed();
    }
    @GameTest public void milestonesPayoutOncePersistAndUnlockEarnedOrbit(GameTestHelper c){
        try(var f=new Fixture(c,"astral-progress")){
            long before=InfiniEconomy.get(f.manager.server).balance(f.p.getUUID());AstralJournal.encountered(f.p);for(var v:AstralWilds.Variant.values())AstralJournal.killed(f.p,v);for(String path:AstralItems.PATHS){AstralJournal.crafted(f.p,"convergence:"+path);AstralJournal.used(f.p,path);}
            c.assertValueEqual(AstralJournal.earned(f.p),63,"Six real milestone receipts are derived from recorded events");c.assertValueEqual(AstralJournal.state(f.p).getIntOr("paid",0),63,"Durably credited milestone receipts are recorded");c.assertValueEqual(InfiniEconomy.get(f.manager.server).balance(f.p.getUUID()),before+1500,"Whole-Infini rewards become the exact ledger units");
            AstralJournal.reconcile(f.p);AstralJournal.encountered(f.p);c.assertValueEqual(InfiniEconomy.get(f.manager.server).balance(f.p.getUUID()),before+1500,"Repeated reconciliation and events cannot multiply payouts");c.assertValueEqual(AstralJournal.wear(f.p,true),1,"Earned original cosmetic can be selected");
            c.assertTrue(AstralJournal.orbitVisible(f.p),"Earned selected Orbit is visible during an allowed session");
            f.p.setInvisible(true);c.assertFalse(AstralJournal.orbitVisible(f.p),"Earned Orbit does not reveal an invisible player");
            f.p.setInvisible(false);c.assertTrue(AstralJournal.orbitVisible(f.p),"Orbit resumes after invisibility ends without losing the saved selection");
            var disk=net.minecraft.nbt.NbtIo.readCompressed(GameModes.savedPlayerFile(f.manager.server,f.p.getUUID()),net.minecraft.nbt.NbtAccounter.create(64L*1024*1024));var fresh=new ServerPlayer(f.manager.server,c.getLevel(),f.p.getGameProfile(),f.p.clientInformation());fresh.load(TagValueInput.create(ProblemReporter.DISCARDING,f.p.registryAccess(),disk));
            c.assertValueEqual(AstralJournal.earned(fresh),63,"Progress survives actual native player-file save/reload");c.assertTrue(AstralJournal.orbit(fresh),"Cosmetic selection survives actual native player save");
            for(String id:AstralJournal.MILESTONES){var a=f.manager.server.getAdvancements().get(Identifier.parse("convergence:astral/"+id));c.assertTrue(a!=null&&f.p.getAdvancements().getOrStartProgress(a).isDone(),"Registered native advancement reflects saved progression: "+id);}
        }catch(java.io.IOException e){throw new RuntimeException(e);}c.succeed();
    }
    @GameTest public void touchAndJavaJournalRefuseOldSessionsAndCannotMoveIcons(GameTestHelper c){
        try(var f=new Fixture(c,"astral-menus")){
            var before=GameModes.state(f.p).copy();AstralJournal.view(f.p,AstralJournal.Page.JOURNAL);c.assertValueEqual(GameModes.state(f.p),before,"Preview does not mutate personal progress");
            var forms=new ArrayList<org.geysermc.cumulus.form.Form>();AstralJournal.form(f.p,AstralJournal.Page.JOURNAL,form->{forms.add(form);return true;});var stale=forms.getLast();AstralJournal.form(f.p,AstralJournal.Page.JOURNAL,form->{forms.add(form);return true;});reply(stale,"0");c.assertTrue(f.p.containerMenu==f.p.inventoryMenu,"Replaced Bedrock form cannot act");
            reply(forms.getLast(),"0");c.assertTrue(f.p.containerMenu instanceof AstralJournal.Handler,"Current native form opens the actual recipe view");var old=f.p.containerMenu;old.clicked(27,0,ContainerInput.THROW,f.p);c.assertTrue(old.getCarried().isEmpty(),"Journal controls cannot become inventory items");f.p.closeContainer();ServerMenu.open(f.p);var current=f.p.containerMenu;old.clicked(27,0,ContainerInput.PICKUP,f.p);c.assertTrue(f.p.containerMenu==current,"Stale Java view cannot replace a newer screen");
        }c.succeed();
    }
    @GameTest(maxTicks=1200) public void realSessionRiftspoolReturnsConsumesAndCancelsOnReconnect(GameTestHelper c){NativeSessionFixture.startSerialized(c,()->new NativeSessionFixture(c){
        Joined joined;AstralItems.Anchor old;BlockPos blocked;
        @Override void start(){joined=join("astral_return",2);later(6,()->{
            eligible(joined.player);joined.player.setItemInHand(InteractionHand.MAIN_HAND,tool("riftspool"));joined.player.getInventory().setItem(1,new ItemStack(Items.ENDER_PEARL,3));
            c.assertValueEqual(AstralItems.spool(joined.player),1,"Native session sets a checked return anchor");old=AstralItems.ANCHORS.get(joined.player.getUUID());
            joined.player.setGameMode(GameType.CREATIVE);c.assertFalse(old.valid(joined.player),"Native Creative mode invalidates a Survival Riftspool anchor");joined.player.setGameMode(GameType.SURVIVAL);
            later(12,()->{joined.player.setPos(old.at().add(1,0,0));c.assertValueEqual(AstralItems.spool(joined.player),1,"Second real use returns through native teleport");joined.drain();c.assertTrue(joined.player.position().distanceToSqr(old.at())<.01,"Player returns to the actual anchor");c.assertValueEqual(joined.player.getInventory().getItem(1).getCount(),2,"Successful return consumes exactly one real pearl");c.assertValueEqual(joined.player.getMainHandItem().getDamageValue(),1,"Return damages the real tool");
                later(110,()->{c.assertValueEqual(AstralItems.spool(joined.player),1,"Native cooldown expires through actual connected player ticks");old=AstralItems.ANCHORS.get(joined.player.getUUID());reconnect(joined,replacement->{joined=replacement;c.assertFalse(old.valid(joined.player),"Old anchor cannot follow a new connection");c.assertFalse(AstralItems.ANCHORS.containsKey(joined.player.getUUID()),"Actual disconnect clears temporary anchors");c.assertValueEqual(joined.player.getInventory().getItem(1).getCount(),2,"Saved inventory consumption survives real reconnect");succeed();});});
            });
        });}
        @Override void cleanup(){if(joined!=null)AstralItems.ANCHORS.remove(joined.player.getUUID());}
    });}
    @GameTest(maxTicks=1200) public void realSessionRiftspoolExpiryNeverConsumesPearls(GameTestHelper c){NativeSessionFixture.startSerialized(c,()->new NativeSessionFixture(c){
        Joined joined;AstralItems.Anchor anchor;
        @Override void start(){joined=join("astral_expiry",2);later(6,()->{eligible(joined.player);joined.player.setItemInHand(InteractionHand.MAIN_HAND,tool("riftspool"));joined.player.getInventory().setItem(1,new ItemStack(Items.ENDER_PEARL,2));c.assertValueEqual(AstralItems.spool(joined.player),1,"Real anchor begins");anchor=AstralItems.ANCHORS.get(joined.player.getUUID());later(405,()->{c.assertFalse(anchor.valid(joined.player),"Twenty-second expiry uses real server ticks");c.assertFalse(AstralItems.ANCHORS.containsKey(joined.player.getUUID()),"Native tick lifecycle removes expired anchor");c.assertValueEqual(joined.player.getInventory().getItem(1).getCount(),2,"Expiry consumes no pearl");succeed();});});}
        @Override void cleanup(){if(joined!=null)AstralItems.ANCHORS.remove(joined.player.getUUID());}
    });}
}
